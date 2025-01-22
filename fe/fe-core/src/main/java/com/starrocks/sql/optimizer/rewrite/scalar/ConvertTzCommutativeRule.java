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
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;

import java.util.List;


public class ConvertTzCommutativeRule extends BottomUpScalarOperatorRewriteRule {
    /**
     * example:
     * convert_tz(a, '+08:00', '+09:00') > '2021-01-01 01:00:00'
     *
     * after rule:
     * a > convert_tz('2021-01-01 01:00:00', '+09:00', '+08:00')
     */
    @Override
    public ScalarOperator visitBinaryPredicate(BinaryPredicateOperator predicate,
                                               ScalarOperatorRewriteContext context) {
        if (!predicate.getChild(1).isConstant() || !OperatorType.CALL.equals(predicate.getChild(0).getOpType())) {
            return predicate;
        }

        // Only support convert_tz function
        CallOperator call = (CallOperator) predicate.getChild(0);
        if (!call.getFnName().equals(FunctionSet.CONVERT_TZ) || call.getChildren().size() != 3) {
            return predicate;
        }
        if (!call.getChild(1).isConstant() || !call.getChild(2).isConstant()) {
            return predicate;
        }

        ScalarOperator columnRef = call.getChild(0);
        ConstantOperator fromTimezone = (ConstantOperator) call.getChild(1);
        ConstantOperator toTimezone = (ConstantOperator) call.getChild(2);
        ScalarOperator constant = predicate.getChild(1);

        // reverse convert_tz implementation
        List<ScalarOperator> newArgs = Lists.newArrayList(constant, toTimezone, fromTimezone);
        CallOperator newConvertTzCall =
                new CallOperator(FunctionSet.CONVERT_TZ, call.getType(), newArgs, call.getFunction());

        return new BinaryPredicateOperator(predicate.getBinaryType(), columnRef, newConvertTzCall);
    }
}
