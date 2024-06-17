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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/planner/StreamLoadPlannerTest.java

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

package com.starrocks.planner;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.starrocks.analysis.Analyzer;
import com.starrocks.analysis.CompoundPredicate;
import com.starrocks.analysis.Expr;
import com.starrocks.catalog.ColumnId;
import com.starrocks.lake.LakeTablet;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.ImportColumnsStmt;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.TableProperty;
import com.starrocks.catalog.Type;
import com.starrocks.common.UserException;
import com.starrocks.load.routineload.KafkaRoutineLoadJob;
import com.starrocks.load.routineload.RoutineLoadJob;
import com.starrocks.load.streamload.StreamLoadInfo;
import com.starrocks.load.streamload.StreamLoadParam;
import com.starrocks.system.ComputeNode;
import com.starrocks.thrift.TCompressionType;
import com.starrocks.sql.ast.PartitionKeyDesc;
import com.starrocks.sql.ast.PartitionValue;
import com.starrocks.sql.ast.SingleRangePartitionDesc;
import com.starrocks.thrift.TFileFormatType;
import com.starrocks.thrift.TFileType;
import com.starrocks.thrift.TStreamLoadPutRequest;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.warehouse.DefaultWarehouse;
import com.starrocks.warehouse.Warehouse;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;

import static com.starrocks.common.util.DateUtils.DATE_TIME_FORMATTER;
import static com.starrocks.common.util.DateUtils.HOUR_FORMATTER;

public class StreamLoadPlannerTest {
    @Injectable
    Database db;

    @Injectable
    OlapTable destTable;

    @Mocked
    StreamLoadScanNode scanNode;

    @Mocked
    OlapTableSink sink;

    @Mocked
    Partition partition;

    @Before
    public void before() {
        new MockUp<WarehouseManager>() {
            @Mock
            public Warehouse getWarehouse(long warehouseId) {
                return new DefaultWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_ID,
                        WarehouseManager.DEFAULT_WAREHOUSE_NAME);
            }

            @Mock
            public Warehouse getWarehouse(String warehouseName) {
                return new DefaultWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_ID,
                        WarehouseManager.DEFAULT_WAREHOUSE_NAME);
            }

            @Mock
            public ComputeNode getComputeNode(LakeTablet tablet) {
                return new ComputeNode(1L, "127.0.0.1", 9030);
            }

            @Mock
            public ComputeNode getComputeNode(Long warehouseId, LakeTablet tablet) {
                return new ComputeNode(1L, "127.0.0.1", 9030);
            }

