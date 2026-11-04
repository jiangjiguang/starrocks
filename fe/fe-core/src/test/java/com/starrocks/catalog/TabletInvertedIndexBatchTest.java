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


package com.starrocks.catalog;

import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.lake.LakeTablet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TStorageMedium;
import mockit.Mock;
import mockit.MockUp;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TabletInvertedIndexBatchTest {
    private TabletInvertedIndex newIndex() {
        TabletInvertedIndex index = new TabletInvertedIndex();
        for (long id = 1; id <= 3; id++) {
            index.addTablet(id, new TabletMeta(100, 200, 300, 400, 1, TStorageMedium.HDD));
            index.addReplica(id, new Replica(id * 10, 7, 1, Replica.ReplicaState.NORMAL));
            index.addReplica(id, new Replica(id * 10 + 1, 8, 1, Replica.ReplicaState.NORMAL));
        }
        return index;
    }

    @Test
    public void testBatchDeleteClearsAllIndexesAndPreservesOtherTablets() {
        TabletInvertedIndex index = newIndex();
        index.deleteTablets(Arrays.asList(1L, 2L, 1L, 99L));
        Assert.assertNull(index.getTabletMeta(1));
        Assert.assertNull(index.getTabletMeta(2));
        Assert.assertNull(index.getTabletIdByReplica(10));
        Assert.assertNull(index.getTabletIdByReplica(11));
        Assert.assertNull(index.getTabletIdByReplica(20));
        Assert.assertNull(index.getReplica(1, 7));
        Assert.assertTrue(index.getReplicasByTabletId(2).isEmpty());
        Assert.assertEquals(Collections.singletonList(3L), index.getTabletIdsByBackendId(7));
        Assert.assertEquals(Collections.singletonList(3L), index.getTabletIdsByBackendId(8));
        Assert.assertNotNull(index.getReplica(3, 7));
        Assert.assertEquals(1, index.getTabletCount());
        Assert.assertEquals(2, index.getReplicaCount());
    }

    @Test
    public void testEmptyBatchAndSingleDeleteRemainCompatible() {
        TabletInvertedIndex index = newIndex();
        index.deleteTablets(Collections.emptyList());
        Assert.assertEquals(3, index.getTabletCount());
        index.deleteTablet(1);
        Assert.assertNull(index.getTabletIdByReplica(10));
        Assert.assertEquals(2, index.getTabletCount());
        index.deleteTablets(Arrays.asList(2L, 3L));
        Assert.assertEquals(0, index.getTabletCount());
        Assert.assertEquals(0, index.getReplicaCount());
        Assert.assertTrue(index.getTabletIdsByBackendId(7).isEmpty());
    }

    @Test
    public void testCheckpointSkipsBatchDeletion() {
        TabletInvertedIndex index = newIndex();
        new MockUp<GlobalStateMgr>() {
            @Mock public boolean isCheckpointThread() {
                return true;
            }
        };
        index.deleteTablets(Arrays.asList(1L, 2L));
        Assert.assertEquals(3, index.getTabletCount());
        Assert.assertNotNull(index.getReplica(1, 7));
    }

    @Test
    public void testForceDeleteSnapshotsBackendsOutsideIndexWriteLock() {
        TabletInvertedIndex index = new TabletInvertedIndex();
        ReentrantReadWriteLock lock = Deencapsulation.getField(index, "lock");
        Set<Long> backends = new java.util.HashSet<>(Arrays.asList(7L, 8L));
        LocalTablet tablet = new LocalTablet(1) {
            @Override public Set<Long> getBackendIds() {
                Assert.assertFalse("must not invert tablet/index lock order", lock.isWriteLockedByCurrentThread());
                return backends;
            }
        };
        index.markTabletsForceDelete(Arrays.asList(tablet, new LakeTablet(2), new LocalTablet(3)));
        backends.clear();
        Assert.assertTrue(index.tabletForceDelete(1, 7));
        Assert.assertTrue(index.tabletForceDelete(1, 8));
        Assert.assertFalse(index.tabletForceDelete(2, 7));
        Assert.assertFalse(index.getForceDeleteTablets().containsKey(3L));
        index.eraseTabletForceDelete(1, 7);
        Assert.assertFalse(index.tabletForceDelete(1, 7));
        Assert.assertTrue(index.tabletForceDelete(1, 8));
    }

    @Test
    public void testTruncateMarksBeforeDeletingAndReplayDoesNotMark(
            @mockit.Mocked GlobalStateMgr state, @mockit.Mocked OlapTable table,
            @mockit.Mocked Partition partition, @mockit.Mocked PhysicalPartition physicalPartition,
            @mockit.Mocked MaterializedIndex materializedIndex) {
        boolean[] replay = {true};
        TabletInvertedIndex index = new TabletInvertedIndex() {
            @Override public void deleteTablets(java.util.Collection<Long> ids) {
                Assert.assertEquals(!replay[0], tabletForceDelete(1, 7));
                super.deleteTablets(ids);
            }
        };
        Replica replica = new Replica(10, 7, 1, Replica.ReplicaState.NORMAL);
        LocalTablet tablet = new LocalTablet(1, Collections.singletonList(replica));
        new mockit.Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getTabletInvertedIndex();
                result = index;
                table.replacePartition(partition);
                result = partition;
                partition.getSubPartitions();
                result = Collections.singletonList(physicalPartition);
                physicalPartition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL);
                result = Collections.singletonList(materializedIndex);
                materializedIndex.getTablets();
                result = Collections.singletonList(tablet);
            }
        };
        com.starrocks.server.LocalMetastore metastore = new com.starrocks.server.LocalMetastore(state, null, null);
        for (boolean isReplay : new boolean[] {true, false}) {
            replay[0] = isReplay;
            index.addTablet(1, new TabletMeta(100, 200, 300, 400, 1, TStorageMedium.HDD));
            index.addReplica(1, replica);
            Deencapsulation.invoke(metastore, "truncateTableInternal", table,
                    Collections.singletonList(partition), false, isReplay);
            Assert.assertNull(index.getTabletMeta(1));
            Assert.assertNull(index.getTabletIdByReplica(10));
        }
    }
}
