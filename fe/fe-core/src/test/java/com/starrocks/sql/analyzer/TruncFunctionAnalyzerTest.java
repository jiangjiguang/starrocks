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

import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
public class TruncFunctionAnalyzerTest {

    @Test
    public void testTruncDateForPresto() {
        // create scalar rewrite context
        ConnectContext session = UtFrameUtils.createDefaultCtx();
        // open presto translate
        session.getSessionVariable().setEnablePrestoTranslation(true);

        SelectAnalyzer selectAnalyzer = new SelectAnalyzer(session);

        List<Expr> params = new ArrayList<>();
        params.add(new StringLiteral("20250101"));
        params.add(new StringLiteral("MM"));

        FunctionName functionName = new FunctionName(FunctionSet.TRUNC);
        FunctionCallExpr node = new FunctionCallExpr(functionName, params);

        List<Type> typeList = new ArrayList<>();
        typeList.add(Type.STRING);
        typeList.add(Type.STRING);

        Function function = new Function(1L, functionName, typeList, Type.DATE,  false);
        node.setFn(function);
        Assert.assertEquals(node.toSql(), "trunc('20250101', 'MM')");

        selectAnalyzer.rewriteTruncForPresto(node);
        Assert.assertEquals(node.toSql(), "trunc(to_date('20250101'), 'MM')");
    }


    @Test
    public void testTruncDateTimeForPresto() {
        // create scalar rewrite context
        ConnectContext session = UtFrameUtils.createDefaultCtx();
        // open presto translate
        session.getSessionVariable().setEnablePrestoTranslation(true);

        SelectAnalyzer selectAnalyzer = new SelectAnalyzer(session);

        List<Expr> params = new ArrayList<>();
        params.add(new StringLiteral("2025-01-01 10:10:10"));
        params.add(new StringLiteral("hour"));

        FunctionName functionName = new FunctionName(FunctionSet.TRUNC);
        FunctionCallExpr node = new FunctionCallExpr(functionName, params);

        List<Type> typeList = new ArrayList<>();
        typeList.add(Type.STRING);
        typeList.add(Type.STRING);

        Function function = new Function(1L, functionName, typeList, Type.DATETIME,  false);
        node.setFn(function);
        Assert.assertEquals(node.toSql(), "trunc('2025-01-01 10:10:10', 'hour')");

        selectAnalyzer.rewriteTruncForPresto(node);
        Assert.assertEquals(node.toSql(), "trunc(timestamp('2025-01-01 10:10:10'), 'hour')");
    }
}