            @Mock
            public ImmutableMap<Long, ComputeNode> getComputeNodesFromWarehouse(long warehouseId) {
                return ImmutableMap.of(1L, new ComputeNode(1L, "127.0.0.1", 9030));
            }
        };
    }

    @Test
    public void testNormalPlan() throws UserException {
        List<Column> columns = Lists.newArrayList();
        Column c1 = new Column("c1", Type.BIGINT, false);
        columns.add(c1);
        Column c2 = new Column("c2", Type.BIGINT, true);
        columns.add(c2);
        new Expectations() {
            {
                destTable.getBaseSchema();
                minTimes = 0;
                result = columns;
                destTable.getPartitions();
                minTimes = 0;
                result = Arrays.asList(partition);
                scanNode.init((Analyzer) any);
                minTimes = 0;
                scanNode.getChildren();
                minTimes = 0;
                result = Lists.newArrayList();
                scanNode.getId();
                minTimes = 0;
                result = new PlanNodeId(5);
                partition.getId();
                minTimes = 0;
                result = 0;
            }
        };
        TStreamLoadPutRequest request = new TStreamLoadPutRequest();
        request.setTxnId(1);
        request.setLoadId(new TUniqueId(2, 3));
        request.setFileType(TFileType.FILE_STREAM);
        request.setFormatType(TFileFormatType.FORMAT_CSV_PLAIN);
        request.setLoad_dop(2);
        request.setPayload_compression_type("LZ4_FRAME");
        StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, db);
        StreamLoadPlanner planner = new StreamLoadPlanner(db, destTable, streamLoadInfo);
        planner.plan(streamLoadInfo.getId());
        Assert.assertEquals(TCompressionType.LZ4_FRAME, streamLoadInfo.getPayloadCompressionType());
    }

    @Test
    public void testPartialUpdatePlan() throws UserException {
        List<Column> columns = Lists.newArrayList();
        Column c1 = new Column("c1", Type.BIGINT, false);
        columns.add(c1);
        Column c2 = new Column("c2", Type.BIGINT, true);
        columns.add(c2);
        new Expectations() {
            {
                destTable.getKeysType();
                minTimes = 0;
                result = KeysType.PRIMARY_KEYS;
                destTable.getBaseSchema();
                minTimes = 0;
                result = columns;
                destTable.getPartitions();
                minTimes = 0;
                result = Arrays.asList(partition);
                scanNode.init((Analyzer) any);
                minTimes = 0;
                scanNode.getChildren();
                minTimes = 0;
                result = Lists.newArrayList();
                scanNode.getId();
                minTimes = 0;
                result = new PlanNodeId(5);
                partition.getId();
                minTimes = 0;
                result = 0;
            }
        };
        TStreamLoadPutRequest request = new TStreamLoadPutRequest();
        request.setTxnId(1);
        request.setLoadId(new TUniqueId(2, 3));
        request.setFileType(TFileType.FILE_STREAM);
        request.setFormatType(TFileFormatType.FORMAT_CSV_PLAIN);
        request.setPartial_update(true);
        request.setColumns("c1");
        StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, db);
        StreamLoadPlanner planner = new StreamLoadPlanner(db, destTable, streamLoadInfo);
        planner.plan(streamLoadInfo.getId());
    }

    @Test
    public void testPartialUpdateMode() throws UserException {
        StreamLoadParam param = new StreamLoadParam();
        param.partialUpdateMode = "column";
        UUID uuid = UUID.randomUUID();
        TUniqueId loadId = new TUniqueId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        StreamLoadInfo streamLoadInfo2 = StreamLoadInfo.fromStreamLoadContext(loadId, 100, 100, param);
        RoutineLoadJob routineLoadJob = new KafkaRoutineLoadJob();
        StreamLoadInfo streamLoadInfo3 = StreamLoadInfo.fromRoutineLoadJob(routineLoadJob);
    }

    @Test
    public void testParseStmt() {
        String sql = "COLUMNS (k1, k2, k3=abc(), k4=default_value())";
        ImportColumnsStmt columnsStmt = com.starrocks.sql.parser.SqlParser.parseImportColumns(sql, 0);
        Assert.assertEquals(4, columnsStmt.getColumns().size());

        sql = "k1 > 2 and k3 < 4";
        Expr where = com.starrocks.sql.parser.SqlParser.parseSqlToExpr(sql, 0);
        Assert.assertTrue(where instanceof CompoundPredicate);
    }

    @Test
    public void testWriteRange() throws UserException {
        List<Column> columns = Lists.newArrayList();
        Column dt = new Column("dt", Type.DATETIME, false);
        columns.add(dt);
        Column c1 = new Column("c1", Type.BIGINT, false);
        columns.add(c1);
        Column c2 = new Column("c2", Type.BIGINT, true);
        columns.add(c2);

        Map<ColumnId, Column> schema = columns.stream().collect(Collectors.toMap(Column::getColumnId, Column -> Column));

        RangePartitionInfo rangePartitionInfo = new RangePartitionInfo(Collections.singletonList(dt));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime baseDate = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), now.getHour(), 0);
        for (int i = -12; i < 12; i++) {
            LocalDateTime startDate = baseDate.plus(i, ChronoUnit.HOURS);
            LocalDateTime endDate = startDate.plus(1, ChronoUnit.HOURS);
            String pt = startDate.format(HOUR_FORMATTER);
            SingleRangePartitionDesc desc = new SingleRangePartitionDesc(false, "p" + pt,
                new PartitionKeyDesc(Lists.newArrayList(new PartitionValue(startDate.format(DATE_TIME_FORMATTER))),
                    Lists.newArrayList(new PartitionValue(endDate.format(DATE_TIME_FORMATTER)))), null);
            Range<PartitionKey> range = rangePartitionInfo.checkAndCreateRange(schema, desc, false);
            rangePartitionInfo.addPartition(i, false, range, null, (short) 1, false);
        }
        Map<String, String> props = Maps.newHashMap();
        props.put("dynamic_partition.enable", "true");
        props.put("dynamic_partition.time_unit", "HOUR");
        props.put("dynamic_partition.time_zone", "Asia/Shanghai");
        props.put("dynamic_partition.end", "1");
        TableProperty tableProperty = new TableProperty(props);
        tableProperty.buildDynamicProperty();

        new Expectations() {
            {
                destTable.getKeysType();
                minTimes = 0;
                result = KeysType.DUP_KEYS;
                destTable.getPartitionInfo();
                minTimes = 0;
                result = rangePartitionInfo;
                destTable.getTableProperty();
                minTimes = 0;
                result = tableProperty;
                destTable.getIdToColumn();
                minTimes = 0;
                result = schema;
            }
        };

        TStreamLoadPutRequest request = new TStreamLoadPutRequest();
        request.setTxnId(1);
        request.setLoadId(new TUniqueId(2, 3));
        request.setFileType(TFileType.FILE_STREAM);
        request.setFormatType(TFileFormatType.FORMAT_CSV_PLAIN);
        request.setColumns("c1");
        request.setPartition_write_start(-3);
        request.setPartition_write_end(2);
        StreamLoadInfo streamLoadInfo = StreamLoadInfo.fromTStreamLoadPutRequest(request, db);
        StreamLoadPlanner planner = new StreamLoadPlanner(db, destTable, streamLoadInfo);
        List<Long> partitionIds = planner.getAllPartitionIds();
        Assert.assertEquals(6, partitionIds.size());
    }
}
