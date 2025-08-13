// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.hive;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.annotations.VisibleForTesting;
import com.starrocks.common.Config;
import com.starrocks.connector.HdfsEnvironment;
import com.starrocks.metric.MetricRepo;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.HarFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FileSystemCache {
    private static final Logger LOG = LogManager.getLogger(FileSystemCache.class);

    // Configuration parameters
    private static final int MAX_CACHE_SIZE = Config.hdfs_client_max_cache_size;
    private static final long EXPIRE_AFTER_ACCESS_MINUTES = Config.hdfs_client_expire_after_access_minutes;
    private static final long REFRESH_AFTER_WRITE_MINUTES = Config.hdfs_client_refresh_after_write_minutes;
    private static final boolean ENABLE_FILE_SYSTEM_RECYCLE = Config.enable_file_system_recycle;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int maxSize = MAX_CACHE_SIZE;
    private Configuration conf = new Configuration();
    private Cache<String, FileSystemWrapper> cache = buildCache();
    // Zombie filesystem that were not successfully closed
    private final ConcurrentMap<String, FileSystemWrapper> zombieEntries = new ConcurrentHashMap<>();

    @VisibleForTesting
    public FileSystemCache(int maxSize, Configuration configuration) {
        this.maxSize = maxSize;
        this.cache = buildCache();
        this.conf = configuration;
    }

    public FileSystemCache() {
        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down FileSystemCache");
            clearCache();
            scheduler.shutdown();
        }));
    }

    private Cache<String, FileSystemWrapper> buildCache() {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .refreshAfterWrite(REFRESH_AFTER_WRITE_MINUTES, TimeUnit.MINUTES)
                .removalListener((String key, FileSystemWrapper wrapper, RemovalCause cause) -> {
                    // The old value was replaced due to the successful loading of the new value
                    // (such as refresh or put overwriting)
                    if (wrapper == null || cause == RemovalCause.REPLACED) {
                        return;
                    }
                    if (MetricRepo.hasInit) {
                        MetricRepo.COUNTER_FILE_SYSTEM_CACHE_EVICT.increase(1L);
                    }
                    LOG.info("Evicting cache key: {} due to {} (refCount={})",
                            key, cause, wrapper.refCount.get());
                    // Recycling filesystem
                    if (ENABLE_FILE_SYSTEM_RECYCLE) {
                        LOG.info("recycle fs will be putted into zombie by key:{}", key);
                        zombieEntries.put(key, wrapper);
                        return;
                    }
                    // Failed to close (still referenced), converted to a zombie entry
                    if (!wrapper.tryClose()) {
                        LOG.info("evicted cache to close fs fail, fs will be putted into zombie by key:{}", key);
                        zombieEntries.put(key, wrapper);
                    }
                })
                .executor(scheduler)
                .build(key -> {
                    LOG.info("The FileSystem is being refreshed: {}", key);
                    try {
                        return new FileSystemWrapper(loadFileSystem(key));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public FileSystemWrapper getFileSystem(String path, Configuration configuration) throws IOException {
        long startTime = System.nanoTime();
        LOG.info("get filesystem by path: {}", path);
        // rewrite the config
        this.conf = configuration;
        // initial the cache key
        String cacheKey = getCacheKey(path);
        try {
            FileSystemWrapper wrapper = cache.get(cacheKey, k -> {
                try {
                    FileSystem fs = loadFileSystem(k);
                    if (fs == null) {
                        throw new RuntimeException("Failed to create filesystem");
                    }
                    return new FileSystemWrapper(fs);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            if (wrapper == null) {
                throw new IOException("Failed to get filesystem wrapper for " + cacheKey);
            }

            // FileSystem fs = wrapper.acquire();
            long endTime = System.nanoTime();
            long elapsed = endTime - startTime;
            long elapsedMs = elapsed / 1_000_000;
            MetricRepo.HISTO_GET_FILE_SYSTEM_CACHE_LATENCY.update(elapsedMs);
            LOG.info("get filesystem elapse [{}] ms", elapsedMs);
            return wrapper;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw new IOException("Failed to get filesystem", e);
        }
    }

    private String getCacheKey(String path) {
        URI uri = new Path(path).toUri();
        if (uri.getScheme().startsWith("har")) {
            return path.split("\\.har")[0] + ".har";
        }
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private FileSystem loadFileSystem(String cacheKey) throws IOException {
        return createFileSystem(cacheKey);
    }

    private FileSystem createFileSystem(String cacheKey) throws IOException {
        URI uri = new Path(cacheKey).toUri();
        FileSystem fs = null;
        // 1. Give priority to reusing zombie entries
        // Atomic operations get and remove
        if (zombieEntries.containsKey(cacheKey)) {
            LOG.info("get filesystem from zombie entries key: {}", cacheKey);
            FileSystemWrapper zombie = zombieEntries.remove(cacheKey);
            if (zombie != null && !zombie.isClosed()) {
                fs = zombie.acquire();
            }
        }

        // 2. zombie fs is null, retry to get fs from system
        if (fs == null) {
            fs = HdfsEnvironment.getFileSystem(conf, uri);
        }

        // 3. Immediate validation for new connections
        if (!isFileSystemValid(fs, cacheKey)) {
            LOG.info("check filesystem invalid by cache key: {}", cacheKey);
            closeQuietly(fs);
            throw new IOException("Failed to establish valid file system connection to " + cacheKey);
        }
        return fs;
    }

    public void closeQuietly(FileSystem fs) {
        try {
            if (fs != null) {
                LOG.info("close quietly filesystem: {}", fs);
                fs.close();
            }
        } catch (IOException e) {
            LOG.warn("Close FileSystem failed: {}", e.getMessage(), e);
        }
    }

    public void clearCache() {
        LOG.info("Clearing filesystem cache");
        cache.invalidateAll();
    }

    public int size() {
        return (int) cache.estimatedSize();
    }

    public boolean isFileSystemValid(FileSystem fs, String checkPath) {
        if (fs == null) {
            return false;
        }
        try {
            if (fs instanceof HarFileSystem) {
                Path harPath = new Path(checkPath);
                return fs.exists(harPath) || fs.listStatus(harPath).length > 0;
            }
            fs.getDefaultBlockSize(new Path(checkPath));
            return true;
        } catch (IOException e) {
            return handleValidationException(e);
        } catch (Exception e) {
            LOG.warn("Unexpected error during filesystem validation", e);
            return false;
        }
    }

    private boolean handleValidationException(IOException e) {
        if (e.getMessage().contains("Filesystem closed")
                || e.getMessage().contains("Connection refused")
                || e instanceof RemoteException) {
            LOG.warn("FileSystem connection invalid: {}", e.getMessage());
            return false;
        }
        if (e.getClass().getSimpleName().contains("InvalidHar")) {
            return false;
        }
        return true;
    }


    public static class FileSystemWrapper implements Closeable {
        private final FileSystem fs;
        // The reference counter of filesystem
        private final AtomicInteger refCount = new AtomicInteger(0);

        // Added a close tag
        private volatile boolean closed = false;

        public FileSystemWrapper(FileSystem fs) {
            this.fs = Objects.requireNonNull(fs, "FileSystem cannot be null");
        }

        /**
         * Obtain the file system instance
         * @return Available file system instances
         * @throws IOException If the file system is closed or unavailable
         * @throws IllegalStateException If the wrapper has been closed
         */
        public synchronized FileSystem acquire() throws IOException {
            if (isClosed()) {
                throw new IOException("Filesystem closed");
            }
            refCount.incrementAndGet();
            LOG.info("Try to get filesystem, refCount = {}", refCount.get());
            return fs;
        }

        /**
         * Release reference (only decrement count, do not actively close)
         */
        public synchronized void release() {
            LOG.info("Try to release filesystem, refCount = {}", refCount.get());
            int remaining = refCount.decrementAndGet();
            if (remaining < 0) {
                LOG.warn("Over-released FileSystem!");
            }
        }

        /**
         * Mark delayed closing (called by RemovalListener)
         */
        @Override
        public void close() {
            // Only mark, do not close immediately
            tryClose();
        }

        /**
         * Try to actually close (when the reference returns to zero)
         */
        public synchronized boolean tryClose() {
            if (refCount.get() <= 0) {
                closed = true; // 标记为已关闭
                doClose();
                return true;
            }
            LOG.info("Try to close filesystem failed, refCount = {}", refCount.get());
            return false;
        }

        /**
         * Actual closing operation
         */
        private void doClose() {
            try {
                if (fs != null) {
                    LOG.info("close filesystem success, refCount = {}", refCount.get());
                    fs.close();
                }
            } catch (IOException e) {
                LOG.warn("Final close failed", e);
            }
        }

        public boolean isClosed() {
            return closed || refCount.get() < 0;
        }


    }
}
