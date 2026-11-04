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

package com.starrocks.common.lock;

import com.starrocks.common.util.concurrent.lock.LockInfo;
import com.starrocks.common.util.concurrent.lock.LockManager;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.server.GlobalStateMgr;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.Future;

import static com.starrocks.common.lock.LockTestUtils.assertLockSuccess;
import static com.starrocks.common.lock.LockTestUtils.assertLockWait;

public class ReleaseTest {
    @Before
    public void setUp() {
        GlobalStateMgr.getCurrentState().setLockManager(new LockManager());
    }

    @Test
    public void testReleaseInvoke() throws Exception {
        long rid = 1L;

        TestLocker testLocker1 = new TestLocker();
        assertLockSuccess(testLocker1.lock(rid, LockType.WRITE));

        TestLocker testLocker2 = new TestLocker();
        Future<LockResult> f2 = testLocker2.lock(rid, LockType.READ);
        assertLockWait(f2);

        TestLocker testLocker3 = new TestLocker();
        Future<LockResult> f3 = testLocker3.lock(rid, LockType.READ);
        assertLockWait(f3);

        TestLocker testLocker4 = new TestLocker();
        Future<LockResult> f4 = testLocker4.lock(rid, LockType.READ);
        assertLockWait(f4);

        assertLockSuccess(testLocker1.release(rid, LockType.WRITE));
        LockTestUtils.assertLockSuccess(f2);
        LockTestUtils.assertLockSuccess(f3);
        LockTestUtils.assertLockSuccess(f4);

        LockManager lockManager = GlobalStateMgr.getCurrentState().getLockManager();
        LockInfo lockInfo = lockManager.dumpLockManager().get(0);
        Assert.assertEquals(1, lockInfo.getRid().longValue());
        Assert.assertEquals(3, lockInfo.getOwners().size());
        Assert.assertEquals(0, lockInfo.getWaiters().size());
    }

    @Test
    public void testReleaseCompatibleIntentionLocks() throws Exception {
        long rid = 1L;
        TestLocker owner = new TestLocker();
        assertLockSuccess(owner.lock(rid, LockType.WRITE));

        TestLocker first = new TestLocker();
        Future<LockResult> firstResult = first.lock(rid, LockType.INTENTION_SHARED);
        assertLockWait(firstResult);
        TestLocker second = new TestLocker();
        Future<LockResult> secondResult = second.lock(rid, LockType.INTENTION_EXCLUSIVE);
        assertLockWait(secondResult);
        TestLocker third = new TestLocker();
        Future<LockResult> thirdResult = third.lock(rid, LockType.INTENTION_SHARED);
        assertLockWait(thirdResult);

        assertLockSuccess(owner.release(rid, LockType.WRITE));
        assertLockSuccess(firstResult);
        assertLockSuccess(secondResult);
        assertLockSuccess(thirdResult);

        LockInfo lockInfo = GlobalStateMgr.getCurrentState().getLockManager().dumpLockManager().get(0);
        Assert.assertEquals(3, lockInfo.getOwners().size());
        Assert.assertEquals(0, lockInfo.getWaiters().size());

        assertLockSuccess(first.release(rid, LockType.INTENTION_SHARED));
        assertLockSuccess(second.release(rid, LockType.INTENTION_EXCLUSIVE));
        assertLockSuccess(third.release(rid, LockType.INTENTION_SHARED));
        Assert.assertTrue(GlobalStateMgr.getCurrentState().getLockManager().dumpLockManager().isEmpty());
    }

    @Test
    public void testReleaseCompatibleWaitersWithExistingOwner() throws Exception {
        long rid = 1L;
        TestLocker sharedOwner = new TestLocker();
        assertLockSuccess(sharedOwner.lock(rid, LockType.INTENTION_SHARED));
        TestLocker exclusiveIntentOwner = new TestLocker();
        assertLockSuccess(exclusiveIntentOwner.lock(rid, LockType.INTENTION_EXCLUSIVE));

        TestLocker reader = new TestLocker();
        Future<LockResult> readResult = reader.lock(rid, LockType.READ);
        assertLockWait(readResult);
        TestLocker sharedIntent = new TestLocker();
        Future<LockResult> sharedIntentResult = sharedIntent.lock(rid, LockType.INTENTION_SHARED);
        assertLockWait(sharedIntentResult);

        // READ can join the existing IS owner once the conflicting IX owner releases.
        assertLockSuccess(exclusiveIntentOwner.release(rid, LockType.INTENTION_EXCLUSIVE));
        assertLockSuccess(readResult);
        assertLockSuccess(sharedIntentResult);

        LockInfo lockInfo = GlobalStateMgr.getCurrentState().getLockManager().dumpLockManager().get(0);
        Assert.assertEquals(3, lockInfo.getOwners().size());
        Assert.assertEquals(0, lockInfo.getWaiters().size());

        assertLockSuccess(reader.release(rid, LockType.READ));
        assertLockSuccess(sharedIntent.release(rid, LockType.INTENTION_SHARED));
        assertLockSuccess(sharedOwner.release(rid, LockType.INTENTION_SHARED));
        Assert.assertTrue(GlobalStateMgr.getCurrentState().getLockManager().dumpLockManager().isEmpty());
    }

    @Test
    public void testReleaseDoesNotBypassQueuedWriter() throws Exception {
        long rid = 1L;
        TestLocker owner = new TestLocker();
        assertLockSuccess(owner.lock(rid, LockType.WRITE));

        TestLocker first = new TestLocker();
        Future<LockResult> firstResult = first.lock(rid, LockType.INTENTION_SHARED);
        assertLockWait(firstResult);
        TestLocker writer = new TestLocker();
        Future<LockResult> writerResult = writer.lock(rid, LockType.WRITE);
        assertLockWait(writerResult);
        TestLocker last = new TestLocker();
        Future<LockResult> lastResult = last.lock(rid, LockType.INTENTION_EXCLUSIVE);
        assertLockWait(lastResult);

        assertLockSuccess(owner.release(rid, LockType.WRITE));
        assertLockSuccess(firstResult);
        assertLockWait(writerResult);
        assertLockWait(lastResult);

        assertLockSuccess(first.release(rid, LockType.INTENTION_SHARED));
        assertLockSuccess(writerResult);
        assertLockWait(lastResult);
        assertLockSuccess(writer.release(rid, LockType.WRITE));
        assertLockSuccess(lastResult);
        assertLockSuccess(last.release(rid, LockType.INTENTION_EXCLUSIVE));
        Assert.assertTrue(GlobalStateMgr.getCurrentState().getLockManager().dumpLockManager().isEmpty());
    }
}
