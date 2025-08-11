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

import com.starrocks.connector.HdfsEnvironment;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileSystemCacheTest {
    private static final String TEST_HDFS_URI = "hdfs://namenode:8020";
    private static final String TEST_HAR_URI = "har://hdfs-namenode/path/to/archive.har";
    private Configuration conf;
    private FileSystemCache cache;
    private FileSystem mockFs;

    @Before
    public void setUp() throws IOException {
        conf = new Configuration();
        mockFs = mock(FileSystem.class);
        when(mockFs.exists(any(Path.class))).thenReturn(true);

        // Simulate HdfsEnvironment
        try (MockedStatic<HdfsEnvironment> mocked = mockStatic(HdfsEnvironment.class)) {
            mocked.when(() -> HdfsEnvironment.getFileSystem(any(), any()))
                    .thenReturn(mockFs);
            cache = new FileSystemCache(conf);
        }
    }

    // Common HDFS path cache
    @Test
    public void testCacheHdfsPath() throws IOException {
        // First acquisition (a new instance should be created)
        FileSystem fs1 = cache.getFileSystem(TEST_HDFS_URI, conf);
        Assert.assertSame(mockFs, fs1);
        Assert.assertEquals(1, cache.size());

        // Get again (should hit the cache)
        FileSystem fs2 = cache.getFileSystem(TEST_HDFS_URI, conf);
        Assert.assertSame(fs1, fs2);
    }

    // Special handling of HAR paths
    @Test
    public void testHarPathHandling() throws IOException, URISyntaxException {
        FileSystem fs = cache.getFileSystem(TEST_HAR_URI, conf);
        verify(mockFs).initialize(eq(new URI(TEST_HAR_URI)), eq(conf));
        Assert.assertEquals(1, cache.size());
    }



    // Cache eviction triggered
    @Test
    public void testLruEviction() throws IOException {
        // Set the maximum cache to 2
        // A parameter constructor needs to be added
        FileSystemCache smallCache = new FileSystemCache(2, conf);

        smallCache.getFileSystem("hdfs://nn1", conf);
        smallCache.getFileSystem("hdfs://nn2", conf);
        Assert.assertEquals(2, smallCache.size());

        // Trigger eviction (access nn1 to make it the most recently used)
        smallCache.getFileSystem("hdfs://nn1", conf);
        // nn2 should be phased out
        smallCache.getFileSystem("hdfs://nn3", conf);

        Assert.assertEquals(2, smallCache.size());
        Assert.assertNotNull(smallCache.getFileSystem("hdfs://nn1", conf));
        Assert.assertNotNull(smallCache.getFileSystem("hdfs://nn3", conf));
    }

    // Multithreaded concurrent access
    @Test
    public void testConcurrentAccess() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    cache.getFileSystem(TEST_HDFS_URI, conf);
                } catch (Exception e) {
                    Assert.fail(e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        // Make sure to create it only once
        Assert.assertEquals(1, cache.size());
    }


    // FileSystem creation failed
    @Test(expected = IOException.class)
    public void testFailedCreation() throws IOException {
        try (MockedStatic<HdfsEnvironment> mocked = mockStatic(HdfsEnvironment.class)) {
            mocked.when(() -> HdfsEnvironment.getFileSystem(any(), any()))
                    .thenThrow(new IOException("Mocked failure"));
            cache.getFileSystem(TEST_HDFS_URI, conf);
        }
        // Ensure that it is not cached after failure
        Assert.assertEquals(0, cache.size());
    }


    // Disable silent processing
    @Test
    public void testCloseQuietly() throws IOException {
        FileSystem brokenFs = mock(FileSystem.class);
        doThrow(new RuntimeException("Mock close error")).when(brokenFs).close();
        // Exceptions should not be thrown
        cache.closeQuietly(brokenFs);
        verify(brokenFs).close();
    }

    // Cache clearing
    @Test
    public void testClearCache() throws IOException {
        cache.getFileSystem(TEST_HDFS_URI, conf);
        Assert.assertEquals(1, cache.size());

        cache.clearCache();
        Assert.assertEquals(0, cache.size());
        verify(mockFs).close();
    }
}
