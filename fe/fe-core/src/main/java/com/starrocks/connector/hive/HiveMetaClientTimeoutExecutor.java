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

import com.starrocks.connector.exception.StarRocksConnectorException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class HiveMetaClientTimeoutExecutor {
    private static final Logger LOG = LogManager.getLogger(HiveMetaClientTimeoutExecutor.class);

    // HMS apis are usually IO-intensive and can be set with more threads
    private static final ThreadPoolExecutor SHARED_EXECUTOR;

    static {
        // Adjust according to the actual business load
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        // at least 16
        int corePoolSize = Math.max(availableProcessors * 4, 16);
        // at least 64
        int maxPoolSize = Math.max(availableProcessors * 16, 64);
        SHARED_EXECUTOR = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(5000),
                new NamedThreadFactory(),
                // Use CallerRunsPolicy as the safety net
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // Allow core thread timeout recycling
        SHARED_EXECUTOR.allowCoreThreadTimeOut(true);
    }

    static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "hms-timeout-executor-" + counter.incrementAndGet());
            // Set as a non-daemon thread
            thread.setDaemon(false);
            return thread;
        }
    }


    public static <T> T executeWithTimeout(Callable<T> task, long timeout, TimeUnit unit, String taskName) {
        Future<T> future = SHARED_EXECUTOR.submit(task);

        try {
            // Monitor thread pool metrics
            monitorPoolStatus();
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            // Record the information of timeout tasks
            LOG.warn("HMS API [{}] timeout after {} {}", taskName, timeout, unit);

            // Try to cancel, but be aware that it may not be truly interrupted
            boolean cancelled = future.cancel(true);

            if (!cancelled) {
                // For tasks that cannot be cancelled, record alerts
                LOG.warn("Failed to cancel HMS API task: {}", taskName);
            }
            throw new StarRocksConnectorException("Operation hms api [" + taskName + "] timeout: ", e);
        } catch (InterruptedException e) {
            // Respond to the interruption of the caller thread
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new StarRocksConnectorException(
                    "Interrupted while waiting for HMS API [" + taskName + "]", e);
        } catch (ExecutionException e) {
            throw new StarRocksConnectorException(
                    "HMS API failed [" + taskName + "]", e.getCause());
        } catch (Exception e) {
            throw new StarRocksConnectorException(
                    "HMS API error [" + taskName + "]", e);
        }
    }


    private static void monitorPoolStatus() {
        int queueSize = SHARED_EXECUTOR.getQueue().size();
        int activeThreads = SHARED_EXECUTOR.getActiveCount();
        int maxPoolSize = SHARED_EXECUTOR.getMaximumPoolSize();

        // Queue usage rate > 80%
        if (queueSize > 4000) {
            LOG.warn("Thread pool [hms-timeout-executor] queue is high: {}/{}", queueSize, 5000);
        }
        // Thread usage rate > 90%
        double usage = (double) activeThreads / maxPoolSize;
        if (usage >= 0.9) {
            LOG.warn("Thread pool [hms-timeout-executor] threads are almost full: {}/{}", activeThreads, maxPoolSize);
        }
    }
}
