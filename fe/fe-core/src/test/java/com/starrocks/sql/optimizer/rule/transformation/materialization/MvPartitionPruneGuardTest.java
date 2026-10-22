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

package com.starrocks.sql.optimizer.rule.transformation.materialization;

import com.starrocks.common.QueryScanNodeDetail;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MvPartitionPruneGuardTest extends MvRewriteTestBase {
    @Test
    public void testListPartitionConstraintSurvivesMvRewrite() {
        starRocksAssert.withTable("CREATE TABLE t_partition_guard_mv (id BIGINT, dt DATE NOT NULL) "
                + "DUPLICATE KEY(id) PARTITION BY LIST(dt) ("
                + "PARTITION p9 VALUES IN ('2026-10-09'), PARTITION p10 VALUES IN ('2026-10-10')) "
                + "DISTRIBUTED BY RANDOM PROPERTIES ('replication_num'='1')", () -> {
                executeInsertSql(connectContext,
                        "INSERT INTO t_partition_guard_mv PARTITION(p10) VALUES (1, '2026-10-10')");
                withRefreshedMV("CREATE MATERIALIZED VIEW mv_partition_guard "
                        + "PARTITION BY dt DISTRIBUTED BY RANDOM REFRESH DEFERRED MANUAL "
                        + "AS SELECT dt, SUM(id) AS total FROM t_partition_guard_mv GROUP BY dt",
                        this::assertMvRewriteGuard);
            });
    }

    private void assertMvRewriteGuard() throws Exception {
        String sql = "SELECT dt, SUM(id) FROM t_partition_guard_mv "
                + "WHERE dt = '2026-10-10' GROUP BY dt";
        ExecPlan plan = UtFrameUtils.getPlanAndFragment(connectContext, sql).second;
        assertEquals(1, plan.getScanNodes().size());
        assertEquals("mv_partition_guard", ((OlapScanNode) plan.getScanNodes().get(0)).getOlapTable().getName());
        boolean oldGuard = connectContext.getSessionVariable().isEnablePartitionPruneFailLimit();
        try {
            connectContext.getSessionVariable().setEnablePartitionPruneFailLimit(true);
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, plan.getScanNodes());
            assertEquals(Boolean.FALSE, connectContext.getQueryScanNodeDetailList().get(0).getPartitionPruneFail());
            assertEquals(Integer.valueOf(1), connectContext.getQueryScanNodeDetailList().get(0).getScanPartitions());
        } finally {
            connectContext.getSessionVariable().setEnablePartitionPruneFailLimit(oldGuard);
        }
    }
}
