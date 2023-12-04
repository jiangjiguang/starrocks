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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/http/rest/ProfileAction.java

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
import com.starrocks.catalog.Database;
import com.starrocks.common.DdlException;
import com.starrocks.common.StarRocksHttpException;
import com.starrocks.http.ActionController;
import com.starrocks.http.BaseRequest;
import com.starrocks.http.BaseResponse;
import com.starrocks.http.IllegalArgException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.MaterializedViewAnalyzer;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.optimizer.dump.MockDumpInfo;
import com.starrocks.sql.parser.ParsingException;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Base64;

/**
 * @author chenjianyang
 * @date 2023/10/18 20:04
 */
public class PartitionBaseTableAction extends RestBaseAction {
    private static ConnectContext ctx;
    protected static final String SQL_KEY = "mvSchema";
    private static final Logger LOG = LogManager.getLogger(PartitionBaseTableAction.class);

    public PartitionBaseTableAction(ActionController controller) {
        super(controller);
    }

    public static void registerAction(ActionController controller) throws IllegalArgException {
        // the extra `/api` path is so disgusting
        controller.registerHandler(HttpMethod.GET, "/api/show_baseTable", new PartitionBaseTableAction(controller));
    }

    @Override
    protected void executeWithoutPassword(BaseRequest request, BaseResponse response) throws DdlException {
        String dbName = request.getRequest().headers().get(DB_KEY);
        String originSql = request.getRequest().headers().get(SQL_KEY);
        try {
            if (Strings.isNullOrEmpty(dbName) || Strings.isNullOrEmpty(originSql)) {
                LOG.warn("No database or originSql selected.");
                throw new StarRocksHttpException(HttpResponseStatus.BAD_REQUEST, "No database or originSql selected.");
            }
            // check privilege for select, otherwise return 401 HTTP status
            Database db = GlobalStateMgr.getCurrentState().getDb(dbName);
            if (db == null) {
                throw new StarRocksHttpException(HttpResponseStatus.NOT_FOUND,
                        "Database [" + dbName + "] " + "does not exists");
            }
            String user = getUser(request);
            initContext(dbName, user);
            try {
                String tableNames = parseStmtWithNewParser(originSql);
                response.getContent().append(tableNames);
                LOG.info("origin sql is: " + originSql + "; tableName is:" + tableNames);
                sendResult(request, response);
            } catch (Exception e) {
                // Transform the general Exception to custom StarRocksHttpException
                LOG.warn("get baseTable failed: " + e.getMessage());
                response.getContent().append("null");
                sendResult(request, response, HttpResponseStatus.NOT_FOUND);
            }
        } catch (StarRocksHttpException e) {
            // status code  should conforms to HTTP semantic
            response.getContent().append("null");
            sendResult(request, response, HttpResponseStatus.NOT_FOUND);
        }
    }

    private static String getUser(BaseRequest request) {
        String auth = request.getAuthorizationHeader();
        try {
            auth = auth.substring("Basic ".length());
        } catch (Exception e) {
            throw new RuntimeException("parse auth fail:" + e);
        }
        Base64.Decoder decoder = Base64.getDecoder();
        String userAndPasswd;
        try {
            byte[] b = decoder.decode(auth);
            userAndPasswd = new String(b);
        } catch (Exception e) {
            throw new RuntimeException("decode auth fail:" + e);
        }
        String[] userAndPasswdSplit;
        try {
            userAndPasswdSplit = userAndPasswd.split(":");
        } catch (Exception e) {
            throw new RuntimeException("split auth fail:" + e);
        }
        return userAndPasswdSplit[0];
    }

    private static void initContext(String database, String user) {
        ctx = new ConnectContext(null);
        ctx.setCurrentUserIdentity(new UserIdentity(user, "%"));
        ctx.setQualifiedUser(user);
        ctx.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        ctx.setThreadLocalInfo();
        ctx.setDumpInfo(new MockDumpInfo());
        ctx.setDatabase(database);
    }

    public static String parseStmtWithNewParser(String originSql) {
        String res = "null";
        try {
            StatementBase statementBase =
                    com.starrocks.sql.parser.SqlParser.parse(originSql, ctx.getSessionVariable().getSqlMode())
                            .get(0);
            com.starrocks.sql.analyzer.MaterializedViewAnalyzer.analyze(statementBase, ctx);
            res = MaterializedViewAnalyzer.baseTableName.get();
            MaterializedViewAnalyzer.baseTableName.remove();
        } catch (ParsingException | SemanticException e) {
            LOG.warn("parse failed: " + e.getMessage());
        }
        return res;
    }
}
