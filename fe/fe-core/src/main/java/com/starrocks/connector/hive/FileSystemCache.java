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
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.HarFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FileSystemCache {
    private static final Logger LOG = LogManager.getLogger(FileSystemCache.class);

    // Configuration parameters
    private static final int MAX_CACHE_SIZE = Config.hdfs_client_max_cache_size;
    private static final long EXPIRE_AFTER_ACCESS_MINUTES = Config.hdfs_client_expire_after_access_minutes;
    private static final long REFRESH_AFTER_WRITE_MINUTES = Config.hdfs_client_refresh_after_write_minutes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Configuration conf;
    private Cache<String, FileSystem> cache;

    public FileSystemCache() {
        this.cache = buildCache(MAX_CACHE_SIZE);
    }

    @VisibleForTesting
    public FileSystemCache(int maxSize, Configuration configuration) {
        this.cache = buildCache(maxSize);
        this.conf = configuration;
    }

    public FileSystemCache(Configuration configuration) {
        this.conf = configuration;
        this.cache = buildCache(MAX_CACHE_SIZE);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down FileSystemCache");
            clearCache();
            scheduler.shutdown();
        }));
    }

    private Cache<String, FileSystem> buildCache(int maxSize) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
                .refreshAfterWrite(REFRESH_AFTER_WRITE_MINUTES, TimeUnit.MINUTES)
                .removalListener((String key, FileSystem fs, RemovalCause cause) -> {
                    if (fs != null) {
                        LOG.info("Evicting cache key: {} due to {}", key, cause);
                        closeQuietly(fs);
                    }
                })
                .executor(scheduler)
                .build(key -> {
                    LOG.info("The FileSystem is being refreshed: {}", key);
                    try {
                        return loadFileSystem(key);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public FileSystem getFileSystem(String path, Configuration configuration) throws IOException {
        // rewrite the config
        this.conf = configuration;
        // initial the cache key
        String cacheKey = getCacheKey(path);
        try {
            return cache.get(cacheKey, k -> {
                try {
                    return loadFileSystem(k);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
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
        FileSystem fs = HdfsEnvironment.getFileSystem(conf, uri);

        // Immediate validation for new connections
        if (!isFileSystemValid(fs, cacheKey)) {
            closeQuietly(fs);
            throw new IOException("Failed to establish valid file system connection to " + cacheKey);
        }
        return fs;
    }

    public void closeQuietly(FileSystem fs) {
        try {
            if (fs != null) {
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
}
