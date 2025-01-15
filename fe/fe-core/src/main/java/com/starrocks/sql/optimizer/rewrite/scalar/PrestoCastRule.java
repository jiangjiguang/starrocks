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
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;


//
// compatible with presto syntax cast functions
//

public class PrestoCastRule extends TopDownScalarOperatorRewriteRule {

    @Override
    public ScalarOperator visitCastOperator(CastOperator operator, ScalarOperatorRewriteContext context) {
        // cast(xx as bigint)
        // xx is string in hive
        // we need to cast xx to decimal at first
        // then get decimal of xx to bigint
        if (context == null) {
            return operator;
        }
        if (context.getSession() == null) {
            return operator;
        }
        boolean enablePrestoTranslation = context.getSession().getSessionVariable().isEnablePrestoTranslation();
        if (!enablePrestoTranslation) {
            return operator;
        }
        ScalarOperator childOperator = operator.getChild(0);
        if (childOperator.getType().isVarchar()) {
            if (operator.getType().isIntegerType()) {
                ScalarOperator newChildOperator = new CastOperator(Type.DECIMALV2, childOperator, true);
                operator.getChildren().set(0, newChildOperator);
            }
        }
        return operator;
    }


}
