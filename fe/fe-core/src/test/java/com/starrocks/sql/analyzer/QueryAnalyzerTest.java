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

package com.starrocks.sql.analyzer;

import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.Test;

public class QueryAnalyzerTest {
    public static ConnectContext connectContext = UtFrameUtils.createDefaultCtx();

    @Test
    public void testCtasSubQueryNoAlias() {
        String originStmt = "select csv_format(*) from (  select * from ( select 1, 2, 3 ) )";
        // It should pass by automatically adding aliases
        try {
            connectContext.getSessionVariable().setSqlDialect("trino");
            StatementBase statementBase = com.starrocks.sql.parser.SqlParser.parse(originStmt,
                    connectContext.getSessionVariable()).get(0);
            Analyzer.analyze(statementBase, connectContext);
            Assert.assertTrue(statementBase.toSql().contains("concat_ws"));
        } catch (Exception e) {
            Assert.fail();
            throw e;
        }

        // It should be fail by automatically adding aliases
        try {
            connectContext.getSessionVariable().setSqlDialect("starrocks");
            StatementBase statementBase = com.starrocks.sql.parser.SqlParser.parse(originStmt,
                    connectContext.getSessionVariable()).get(0);
            Analyzer.analyze(statementBase, connectContext);
        } catch (Exception e) {
            Assert.fail();
            throw e;
        }
    }


}
