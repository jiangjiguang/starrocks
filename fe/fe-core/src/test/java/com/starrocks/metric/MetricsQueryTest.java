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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/qe/ConnectProcessorTest.java

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
package com.starrocks.metric;

import com.google.common.collect.Sets;
import com.starrocks.authentication.AuthenticationMgr;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.mysql.MysqlCapability;
import com.starrocks.mysql.MysqlChannel;
import com.starrocks.mysql.MysqlCommand;
import com.starrocks.mysql.MysqlSerializer;
import com.starrocks.plugin.AuditEvent.AuditEventBuilder;
import com.starrocks.privilege.PrivilegeBuiltinConstants;
import com.starrocks.proto.PQueryStatistics;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.ConnectProcessor;
import com.starrocks.qe.QueryState;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.common.AuditEncryptionChecker;
import com.starrocks.sql.optimizer.dump.MockDumpInfo;
import com.starrocks.sql.plan.PlanTestBase;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * @author luoyuanluoyuan
 * @date 2025/7/1 15:46
 */

public class MetricsQueryTest extends PlanTestBase {
    private static ByteBuffer initDbPacket;
    private static ByteBuffer initWarehousePacket;
    private static ByteBuffer changeUserPacket;
    private static ByteBuffer resetConnectionPacket;
    private static ByteBuffer pingPacket;
    private static ByteBuffer quitPacket;
    private static ByteBuffer queryPacket;
    private static ByteBuffer fieldListPacket;
    private static AuditEventBuilder auditBuilder = new AuditEventBuilder();
    private static ConnectContext myContext;

    @Mocked
    private static SocketChannel socketChannel;

    private static PQueryStatistics statistics = new PQueryStatistics();

    @BeforeClass
    public static void setUpClass() {
        // Init Database packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(2);
            serializer.writeEofString("testDb");
            initDbPacket = serializer.toByteBuffer();
        }

