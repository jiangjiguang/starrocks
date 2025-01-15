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

package com.starrocks.sql.optimizer.rewrite.scalar;

import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PrestoCastRuleTest {


    @Test
    public void testStringTypeCastBigint() {
        // create scalar rewrite context
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();
        ConnectContext session = UtFrameUtils.createDefaultCtx();

        // open presto translate
        session.getSessionVariable().setEnablePrestoTranslation(true);
        context.setSession(session);

        ScalarOperatorRewriteRule rule = new PrestoCastRule();

        ScalarOperator operator =
                new CastOperator(Type.BIGINT, ConstantOperator.createChar("187293.624226336"));

        ScalarOperator result = rule.apply(operator, context);

        assertTrue(result.getType().isBigint());
    }
}
