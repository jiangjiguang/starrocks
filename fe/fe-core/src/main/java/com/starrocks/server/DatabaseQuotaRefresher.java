// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.server;

import com.google.common.collect.Lists;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.util.FrontendDaemon;
import com.starrocks.common.util.concurrent.lock.AutoCloseableLock;
import com.starrocks.common.util.concurrent.lock.LockType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Refreshes database quota usage outside the DDL admission path.
 *
 * The old implementation recalculated quota usage synchronously from
 * Database.checkQuota(), while holding a database READ lock.  This daemon
 * keeps the same quota semantics while moving the expensive traversal to a
 * periodic background task.
 */
public class DatabaseQuotaRefresher extends FrontendDaemon {
    private static final Logger LOG = LogManager.getLogger(DatabaseQuotaRefresher.class);

    public DatabaseQuotaRefresher() {
        super("DatabaseQuotaRefresher", Config.db_used_data_quota_update_interval_secs * 1000L);
    }

    @Override
    protected void runAfterCatalogReady() {
        updateAllDatabaseQuota();
    }

    private void updateAllDatabaseQuota() {
        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        List<Long> dbIdList = globalStateMgr.getLocalMetastore().getDbIds();
        for (Long dbId : dbIdList) {
            Database db = globalStateMgr.getDb(dbId);
            if (db == null || db.isSystemDatabase()) {
                continue;
            }

            try {
                if (db.getDataQuota() < FeConstants.DEFAULT_DB_DATA_QUOTA_BYTES) {
                    db.usedDataQuotaBytes.set(getUsedDataQuota(db));
                }
                if (db.getReplicaQuota() < FeConstants.DEFAULT_DB_REPLICA_QUOTA_SIZE) {
                    db.usedReplicaQuotaBytes.set(getUsedReplicaQuota(db));
                }
            } catch (Throwable e) {
                LOG.warn("Failed to update database [{}] quota usage", dbId, e);
            }
        }
    }

    private long getUsedDataQuota(Database database) {
        long usedDataQuota = 0;
        for (Table table : database.getTables()) {
            if (!table.isOlapTableOrMaterializedView()) {
                continue;
            }
            OlapTable olapTable = (OlapTable) table;
            try (AutoCloseableLock ignored = new AutoCloseableLock(
                    database, Lists.newArrayList(olapTable.getId()), LockType.READ)) {
                usedDataQuota += olapTable.getDataSize();
            }
        }
        return usedDataQuota;
    }

    private long getUsedReplicaQuota(Database database) {
        long usedReplicaQuota = 0;
        for (Table table : database.getTables()) {
            if (!table.isOlapTableOrMaterializedView()) {
                continue;
            }
            OlapTable olapTable = (OlapTable) table;
            try (AutoCloseableLock ignored = new AutoCloseableLock(
                    database, Lists.newArrayList(olapTable.getId()), LockType.READ)) {
                usedReplicaQuota += olapTable.getReplicaCount();
            }
        }
        return usedReplicaQuota;
    }
}
