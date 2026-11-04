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


package com.starrocks.leader;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.server.NodeMgr;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.task.AgentTask;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TTabletInfo;
import com.starrocks.thrift.TTaskType;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MetadataTableLockTest {
    @Test
    public void testReportDeletionGroupsByTableAndRefreshesAfterYield(
            @Mocked GlobalStateMgr state, @Mocked LocalMetastore metastore,
            @Mocked NodeMgr nodeMgr, @Mocked SystemInfoService info, @Mocked Backend backend) {
        Database db = new Database(100, "db");
        TabletInvertedIndex index = new TabletInvertedIndex();
        index.addTablet(1000, new TabletMeta(100, 200, 1, 1, 1, TStorageMedium.HDD));
        index.addTablet(1001, new TabletMeta(100, 201, 1, 1, 1, TStorageMedium.HDD));
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getTabletInvertedIndex();
                result = index;
                state.getLocalMetastore();
                result = metastore;
                state.getNodeMgr();
                result = nodeMgr;
                nodeMgr.getClusterInfo();
                result = info;
                info.getBackend(1);
                result = backend;
                backend.getDisks();
                result = com.google.common.collect.ImmutableMap.of();
                metastore.getDbIncludeRecycleBin(100);
                result = db;
                metastore.getTableIncludeRecycleBin(db, anyLong);
                result = null;
                minTimes = 0;
            }
        };
        List<Long> locks = new ArrayList<>();
        List<Long> unlocks = new ArrayList<>();
        new MockUp<Locker>() {
            @Mock public void lockDatabase(Database database, LockType type) {
                Assert.fail("report deletion must not take a whole database WRITE");
            }
            @Mock public void lockTableWithIntensiveDbLock(Database database, Long tableId, LockType type) {
                Assert.assertEquals(LockType.WRITE, type);
                locks.add(tableId);
                // A truncate while yielding the first table removes its old tablet metadata.
                if (locks.size() == 2) {
                    index.deleteTablet(1000);
                }
            }
            @Mock public void unLockTablesWithIntensiveDbLock(Database database, List<Long> tableIds, LockType type) {
                unlocks.add(tableIds.get(0));
            }
        };
        ListMultimap<Long, Long> deletes = ArrayListMultimap.create();
        deletes.put(100L, 1000L);
        deletes.put(100L, 1001L);
        long saved = ReportHandler.maxTableWriteLockHoldingTimeMs;
        ReportHandler.maxTableWriteLockHoldingTimeMs = -1;
        try {
            ReportHandler.deleteFromMeta(deletes, 1, 1);
            Assert.assertEquals(java.util.Arrays.asList(200L, 200L, 201L, 201L), locks);
            Assert.assertEquals(locks, unlocks);
        } finally {
            ReportHandler.maxTableWriteLockHoldingTimeMs = saved;
        }
    }

    @Test
    public void testReportDatabaseDroppedDuringYieldDoesNotDoubleUnlock(
            @Mocked GlobalStateMgr state, @Mocked LocalMetastore metastore,
            @Mocked NodeMgr nodeMgr, @Mocked SystemInfoService info, @Mocked Backend backend) {
        Database db = new Database(100, "db");
        TabletInvertedIndex index = new TabletInvertedIndex();
        index.addTablet(1000, new TabletMeta(100, 200, 1, 1, 1, TStorageMedium.HDD));
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getTabletInvertedIndex();
                result = index;
                state.getLocalMetastore();
                result = metastore;
                state.getNodeMgr();
                result = nodeMgr;
                nodeMgr.getClusterInfo();
                result = info;
                info.getBackend(1);
                result = backend;
                backend.getDisks();
                result = com.google.common.collect.ImmutableMap.of();
                metastore.getDbIncludeRecycleBin(100);
                returns(db, null);
            }
        };
        List<Long> unlocks = new ArrayList<>();
        new MockUp<Locker>() {
            @Mock public void lockTableWithIntensiveDbLock(Database database, Long tableId, LockType type) { }
            @Mock public void unLockTablesWithIntensiveDbLock(Database database, List<Long> ids, LockType type) {
                Assert.assertNotNull(database);
                unlocks.add(ids.get(0));
            }
        };
        ListMultimap<Long, Long> deletes = ArrayListMultimap.create();
        deletes.put(100L, 1000L);
        long saved = ReportHandler.maxTableWriteLockHoldingTimeMs;
        ReportHandler.maxTableWriteLockHoldingTimeMs = -1;
        try {
            ReportHandler.deleteFromMeta(deletes, 1, 1);
            Assert.assertEquals(Collections.singletonList(200L), unlocks);
        } finally {
            ReportHandler.maxTableWriteLockHoldingTimeMs = saved;
        }
    }

    @Test
    public void testStorageMigrationUsesReplicaTableLock(@Mocked GlobalStateMgr state) {
        Database db = new Database(100, "db");
        TabletInvertedIndex index = new TabletInvertedIndex();
        index.addTablet(1000, new TabletMeta(100, 200, 1, 1, 1, TStorageMedium.HDD));
        Replica replica = new Replica(300, 1, 1, Replica.ReplicaState.NORMAL);
        index.addReplica(1000, replica);
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getTabletInvertedIndex();
                result = index;
                state.getDb(100);
                result = db;
            }
        };
        List<Long> locks = new ArrayList<>();
        List<Long> unlocks = new ArrayList<>();
        new MockUp<Locker>() {
            @Mock public void lockDatabase(Database database, LockType type) {
                Assert.fail("whole DB lock");
            }
            @Mock public void lockTableWithIntensiveDbLock(Database database, Long id, LockType type) {
                locks.add(id);
            }
            @Mock public void unLockTablesWithIntensiveDbLock(Database database, List<Long> ids, LockType type) {
                unlocks.add(ids.get(0));
            }
        };
        AgentTask task = new AgentTask(null, 1, TTaskType.STORAGE_MEDIUM_MIGRATE, 100, 200, 1, 1, 1000) {
        };
        TTabletInfo tablet = new TTabletInfo();
        tablet.setTablet_id(1000);
        tablet.setPath_hash(999);
        TFinishTaskRequest request = new TFinishTaskRequest();
        request.setTask_status(new TStatus(TStatusCode.OK));
        request.setFinish_tablet_infos(Collections.singletonList(tablet));
        Deencapsulation.invoke(new LeaderImpl(), "finishStorageMigration", task, request);
        Assert.assertEquals(999, replica.getPathHash());
        Assert.assertEquals(Collections.singletonList(200L), locks);
        Assert.assertEquals(locks, unlocks);
    }

    @Test
    public void testCooldownSkipsOnlyBusyTable(@Mocked GlobalStateMgr state,
            @Mocked com.starrocks.catalog.OlapTable table,
            @Mocked com.starrocks.catalog.Partition partition,
            @Mocked com.starrocks.catalog.PartitionInfo partitionInfo) {
        Database db = new Database(100, "db");
        new Expectations() {
            {
                table.getName();
                result = "table";
                table.getId();
                result = 200L;
                table.isOlapTableOrMaterializedView();
                result = true;
                table.getAllPartitions();
                result = Collections.singletonList(partition);
                table.getPartitionInfo();
                result = partitionInfo;
                table.getState();
                result = com.starrocks.catalog.OlapTable.OlapTableState.NORMAL;
                partition.getId();
                result = 300L;
                partitionInfo.getDataProperty(300);
                result = new com.starrocks.catalog.DataProperty(TStorageMedium.SSD, 0);
            }
        };
        db.registerTableUnlocked(table);
        LocalMetastore metastore = new LocalMetastore(state, null, null);
        new MockUp<LocalMetastore>() {
            @Mock public List<Long> getDbIds() {
                return Collections.singletonList(100L);
            }
            @Mock public Database getDb(long id) {
                return db;
            }
        };
        List<Long> tried = new ArrayList<>();
        new MockUp<Locker>() {
            @Mock public void lockDatabase(Database database, LockType type) {
                Assert.assertEquals(LockType.READ, type);
            }
            @Mock public void unLockDatabase(Database database, LockType type) { }
            @Mock public boolean tryLockDatabase(Database database, LockType type, long timeout) {
                Assert.fail("cooldown must not take a whole database WRITE");
                return false;
            }
            @Mock public boolean tryLockTableWithIntensiveDbLock(Database database, Long id, LockType type,
                    long timeout, java.util.concurrent.TimeUnit unit) {
                tried.add(id);
                return false;
            }
            @Mock public void unLockTablesWithIntensiveDbLock(Database database, List<Long> ids, LockType type) {
                Assert.fail("failed try-lock must not be unlocked");
            }
        };
        Assert.assertTrue(metastore.getPartitionIdToStorageMediumMap().isEmpty());
        Assert.assertEquals(Collections.singletonList(200L), tried);
    }
}
