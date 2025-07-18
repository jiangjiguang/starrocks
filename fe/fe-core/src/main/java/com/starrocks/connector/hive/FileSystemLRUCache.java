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

import com.google.common.annotations.VisibleForTesting;
import com.starrocks.common.Config;
import com.starrocks.connector.HdfsEnvironment;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FileSystemLRUCache {
    private static final Logger LOG = LogManager.getLogger(FileSystemLRUCache.class);
    // Maximum cache quantity (adjusted as needed)
    private static final int MAX_CACHE_SIZE = Config.hdfs_client_max_cache_size;

    private static final boolean ENABLE_CACHE = Config.enable_file_system_with_cache;
    private final Map<String, FileSystem> cache;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();


    // Add a constructor with parameters (for testing)
    @VisibleForTesting
    public FileSystemLRUCache(int maxSize) {
        this.cache = new LinkedHashMap<String, FileSystem>(maxSize) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileSystem> eldest) {
                // Use the incoming maxSize
                return size() > maxSize;
            }
        };
    }


    public FileSystemLRUCache() {
        // accessOrder=true open LRU
        // The load factor of 0.75f controls the expansion of the hash table part
        this.cache = new LinkedHashMap<String, FileSystem>(
                MAX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileSystem> eldest) {
                // When the cache is full, automatically eliminate the entries that have not been used for the longest time
                if (size() > MAX_CACHE_SIZE) {
                    LOG.info("Evicting cache key: " + eldest.getKey());
                    // Disable the obsolete FileSystem
                    closeQuietly(eldest.getValue());
                    return true;
                }
                return false;
            }
        };

        // Regularly verify all cache connections (without affecting the main process)
        // startValidationTask();

        // Register the shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down FileSystemLRUCache");
            clearCache();
            scheduler.shutdown();
        }));
    }

    private void startValidationTask() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                // 1. Dynamic switch inspection
                if (!ENABLE_CACHE) {
                    // Skip but do not close the thread
                    return;
                }

                // 2. Safe cache traversal
                List<String> keysToRemove = new ArrayList<>();
                readLock.lock();
                try {
                    cache.forEach((k, fs) -> {
                        if (!isFileSystemValid(fs)) {
                            keysToRemove.add(k);

                        }
                    });
                } finally {
                    readLock.unlock();
                }

                // 3. Batch remove invalid connections
                if (!keysToRemove.isEmpty()) {
                    writeLock.lock();
                    try {
                        keysToRemove.forEach(namenode -> {
                            FileSystem fs = cache.remove(namenode);
                            closeQuietly(fs);
                        });
                    } finally {
                        writeLock.unlock();
                    }
                }
            } catch (Exception e) {
                // Avoid unexpected task exits
                LOG.error("Validation task failed", e);
            }
        }, 1, 5, TimeUnit.MINUTES);
    }


    public FileSystem getFileSystem(String path, Configuration conf) throws IOException {
        URI uri = new Path(path).toUri();
        // e.g. "hdfs://DClusterNmg5"
        String namenode = uri.getScheme() + "://" + uri.getAuthority();
        // rewrite the uri by the namenode address
        URI newCacheUri = new Path(namenode).toUri();

        // HarFileSystem need path end with .har
        if (uri.getScheme().startsWith("har")) {
            // e.g. "har://hdfs-DClusterNmg5/user/xx/yy.har"
            // File /user/dj_dw/dj_dw/har/ods_binlog_trade_increment/ods_binlog_trade_increment__201901.har/30/20
            // not found in /user/dj_dw/dj_dw/har/ods_binlog_trade_increment/ods_binlog_trade_increment__202203.har
            namenode = path.split("\\.har")[0];
            newCacheUri = new Path(path).toUri();
        }

        // Phase One: Lock-free Fast Reading (90% hit scenarios)
        readLock.lock();
        try {
            // Cache hit (automatically update LRU order)
            FileSystem fs = cache.get(namenode);
            if (fs != null) {
                return fs;
            }
        } finally {
            readLock.unlock();
        }

        // Phase Two: Write lock protection creation
        writeLock.lock();
        try {
            // Double-checking (to prevent other threads from being created)
            FileSystem fs = cache.get(namenode);
            if (fs != null) {
                return fs;
            }

            // Cache miss
            try {
                // Create a new instance
                fs = HdfsEnvironment.getFileSystem(conf, newCacheUri);
                // If the cache is full, removeEldestEntry will be triggered
                cache.put(namenode, fs);
                return fs;
            } catch (Exception e) {
                // Make sure to remove the residual state when the creation fails
                cache.remove(namenode);
                throw new IOException(e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Safely disable FileSystem
     */
    public void closeQuietly(FileSystem fs) {
        try {
            if (fs != null) {
                fs.close();
            }
        } catch (IOException e) {
            LOG.warn("Close FileSystem failed: " + e.getMessage(), e);
        }
    }

    /**
     * Clear cache (for elegant closure)
     */
    public void clearCache() {
        LOG.info("clear filesystem cache when jvm exit");
        writeLock.lock();
        try {
            cache.values().forEach(this::closeQuietly);
            cache.clear();
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return cache.size();
        } finally {
            readLock.unlock();
        }
    }



    /**
     * Verify whether the FileSystem connection is valid
     */
    private boolean isFileSystemValid(FileSystem fs) {
        if (fs == null) {
            return false;
        }
        try {
            // Use getStatus instead of exists for better performance
            fs.getFileStatus(new Path("/"));
            return true;
        } catch (IOException e) {
            LOG.debug("FileSystem validation failed: {}", e.getMessage());
            return false;
        }
    }

}
