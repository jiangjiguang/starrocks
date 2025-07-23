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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/http/rest/HealthAction.java

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

package com.starrocks.http.rest;

import com.google.common.base.Strings;
import com.starrocks.common.DdlException;
import com.starrocks.common.StarRocksHttpException;
import com.starrocks.common.profile.Timer;
import com.starrocks.common.profile.Tracers;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.HttpConnectContext;
import com.starrocks.http.IllegalArgException;
import com.starrocks.privilege.AccessDeniedException;
import com.starrocks.qe.ConnectProcessor;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.SqlParser;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class GetSqlDigestAction extends RestBaseAction {

    private static final Logger LOG = LogManager.getLogger(GetSqlDigestAction.class);
    public GetSqlDigestAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller)
            throws IllegalArgException {
        controller.registerHandler(HttpMethod.POST, "/api/_get_sql_digest",
                new GetSqlDigestAction(controller));
    }

    @Override
    public void executeWithoutPassword(BaseRequest request, BaseResponse response) throws DdlException, AccessDeniedException {
        response.setContentType("application/json");
        String originStmt = request.getContent();
        // check sql if valid
        if (Strings.isNullOrEmpty(originStmt)) {
            throw new StarRocksHttpException(HttpResponseStatus.BAD_REQUEST,
                    "POST body must contains [sql] root object");
        }

        HttpConnectContext ctx = request.getConnectContext();
        UserIdentity currentUser = ctx.getCurrentUserIdentity();
        ctx.setCurrentUserIdentity(currentUser);

        // parse sql
        List<StatementBase> statements;
        try (Timer ignored = Tracers.watchScope("Parser")) {
            statements = SqlParser.parse(originStmt, ctx.getSessionVariable());
        }
        StatementBase statementBase = statements.get(0);
        String digest = ConnectProcessor.computeStatementDigest(statementBase);

        RestResult result = new RestResult();
        result.addResultEntry("digest", digest);

        sendResult(request, response, result);
    }


}
