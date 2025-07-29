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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/sql/optimizer/rewrite/ScalarOperatorFunctions.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.starrocks.sql.analyzer;

import com.google.common.collect.Lists;
import com.starrocks.analysis.DecimalLiteral;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionParams;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.ScalarFunction;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
/**
 * @author luoyuanluoyuan
 * @date 2025/7/30 10:36
 */

public class ReverseFunctionAnalyzerTest {
    @Test
    public void testReverseDecimal() {
        ConnectContext session = UtFrameUtils.createDefaultCtx();
        session.getSessionVariable().setEnablePrestoTranslation(true);
        List<Expr> params = Lists.newArrayList();
        params.add(new DecimalLiteral(new BigDecimal(new BigInteger("1845076"), 2)));
        List<Type> paramTypes = Lists.newArrayList();
        paramTypes.add(ScalarType.DECIMAL128);

        ScalarFunction function =
                (ScalarFunction) Expr.getBuiltinFunction(FunctionSet.REVERSE, paramTypes.toArray(new Type[0]),
                       Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        Assert.assertNotNull(function);

        FunctionCallExpr functionCallExpr = new FunctionCallExpr(FunctionSet.REVERSE, FunctionParams.createStarParam());
        ScalarFunction scalarFunc = (ScalarFunction) DecimalV3FunctionAnalyzer.getDecimalV3Function(session,
                        functionCallExpr,
                        new Type[] {ScalarType.DECIMAL128});
        Type[] types = scalarFunc.getArgs();
        Type returnType = scalarFunc.getReturnType();
        Assert.assertTrue(types[0].isVarchar());
        Assert.assertTrue(returnType.isVarchar());
    }
}
