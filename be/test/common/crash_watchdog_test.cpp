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

#include "common/crash_watchdog.h"

#include <gtest/gtest.h>
#include <unistd.h>

#include <atomic>
#include <ctime>
#include <limits>

#include "common/process_exit.h"

namespace starrocks {

extern std::atomic<bool> k_starrocks_be_crashing;
extern std::atomic<bool> k_starrocks_exit;
extern std::atomic<bool> k_starrocks_quick_exit;
extern std::atomic<bool> g_crash_watchdog_started;

namespace {
constexpr int64_t kSecond = 1000000000LL;

int g_terminate_calls = 0;
void count_terminate() {
    ++g_terminate_calls;
}
} // namespace

// Drives CrashWatchdog::run_once with a stub terminate callback so the fire decision can be tested
// in-process without terminating the test binary.
class CrashWatchdogTest : public testing::Test {
protected:
    void SetUp() override { g_terminate_calls = 0; }
    void TearDown() override {
        k_starrocks_be_crashing.store(false);
        k_starrocks_exit.store(false);
        k_starrocks_quick_exit.store(false);
        g_crash_watchdog_started.store(false);
    }

    CrashWatchdog _watchdog{count_terminate};
};

TEST_F(CrashWatchdogTest, NoTerminateWhenNotCrashing) {
    _watchdog.run_once(false, 1, 0);
    _watchdog.run_once(false, 1, 1000 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
}

TEST_F(CrashWatchdogTest, TerminatesAfterDeadline) {
    int64_t t0 = 100 * kSecond;
    // First crashing observation only latches the start; the deadline has not elapsed yet.
    _watchdog.run_once(true, 60, t0);
    // Just before the deadline: still alive.
    _watchdog.run_once(true, 60, t0 + 59 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
    // At the deadline: terminate.
    _watchdog.run_once(true, 60, t0 + 60 * kSecond);
    EXPECT_EQ(1, g_terminate_calls);
}

TEST_F(CrashWatchdogTest, DisabledWhenTimeoutNonPositive) {
    int64_t t0 = 100 * kSecond;
    _watchdog.run_once(true, 0, t0);
    _watchdog.run_once(true, 0, t0 + 10000 * kSecond);
    _watchdog.run_once(true, -1, t0 + 10000 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
}

TEST_F(CrashWatchdogTest, LargeTimeoutDoesNotOverflow) {
    _watchdog.run_once(true, std::numeric_limits<int64_t>::max(), 0);
    _watchdog.run_once(true, std::numeric_limits<int64_t>::max(), 10000 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
}

TEST_F(CrashWatchdogTest, NormalShutdownIsNotACrash) {
    EXPECT_FALSE(is_process_crashing());
    set_process_exit();
    set_process_quick_exit();
    _watchdog.run_once(is_process_crashing(), 1, 0);
    _watchdog.run_once(is_process_crashing(), 1, 100 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
    EXPECT_FALSE(is_process_crashing());
}

TEST_F(CrashWatchdogTest, CrashFlagDoesNotStartGracefulShutdown) {
    set_process_is_crashing();
    set_process_is_crashing();
    EXPECT_TRUE(is_process_crashing());
    EXPECT_FALSE(process_exit_in_progress());
    EXPECT_FALSE(process_quick_exit_in_progress());
}

TEST_F(CrashWatchdogTest, DeadlineMeasuredFromFirstObservation) {
    int64_t t0 = 100 * kSecond;
    // Latch at t0.
    _watchdog.run_once(true, 30, t0);
    // A later observation still within 30s of t0 must not fire: the deadline is anchored to the
    // first observation, not the last.
    _watchdog.run_once(true, 30, t0 + 20 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
    _watchdog.run_once(true, 30, t0 + 30 * kSecond);
    EXPECT_EQ(1, g_terminate_calls);
}

TEST_F(CrashWatchdogTest, NotCrashingResetsLatch) {
    int64_t t0 = 100 * kSecond;
    _watchdog.run_once(true, 10, t0);
    // A non-crashing observation clears the latch, so the streak restarts.
    _watchdog.run_once(false, 10, t0 + 5 * kSecond);
    // The next crashing observation re-latches the streak start here (t0 + 14s), so the deadline
    // is measured from t0 + 14s, not from the original t0.
    _watchdog.run_once(true, 10, t0 + 14 * kSecond);
    // 9s into the new streak: still below the 10s deadline.
    _watchdog.run_once(true, 10, t0 + 23 * kSecond);
    EXPECT_EQ(0, g_terminate_calls);
    // 10s into the new streak: terminate.
    _watchdog.run_once(true, 10, t0 + 24 * kSecond);
    EXPECT_EQ(1, g_terminate_calls);
}

TEST_F(CrashWatchdogTest, StartIsNoOpWhenDisabled) {
    // A non-positive timeout (the production default) must return without launching the watchdog
    // thread, and a second call must be a no-op via the started flag.
    may_start_crash_watchdog(0);
    EXPECT_TRUE(g_crash_watchdog_started.load());
    may_start_crash_watchdog(0);
    EXPECT_TRUE(g_crash_watchdog_started.load());
}

// Thread-Sanitizer cannot fork a multi-threaded process for death tests; skip the end-to-end kill.
#ifndef THREAD_SANITIZER
// Keep process-lifetime polling threads in child processes so they cannot affect other tests.
TEST(CrashWatchdogDeathTest, DisabledWatchdogDoesNotExitOnCrash) {
    EXPECT_EXIT(
            {
                may_start_crash_watchdog(0);
                set_process_is_crashing();
                struct timespec wait = {};
                wait.tv_sec = 3;
                nanosleep(&wait, nullptr);
                _exit(0);
            },
            ::testing::ExitedWithCode(0), "");
}

TEST(CrashWatchdogDeathTest, NoExitWithoutCrash) {
    EXPECT_EXIT(
            {
                may_start_crash_watchdog(1);
                struct timespec wait = {};
                wait.tv_sec = 3;
                nanosleep(&wait, nullptr);
                _exit(is_process_crashing() || !g_crash_watchdog_started.load() ? 2 : 0);
            },
            ::testing::ExitedWithCode(0), "");
}

// End-to-end: a wedged crash handler (crash flag set, process still alive) must be force-exited by
// the real watchdog thread. The child starts the watchdog with a 1s deadline, sets the flag, then
// hangs; the watchdog must terminate it via _exit(1).
TEST(CrashWatchdogDeathTest, ForcesExitWhenHandlerHangs) {
    EXPECT_EXIT(
            {
                may_start_crash_watchdog(1);
                set_process_is_crashing();
                while (true) {
                    pause();
                }
            },
            ::testing::ExitedWithCode(1), "crash_watchdog");
}
#endif

} // namespace starrocks
