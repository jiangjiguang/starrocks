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

import com.google.common.collect.Lists;
import com.starrocks.analysis.BinaryType;
import com.starrocks.catalog.Type;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorFunctions;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;
import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;

/**
 * @author chenjianyang
 * @date 2025/2/10 15:23
 */
public class ConvertTzCommutativeRuleTest {

    @Test
    public void applyConvertTzRule() {
        // create scalar rewrite context
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        ScalarOperatorRewriteRule rule = new ConvertTzCommutativeRule();

        CallOperator convertTzOperator = new CallOperator("convert_tz", Type.DATETIME,
                Lists.newArrayList(new ColumnRefOperator(1, Type.DATETIME, "dt", false),
                        ConstantOperator.createVarchar("+08:00"),
                        ConstantOperator.createVarchar("+07:00")));

        BinaryPredicateOperator
                bpo1 = new BinaryPredicateOperator(BinaryType.EQ, convertTzOperator,
                ConstantOperator.createDatetime(LocalDateTime.of(2021, 1, 1, 0, 0, 0)));

        ScalarOperator result = rule.apply(bpo1, context);
        if (result.getChild(1) instanceof CallOperator) {
            CallOperator call = (CallOperator) result.getChild(1);
            assertEquals(call.getFnName(), "convert_tz");
        } else {
            throw new RuntimeException("result is not CallOperator");
        }
    }

    @Test
    public void constantFold() {
        ConstantOperator olddt = ConstantOperator.createDatetime(LocalDateTime.of(2019, 8, 1, 13, 21, 3));
        assertEquals("2019-07-31T22:21:03",
                ScalarOperatorFunctions.convert_tz(olddt,
                        ConstantOperator.createVarchar("Asia/Shanghai"),
                        ConstantOperator.createVarchar("America/Los_Angeles")).getDatetime().toString());

        ConstantOperator oldd = ConstantOperator.createDate(LocalDateTime.of(2019, 8, 1, 0, 0, 0));
        assertEquals("2019-07-31T09:00",
                ScalarOperatorFunctions.convert_tz(oldd,
                        ConstantOperator.createVarchar("Asia/Shanghai"),
                        ConstantOperator.createVarchar("America/Los_Angeles")).getDatetime().toString());
    }
}
