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
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.metric.MetricRepo;
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

            refreshDatabaseQuota(db, dbId);
        }
    }

    private void refreshDatabaseQuota(Database db, long dbId) {
        boolean refreshDataQuota = db.getDataQuota() < FeConstants.DEFAULT_DB_DATA_QUOTA_BYTES;
        boolean refreshReplicaQuota = db.getReplicaQuota() < FeConstants.DEFAULT_DB_REPLICA_QUOTA_SIZE;
        if (!refreshDataQuota && !refreshReplicaQuota) {
            return;
        }

        long startNanos = System.nanoTime();
        boolean timedOut = false;
        try {
            QuotaUsage usage = getQuotaUsage(db, refreshDataQuota, refreshReplicaQuota);
            if (usage == null) {
                timedOut = true;
                return;
            }
            if (refreshDataQuota) {
                db.usedDataQuotaBytes.set(usage.dataBytes);
            }
            if (refreshReplicaQuota) {
                db.usedReplicaQuotaBytes.set(usage.replicaCount);
            }
        } catch (Throwable e) {
            LOG.warn("Failed to update database [{}] quota usage", dbId, e);
        } finally {
            recordRefreshMetrics(db.getFullName(), System.nanoTime() - startNanos, timedOut);
        }
    }

    private void recordRefreshMetrics(String db, long elapsedNanos, boolean timedOut) {
        if (!MetricRepo.hasInit) {
            return;
        }
        long elapsedMs = elapsedNanos / 1_000_000L;
        MetricRepo.HISTO_DATABASE_QUOTA_REFRESH_LATENCY.update(elapsedMs);
        MetricRepo.COUNTER_DATABASE_QUOTA_REFRESH.increase(1L);
        MetricRepo.GAUGE_DATABASE_QUOTA_REFRESH_LATENCY_BY_DB.getMetric(db).setValue(elapsedMs);
        MetricRepo.COUNTER_DATABASE_QUOTA_REFRESH_BY_DB.getMetric(db).increase(1L);
        if (timedOut) {
            MetricRepo.COUNTER_DATABASE_QUOTA_REFRESH_TIMEOUT.increase(1L);
            MetricRepo.COUNTER_DATABASE_QUOTA_REFRESH_TIMEOUT_BY_DB.getMetric(db).increase(1L);
        }
    }

    private QuotaUsage getQuotaUsage(Database database, boolean refreshDataQuota, boolean refreshReplicaQuota) {
        long usedDataQuota = 0;
        long usedReplicaQuota = 0;
        long configuredTimeoutMs = Config.db_used_data_quota_lock_timeout_ms;
        long timeoutMs = configuredTimeoutMs == 0 ? 0 : Math.max(1L, configuredTimeoutMs);
        long deadlineNanos = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;

        for (Table table : database.getTables()) {
            if (!table.isOlapTableOrMaterializedView()) {
                continue;
            }

            List<Long> tableIds = Lists.newArrayList(table.getId());
            Locker locker = new Locker();
            if (timeoutMs == 0) {
                locker.lockTablesWithIntensiveDbLock(database, tableIds, LockType.READ);
            } else {
                long remainingNanos = deadlineNanos - System.nanoTime();
                long remainingMs = Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
                if (!locker.tryLockTablesWithIntensiveDbLock(database, tableIds, LockType.READ, remainingMs)) {
                    LOG.warn("Skip database [{}] quota refresh because table [{}] lock timed out after {} ms",
                            database.getId(), table.getId(), timeoutMs);
                    return null;
                }
            }
            try {
                OlapTable olapTable = (OlapTable) table;
                if (refreshDataQuota) {
                    usedDataQuota += olapTable.getDataSize();
                }
                if (refreshReplicaQuota) {
                    usedReplicaQuota += olapTable.getReplicaCount();
                }
            } finally {
                locker.unLockTablesWithIntensiveDbLock(database, tableIds, LockType.READ);
            }
            if (timeoutMs > 0 && System.nanoTime() >= deadlineNanos) {
                LOG.warn("Skip database [{}] quota refresh because the refresh deadline of {} ms was exceeded",
                        database.getId(), timeoutMs);
                return null;
            }
        }
        return new QuotaUsage(usedDataQuota, usedReplicaQuota);
    }

    private static class QuotaUsage {
        private final long dataBytes;
        private final long replicaCount;

        private QuotaUsage(long dataBytes, long replicaCount) {
            this.dataBytes = dataBytes;
            this.replicaCount = replicaCount;
        }
    }
}
