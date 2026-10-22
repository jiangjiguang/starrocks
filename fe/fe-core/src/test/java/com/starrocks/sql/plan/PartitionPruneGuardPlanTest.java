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

package com.starrocks.sql.plan;

import com.google.common.collect.ImmutableMap;
import com.starrocks.analysis.BinaryType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Type;
import com.starrocks.common.FeConstants;
import com.starrocks.common.QueryScanNodeDetail;
import com.starrocks.common.UserException;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.rewrite.OptOlapPartitionPruner;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MVPartitionPruner;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PartitionPruneGuardPlanTest extends PlanTestNoneDBBase {
    private boolean wasRunningUnitTest;
    private boolean wasGuardEnabled;

    @BeforeClass
    public static void createTables() throws Exception {
        starRocksAssert.withDatabase("partition_guard_test").useDatabase("partition_guard_test")
                .withTable("CREATE TABLE t (id INT, v INT, dt DATE NOT NULL) DUPLICATE KEY(id) "
                        + "PARTITION BY RANGE(dt) ("
                        + "PARTITION p9 VALUES [('2026-10-09'), ('2026-10-10')), "
                        + "PARTITION p10 VALUES [('2026-10-10'), ('2026-10-11'))) "
                        + "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
        OlapTable table = (OlapTable) starRocksAssert.getTable("partition_guard_test", "t");
        Partition partition = table.getPartition("p10");
        partition.updateVisibleVersion(2);
        partition.getBaseIndex().getTablets().forEach(tablet -> ((LocalTablet) tablet).getImmutableReplicas()
                .forEach(replica -> replica.updateVersion(2)));
        starRocksAssert.withTable("CREATE TABLE t_list (id INT, dt DATE NOT NULL) DUPLICATE KEY(id) "
                + "PARTITION BY LIST(dt) (PARTITION p9 VALUES IN ('2026-10-09'), "
                + "PARTITION p10 VALUES IN ('2026-10-10')) "
                + "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
        makePartitionVisible("t_list", "p10");
        starRocksAssert.withTable("CREATE TABLE t_expr (id INT, dt DATE NOT NULL) DUPLICATE KEY(id) "
                + "PARTITION BY date_trunc('day', dt) "
                + "(START ('2026-10-09') END ('2026-10-11') EVERY (INTERVAL 1 day)) "
                + "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
        OlapTable exprTable = (OlapTable) starRocksAssert.getTable("partition_guard_test", "t_expr");
        exprTable.getPartitions().forEach(p -> makeVisible(p));
    }

    private static void makePartitionVisible(String tableName, String partitionName) throws Exception {
        OlapTable table = (OlapTable) starRocksAssert.getTable("partition_guard_test", tableName);
        makeVisible(table.getPartition(partitionName));
    }

    private static void makeVisible(Partition partition) {
        partition.updateVisibleVersion(2);
        partition.getBaseIndex().getTablets().forEach(tablet -> ((LocalTablet) tablet).getImmutableReplicas()
                .forEach(replica -> replica.updateVersion(2)));
    }

    @Before
    public void enableGuard() {
        wasRunningUnitTest = FeConstants.runningUnitTest;
        wasGuardEnabled = connectContext.getSessionVariable().isEnablePartitionPruneFailLimit();
        FeConstants.runningUnitTest = false;
        connectContext.getSessionVariable().setEnablePartitionPruneFailLimit(true);
    }

    @After
    public void restoreSettings() {
        FeConstants.runningUnitTest = wasRunningUnitTest;
        connectContext.getSessionVariable().setEnablePartitionPruneFailLimit(wasGuardEnabled);
    }

    @Test
    public void testDateConstraintSurvivesPlanningAndColumnPruning() throws Exception {
        for (String sql : new String[] {
                "SELECT id, dt FROM t WHERE dt = '2026-10-10'",
                "SELECT id FROM t WHERE dt >= '2026-10-09'",
                "SELECT id FROM t PARTITION(p10)"}) {
            ExecPlan plan = getExecPlan(sql);
            assertEquals(1, plan.getScanNodes().size());
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, plan.getScanNodes());
            assertEquals(Boolean.FALSE, connectContext.getQueryScanNodeDetailList().get(0).getPartitionPruneFail());
            assertEquals(Integer.valueOf(1), connectContext.getQueryScanNodeDetailList().get(0).getScanPartitions());
        }
    }

    @Test
    public void testUnconstrainedQueriesRemainBlocked() throws Exception {
        for (String sql : new String[] {
                "SELECT id FROM t", "SELECT id FROM t WHERE id = 123",
                "SELECT id FROM t WHERE dt = '2026-10-10' OR id = 123"}) {
            ExecPlan plan = getExecPlan(sql);
            try {
                QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, plan.getScanNodes());
                fail("Expected guard rejection: " + sql);
            } catch (UserException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("partition clipping failed"));
            }
        }
    }

    @Test
    public void testListPartitionConstraint() throws Exception {
        assertAllowed("SELECT id FROM t_list WHERE dt = '2026-10-10'", 1);
        assertAllowed("SELECT id FROM t_list PARTITION(p10)", 1);
        assertBlocked("SELECT id FROM t_list");
        assertBlocked("SELECT id FROM t_list WHERE id = 123");
    }

    @Test
    public void testExpressionPartitionConstraint() throws Exception {
        assertAllowed("SELECT id FROM t_expr WHERE dt = '2026-10-10'", 1);
        assertBlocked("SELECT id FROM t_expr");
        assertBlocked("SELECT id FROM t_expr WHERE id = 123");
    }

    @Test
    public void testSplitOrRetainsPartitionConstraint() throws Exception {
        double oldThreshold = connectContext.getSessionVariable().getSelectRatioThreshold();
        try {
            connectContext.getSessionVariable().setSelectRatioThreshold(-1);
            ExecPlan plan = getExecPlan("SELECT id FROM t WHERE dt = '2026-10-10' AND (id = 1 OR v = 2)");
            assertTrue(plan.getScanNodes().size() > 1);
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, plan.getScanNodes());
            connectContext.getQueryScanNodeDetailList().forEach(detail ->
                    assertEquals(Boolean.FALSE, detail.getPartitionPruneFail()));
        } finally {
            connectContext.getSessionVariable().setSelectRatioThreshold(oldThreshold);
        }
    }

    @Test
    public void testRepeatedPruningAndMvReset() throws Exception {
        OlapTable table = (OlapTable) starRocksAssert.getTable("partition_guard_test", "t");
        Column dt = table.getColumn("dt");
        Column id = table.getColumn("id");
        ColumnRefOperator dtRef = new ColumnRefOperator(1, Type.DATE, "dt", false);
        ColumnRefOperator idRef = new ColumnRefOperator(2, Type.INT, "id", false);
        LogicalOlapScanOperator scan = new LogicalOlapScanOperator(table,
                ImmutableMap.of(dtRef, dt, idRef, id), ImmutableMap.of(dt, dtRef, id, idRef),
                null, -1, new BinaryPredicateOperator(BinaryType.EQ, dtRef,
                ConstantOperator.createDate(LocalDateTime.of(2026, 10, 10, 0, 0))));

        LogicalOlapScanOperator pruned = OptOlapPartitionPruner.prunePartitions(scan);
        assertTrue(pruned.hasPartitionConstraint());
        assertEquals(1, pruned.getSelectedPartitionId().size());
        LogicalOlapScanOperator prunedAgain = OptOlapPartitionPruner.prunePartitions(pruned);
        assertTrue(prunedAgain.hasPartitionConstraint());
        assertEquals(pruned.getSelectedPartitionId(), prunedAgain.getSelectedPartitionId());

        LogicalOlapScanOperator reset = MVPartitionPruner.resetSelectedPartitions(pruned);
        assertNull(reset.getSelectedPartitionId());
        assertFalse(reset.hasPartitionConstraint());
        LogicalOlapScanOperator withoutPredicate = new LogicalOlapScanOperator.Builder()
                .withOperator(reset).setPredicate(null).build();
        LogicalOlapScanOperator unpruned = OptOlapPartitionPruner.prunePartitions(withoutPredicate);
        assertFalse(unpruned.hasPartitionConstraint());
        assertEquals(1, unpruned.getSelectedPartitionId().size());

        // Re-pruning after a predicate rewrite must not trust the flag from the old predicate.
        LogicalOlapScanOperator rewritten = new LogicalOlapScanOperator.Builder()
                .withOperator(pruned).setPredicate(null).build();
        LogicalOlapScanOperator rewrittenAndPruned = OptOlapPartitionPruner.prunePartitions(rewritten);
        assertFalse(rewrittenAndPruned.hasPartitionConstraint());

        LogicalOlapScanOperator replaced = new LogicalOlapScanOperator.Builder()
                .withOperator(pruned).setPredicate(new BinaryPredicateOperator(BinaryType.EQ, idRef,
                        ConstantOperator.createInt(123))).build();
        assertFalse(replaced.hasPartitionConstraint());
        assertFalse(OptOlapPartitionPruner.prunePartitions(replaced).hasPartitionConstraint());
    }

    private void assertAllowed(String sql, int expectedPartitions) throws Exception {
        ExecPlan plan = getExecPlan(sql);
        QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, plan.getScanNodes());
        assertEquals(Boolean.FALSE, connectContext.getQueryScanNodeDetailList().get(0).getPartitionPruneFail());
        assertEquals(Integer.valueOf(expectedPartitions),
                connectContext.getQueryScanNodeDetailList().get(0).getScanPartitions());
    }

    private void assertBlocked(String sql) throws Exception {
        ExecPlan plan = getExecPlan(sql);
        try {
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, plan.getScanNodes());
            fail("Expected partition guard to reject: " + sql);
        } catch (UserException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("partition clipping failed"));
        }
    }
}