        // Init Warehouse packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(2);
            serializer.writeEofString("'warehouse aaa'");
            initWarehousePacket = serializer.toByteBuffer();
        }

        // Change user packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(17);
            // code
            serializer.writeInt1(17);
            // user name
            serializer.writeNulTerminateString("starrocks-user");
            // plugin data
            serializer.writeInt1(20);
            byte[] buf = new byte[20];
            for (int i = 0; i < 20; ++i) {
                buf[i] = (byte) ('a' + i);
            }
            serializer.writeBytes(buf);
            // database
            serializer.writeNulTerminateString("testDb");
            // character set
            serializer.writeInt2(33);
            changeUserPacket = serializer.toByteBuffer();
        }

        // Reset connection packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(31);
            resetConnectionPacket = serializer.toByteBuffer();
        }

        // Ping packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(14);
            pingPacket = serializer.toByteBuffer();
        }

        // Quit packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(1);
            quitPacket = serializer.toByteBuffer();
        }

        // Query packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(3);
            serializer.writeEofString("select c1 from testDb.testTb");
            queryPacket = serializer.toByteBuffer();
        }

        // Field list packet
        {
            MysqlSerializer serializer = MysqlSerializer.newInstance();
            serializer.writeInt1(4);
            serializer.writeNulTerminateString("c1");
            serializer.writeEofString("");
            fieldListPacket = serializer.toByteBuffer();
        }

        statistics.scanBytes = 0L;
        statistics.scanRows = 0L;

        Mockito.mockStatic(AuditEncryptionChecker.class);
    }

    @Before
    public void setUp() {
        super.setUp();
        initDbPacket.clear();
        initWarehousePacket.clear();
        pingPacket.clear();
        quitPacket.clear();
        queryPacket.clear();
        fieldListPacket.clear();
        changeUserPacket.clear();
        resetConnectionPacket.clear();
        // Mock
        MysqlChannel channel = new MysqlChannel(socketChannel);
        new Expectations(channel) {
            {
                channel.getRemoteHostPortString();
                minTimes = 0;
                result = "127.0.0.1:12345";
            }
        };
        myContext = new ConnectContext(socketChannel);
        myContext.setDatabase("testDb");
        Deencapsulation.setField(myContext, "mysqlChannel", channel);
    }

    private static ConnectContext initMockContext(MysqlChannel channel, GlobalStateMgr globalStateMgr) {
        ConnectContext context = new ConnectContext(socketChannel) {
            private boolean firstTimeToSetCommand = true;

            @Override
            public void setKilled() {
                myContext.setKilled();
            }

            @Override
            public MysqlSerializer getSerializer() {
                return myContext.getSerializer();
            }

            @Override
            public QueryState getState() {
                return myContext.getState();
            }

            @Override
            public void setStartTime() {
                myContext.setStartTime();
            }

            @Override
            public String getDatabase() {
                return myContext.getDatabase();
            }

            @Override
            public void setCommand(MysqlCommand command) {
                if (firstTimeToSetCommand) {
                    myContext.setCommand(command);
                    firstTimeToSetCommand = false;
                } else {
                    super.setCommand(command);
                }
            }
        };

        new Expectations(context) {
            {
                context.getMysqlChannel();
                minTimes = 0;
                result = channel;

                context.isKilled();
                minTimes = 0;
                maxTimes = 3;
                returns(false, true, false);

                context.getGlobalStateMgr();
                minTimes = 0;
                result = globalStateMgr;

                context.getAuditEventBuilder();
                minTimes = 0;
                result = auditBuilder;

                context.getQualifiedUser();
                minTimes = 0;
                result = "testCluster:user";

                context.getCurrentUserIdentity();
                minTimes = 0;
                result = UserIdentity.ROOT;

                context.getStartTime();
                minTimes = 0;
                result = 0L;

                context.getReturnRows();
                minTimes = 0;
                result = 1L;

                context.setStmtId(anyLong);
                minTimes = 0;

                context.getStmtId();
                minTimes = 0;
                result = 1L;

                context.getExecutionId();
                minTimes = 0;
                result = new TUniqueId();

                context.getCapability();
                minTimes = 0;
                result = MysqlCapability.DEFAULT_CAPABILITY;
            }
        };

        return context;
    }

    private static MysqlChannel mockChannel(ByteBuffer packet) {
        try {
            MysqlChannel channel = new MysqlChannel(socketChannel);
            new Expectations(channel) {
                {
                    // Mock receive
                    channel.fetchOnePacket();
                    minTimes = 0;
                    result = packet;

                    // Mock reset
                    channel.setSequenceId(0);
                    times = 1;

                    // Mock send
                    channel.sendAndFlush((ByteBuffer) any);
                    minTimes = 0;

                    channel.getRemoteHostPortString();
                    minTimes = 0;
                    result = "127.0.0.1:12345";
                }
            };
            return channel;
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    public void testQueryInternalErrMetric() throws Exception {
        starRocksAssert.withDatabase("testDb")
                .useDatabase("testDb")
                .withTable("create table testTb (c1 int, c2 int) " +
                        "properties('replication_num'='1') ");
        String sql = "select c1 from testDb.testTb";
        ConnectContext ctx = initMockContext(mockChannel(queryPacket), GlobalStateMgr.getCurrentState());
        ctx.setCurrentUserIdentity(UserIdentity.ROOT);
        ctx.setCurrentRoleIds(Sets.newHashSet(PrivilegeBuiltinConstants.ROOT_ROLE_ID));
        ctx.setQualifiedUser(AuthenticationMgr.ROOT_USER);
        ctx.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        ctx.setThreadLocalInfo();
        ctx.setDumpInfo(new MockDumpInfo());
        ctx.setSessionId(UUIDUtil.genUUID());
        QueryStatement parsedStmt = (QueryStatement) UtFrameUtils.parseStmtWithNewParser(sql, ctx);
        StmtExecutor executor = new StmtExecutor(ctx, parsedStmt);
        executor.addRunningQueryDetail(parsedStmt);
        ConnectProcessor processor = new ConnectProcessor(ctx);
        // Mock statement executor
        new Expectations() {
            {
                executor.getQueryStatisticsForAuditLog();
                minTimes = 0;
                result = statistics;
            }
        };

        processor.processOnce();
        executor.addRunningQueryDetail(parsedStmt);
        Assert.assertEquals(MetricRepo.COUNTER_QUERY_ALL.getValue().longValue(), 1L);
    }
}
