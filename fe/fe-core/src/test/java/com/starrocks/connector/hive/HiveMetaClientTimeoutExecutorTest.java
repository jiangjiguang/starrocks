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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HiveMetaClientTimeoutExecutorTest {

    @Test
    @Timeout(3)
    void testNormalExecution() throws Exception {
        // test exec normal
        String result = HiveMetaClientTimeoutExecutor.executeWithTimeout(
                () -> "test-result",
                1, TimeUnit.SECONDS,
                "testTask"
        );

        assertEquals("test-result", result);
    }

    @Test
    @Timeout(3)
    void testTimeout() {
        // test timeout
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        assertThrows(RuntimeException.class, () -> {
            HiveMetaClientTimeoutExecutor.executeWithTimeout(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    wasInterrupted.set(true);
                    throw e;
                }
                return "slow";
            }, 100, TimeUnit.MILLISECONDS, "slowTask");
        });

        // check task was interrupted
        assertTrue(wasInterrupted.get());
    }

    @Test
    @Timeout(3)
    void testExceptionPropagation() {
        // Test for abnormal propagation
        RuntimeException expectedException = new RuntimeException("expected error");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            HiveMetaClientTimeoutExecutor.executeWithTimeout(() -> {
                throw expectedException;
            }, 1, TimeUnit.SECONDS, "errorTask");
        });

        assertEquals("expected error", thrown.getCause().getMessage());
    }

    @Test
    @Timeout(5)
    void testConcurrentExecutions() throws Exception {
        // Test concurrent execution
        int numTasks = 100;
        CountDownLatch latch = new CountDownLatch(numTasks);
        ExecutorService testExecutor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < numTasks; i++) {
            final int taskId = i;
            testExecutor.submit(() -> {
                try {
                    String result = HiveMetaClientTimeoutExecutor.executeWithTimeout(
                            () -> "task-" + taskId,
                            500, TimeUnit.MILLISECONDS,
                            "concurrentTask-" + taskId
                    );
                    assertEquals("task-" + taskId, result);
                } catch (Exception e) {
                    fail("Task " + taskId + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        testExecutor.shutdown();
    }

    @Test
    @Timeout(3)
    void testInterruption() throws Exception {
        // The test caller thread was interrupted
        Thread testThread = new Thread(() -> {
            try {
                // Interrupt myself first
                Thread.currentThread().interrupt();

                HiveMetaClientTimeoutExecutor.executeWithTimeout(
                        () -> "task",
                        1, TimeUnit.SECONDS,
                        "interruptedTask"
                );

                fail("Should throw interrupted exception");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Interrupted"));
                // Check whether the interrupt status has been cleared
                assertTrue(Thread.currentThread().isInterrupted());
            }
        });

        testThread.start();
        testThread.join();
    }
}
