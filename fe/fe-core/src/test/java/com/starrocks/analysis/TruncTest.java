// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.analysis;

import com.starrocks.common.util.UUIDUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TruncTest {
    private static ConnectContext ctx;

    @BeforeClass
    public static void setUp() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        ctx = UtFrameUtils.createDefaultCtx();
        ctx.setQueryId(UUIDUtil.genUUID());
    }

    @Test
    public void testTruncAnalyze() {
        ctx.getSessionVariable().setEnablePrestoTranslation(true);

        try {
            String sql1 =
                    "SELECT trunc('2020-11-03 23:41:37', 'year')";
            StatementBase statement1 = SqlParser.parseSingleStatement(sql1, ctx.getSessionVariable().getSqlMode());
            StmtExecutor stmtExecutor1 = new StmtExecutor(ctx, statement1);
            stmtExecutor1.execute();

            String sql2 =
                    "SELECT trunc('2020-11-03', 'year')";
            StatementBase statement2 = SqlParser.parseSingleStatement(sql2, ctx.getSessionVariable().getSqlMode());
            StmtExecutor stmtExecutor2 = new StmtExecutor(ctx, statement2);
            stmtExecutor2.execute();
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

}
