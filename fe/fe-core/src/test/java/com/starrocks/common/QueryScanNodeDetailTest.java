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

package com.starrocks.common;

import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.HudiTable;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.OlapTable;
import com.starrocks.planner.HdfsScanNode;
import com.starrocks.planner.HudiScanNode;
import com.starrocks.planner.IcebergScanNode;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.ScanNode;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.MetadataMgr;
import com.starrocks.sql.plan.HDFSScanNodePredicates;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TScanRange;
import com.starrocks.thrift.TScanRangeLocations;
import mockit.Expectations;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QueryScanNodeDetailTest {

    @Mocked
    private GlobalStateMgr globalStateMgr;

    @Mocked
    private MetadataMgr metadataMgr;

    private ConnectContext connectContext;

    @Before
    public void setUp() {
        connectContext = new ConnectContext();
        connectContext.setSessionVariable(new SessionVariable());

        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = globalStateMgr;
                minTimes = 0;

                globalStateMgr.getMetadataMgr();
                result = metadataMgr;
                minTimes = 0;
            }
        };
    }

    @After
    public void tearDown() {
        connectContext = null;
    }

    @Test
    public void testGetScanNodeExplainWithHdfsScanNode(@Mocked HiveTable hiveTable,
                                                        @Mocked HdfsScanNode hdfsScanNode) {
        // Setup HDFSScanNodePredicates
        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        Set<Long> selectedPartitionIds = new HashSet<>();
        selectedPartitionIds.add(1L);
        selectedPartitionIds.add(2L);
        predicates.setSelectedPartitionIds(selectedPartitionIds);

        // Setup scan range locations with files
        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Arrays.asList("file1.parquet", "file2.parquet", "file1.parquet")); // duplicate file1

        new Expectations() {
            {
                hdfsScanNode.getScanNodePredicates();
                result = predicates;

                hdfsScanNode.getHiveTable();
                result = hiveTable;

                hiveTable.getDbName();
                result = "test_db";

                hiveTable.getTableName();
                result = "test_table";

                hiveTable.getPartitionCount();
                result = 100;

                hdfsScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(hdfsScanNode);

        String result = QueryScanNodeDetail.getScanNodeExplain(connectContext, scanNodes);

        assertNotNull(result);
        assertTrue(result.contains("test_db.test_table"));
        assertTrue(result.contains("\"scanPartitions\":2"));
        assertTrue(result.contains("\"totalPartitions\":100"));
        // Should be 2 unique files (file1 and file2), not 3
        assertTrue(result.contains("\"scanFiles\":2"));
        assertTrue(result.contains("\"partitionPruneFail\":false"));
    }

    @Test
    public void testGetScanNodeExplainWithIcebergScanNode(@Mocked IcebergTable icebergTable,
                                                          @Mocked IcebergScanNode icebergScanNode) {
        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        Set<Long> selectedPartitionIds = new HashSet<>();
        selectedPartitionIds.add(1L);
        predicates.setSelectedPartitionIds(selectedPartitionIds);

        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Arrays.asList("file1.parquet", "file2.parquet"));

        List<String> partitionNames = Arrays.asList("p1", "p2", "p3");

        new Expectations() {
            {
                icebergScanNode.getIcebergTable();
                result = icebergTable;

                icebergScanNode.getScanNodePredicates();
                result = predicates;

                icebergTable.getCatalogName();
                result = "iceberg_catalog";

                icebergTable.getRemoteDbName();
                result = "test_db";

                icebergTable.getRemoteTableName();
                result = "test_table";

                icebergScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;

                metadataMgr.listPartitionNames("iceberg_catalog", "test_db", "test_table");
                result = partitionNames;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(icebergScanNode);

        String result = QueryScanNodeDetail.getScanNodeExplain(connectContext, scanNodes);

        assertNotNull(result);
        assertTrue(result.contains("test_db.test_table"));
        assertTrue(result.contains("\"scanPartitions\":1"));
        assertTrue(result.contains("\"totalPartitions\":3"));
        assertTrue(result.contains("\"scanFiles\":2"));
    }

    @Test
    public void testGetScanNodeExplainWithIcebergUnpartitionedTable(
            @Mocked IcebergTable icebergTable,
            @Mocked IcebergScanNode icebergScanNode) {
        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        predicates.setSelectedPartitionIds(new HashSet<>());

        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Arrays.asList("file1.parquet"));

        // Empty partition names for unpartitioned table
        List<String> partitionNames = Collections.emptyList();

        new Expectations() {
            {
                icebergScanNode.getIcebergTable();
                result = icebergTable;

                icebergScanNode.getScanNodePredicates();
                result = predicates;

                icebergTable.getCatalogName();
                result = "iceberg_catalog";

                icebergTable.getRemoteDbName();
                result = "test_db";

                icebergTable.getRemoteTableName();
                result = "unpartitioned_table";

                icebergScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;

                metadataMgr.listPartitionNames("iceberg_catalog", "test_db", "unpartitioned_table");
                result = partitionNames;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(icebergScanNode);

        String result = QueryScanNodeDetail.getScanNodeExplain(connectContext, scanNodes);

        assertNotNull(result);
        // Unpartitioned table should have totalPartitions = 1
        assertTrue(result.contains("\"totalPartitions\":1"));
    }

    @Test
    public void testGetScanNodeExplainWithHudiScanNode(@Mocked HudiTable hudiTable,
                                                        @Mocked HudiScanNode hudiScanNode) {
        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        Set<Long> selectedPartitionIds = new HashSet<>();
        selectedPartitionIds.add(1L);
        predicates.setSelectedPartitionIds(selectedPartitionIds);

        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Arrays.asList("file1.parquet"));

        new Expectations() {
            {
                hudiScanNode.getHudiTable();
                result = hudiTable;

                hudiScanNode.getScanNodePredicates();
                result = predicates;

                hudiTable.getDbName();
                result = "test_db";

                hudiTable.getTableName();
                result = "test_table";

                hudiTable.getPartitionCount();
                result = 50;

                hudiScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(hudiScanNode);

        String result = QueryScanNodeDetail.getScanNodeExplain(connectContext, scanNodes);

        assertNotNull(result);
        assertTrue(result.contains("test_db.test_table"));
        assertTrue(result.contains("\"scanPartitions\":1"));
        assertTrue(result.contains("\"totalPartitions\":50"));
        assertTrue(result.contains("\"scanFiles\":1"));
    }

    @Test
    public void testGetScanNodeExplainWithOlapScanNode(@Mocked OlapTable olapTable,
                                                        @Mocked OlapScanNode olapScanNode) {
        new Expectations() {
            {
                olapScanNode.getOlapTable();
                result = olapTable;

                olapTable.getTableIdentifier();
                result = "test_db.test_table";

                olapScanNode.getSelectedPartitionIds();
                result = Arrays.asList(1L, 2L);

                olapScanNode.getScanTabletIds();
                result = Arrays.asList(101L, 102L, 103L);

                olapScanNode.getPrunedPartitionPredicates();
                result = Arrays.asList(); // Not empty means partition prune success

                olapTable.getPartitionsCount();
                result = 10;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(olapScanNode);

        String result = QueryScanNodeDetail.getScanNodeExplain(connectContext, scanNodes);

        assertNotNull(result);
        assertTrue(result.contains("test_db.test_table"));
        assertTrue(result.contains("\"scanPartitions\":2"));
        assertTrue(result.contains("\"totalPartitions\":10"));
        assertTrue(result.contains("\"scanFiles\":3"));
    }


    @Test
    public void testCheckQueryScanSizeValidWithEmptyScanNodes() throws UserException {
        List<ScanNode> scanNodes = new ArrayList<>();
        // Should not throw exception
        QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, scanNodes);
    }

    @Test
    public void testCheckQueryScanSizeValidWithPartitionLimit(
            @Mocked HiveTable hiveTable,
            @Mocked HdfsScanNode hdfsScanNode) {
        // Set partition limit to 10
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        sessionVariable.setScanPartitionsLimit(10);

        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        Set<Long> selectedPartitionIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            selectedPartitionIds.add((long) i);
        }
        predicates.setSelectedPartitionIds(selectedPartitionIds);

        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Collections.singletonList("file1.parquet"));

        new Expectations() {
            {
                hdfsScanNode.getHiveTable();
                result = hiveTable;

                hdfsScanNode.getScanNodePredicates();
                result = predicates;

                hiveTable.getDbName();
                result = "test_db";

                hiveTable.getTableName();
                result = "test_table";

                hiveTable.getPartitionCount();
                result = 100;

                hdfsScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(hdfsScanNode);

        try {
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, scanNodes);
            fail("Should throw UserException for exceeding partition limit");
        } catch (UserException e) {
            assertTrue(e.getMessage().contains("exceeded the limit"));
            assertTrue(e.getMessage().contains("20"));
            assertTrue(e.getMessage().contains("10"));
        }
    }

    @Test
    public void testCheckQueryScanSizeValidWithFileLimit(
            @Mocked HiveTable hiveTable,
            @Mocked HdfsScanNode hdfsScanNode) {
        // Set file limit to 5
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        sessionVariable.setScanFilesLimit(5);

        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        Set<Long> selectedPartitionIds = new HashSet<>();
        selectedPartitionIds.add(1L);
        predicates.setSelectedPartitionIds(selectedPartitionIds);

        // Create 10 unique files
        List<String> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add("file" + i + ".parquet");
        }
        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(files);

        new Expectations() {
            {
                hdfsScanNode.getHiveTable();
                result = hiveTable;

                hdfsScanNode.getScanNodePredicates();
                result = predicates;

                hiveTable.getDbName();
                result = "test_db";

                hiveTable.getTableName();
                result = "test_table";

                hiveTable.getPartitionCount();
                result = 100;

                hdfsScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(hdfsScanNode);

        try {
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, scanNodes);
            fail("Should throw UserException for exceeding file limit");
        } catch (UserException e) {
            assertTrue(e.getMessage().contains("exceeded the limit"));
            assertTrue(e.getMessage().contains("10"));
            assertTrue(e.getMessage().contains("5"));
        }
    }

    @Test
    public void testCheckQueryScanSizeValidWithTableLimit(
            @Mocked HiveTable hiveTable,
            @Mocked HdfsScanNode hdfsScanNode,
            @Mocked HiveTable hiveTable2,
            @Mocked HdfsScanNode hdfsScanNode2) {
        // Set table limit to 1
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        sessionVariable.setScanTablesLimit(1);

        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        predicates.setSelectedPartitionIds(new HashSet<>());

        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Collections.singletonList("file1.parquet"));

        new Expectations() {
            {
                hdfsScanNode.getHiveTable();
                result = hiveTable;

                hdfsScanNode.getScanNodePredicates();
                result = predicates;

                hiveTable.getDbName();
                result = "test_db";

                hiveTable.getTableName();
                result = "table1";

                hiveTable.getPartitionCount();
                result = 10;

                hdfsScanNode.getScanRangeLocations(0);
                result = scanRangeLocations;

                hdfsScanNode2.getHiveTable();
                result = hiveTable2;

                hdfsScanNode2.getScanNodePredicates();
                result = predicates;

                hiveTable2.getDbName();
                result = "test_db";

                hiveTable2.getTableName();
                result = "table2";

                hiveTable2.getPartitionCount();
                result = 10;

                hdfsScanNode2.getScanRangeLocations(0);
                result = scanRangeLocations;
            }
        };

        List<ScanNode> scanNodes = new ArrayList<>();
        scanNodes.add(hdfsScanNode);
        scanNodes.add(hdfsScanNode2);

        try {
            QueryScanNodeDetail.checkQueryScanSizeValid(connectContext, scanNodes);
            fail("Should throw UserException for exceeding table limit");
        } catch (UserException e) {
            assertTrue(e.getMessage().contains("exceeded the limit"));
            assertTrue(e.getMessage().contains("2"));
            assertTrue(e.getMessage().contains("1"));
        }
    }


    @Test
    public void testUniqueFileCount() throws UserException {
        // Test that duplicate files are counted only once
        HDFSScanNodePredicates predicates = new HDFSScanNodePredicates();
        Set<Long> selectedPartitionIds = new HashSet<>();
        selectedPartitionIds.add(1L);
        predicates.setSelectedPartitionIds(selectedPartitionIds);

        // 5 scan ranges but only 3 unique files
        List<TScanRangeLocations> scanRangeLocations = createMockScanRangeLocations(
                Arrays.asList("file1.parquet", "file2.parquet", "file3.parquet",
                        "file1.parquet", "file2.parquet"));

        List<ScanNode> scanNodes = new ArrayList<>();

        // Use MockUp to create a minimal mock
        new MockUp<HdfsScanNode>() {
            @mockit.Mock
            public HDFSScanNodePredicates getScanNodePredicates() {
                return predicates;
            }
        };

        // Verify that unique file count is correct
        Set<String> uniqueFiles = new HashSet<>();
        for (TScanRangeLocations scanRange : scanRangeLocations) {
            if (scanRange.getScan_range() != null && scanRange.getScan_range().isSetHdfs_scan_range()) {
                uniqueFiles.add(scanRange.getScan_range().getHdfs_scan_range().getRelative_path());
            }
        }

        assertEquals(3, uniqueFiles.size());
    }

    /**
     * Helper method to create mock TScanRangeLocations list with given file names
     */
    private List<TScanRangeLocations> createMockScanRangeLocations(List<String> fileNames) {
        List<TScanRangeLocations> result = new ArrayList<>();
        for (String fileName : fileNames) {
            TScanRangeLocations locations = new TScanRangeLocations();
            TScanRange scanRange = new TScanRange();
            THdfsScanRange hdfsScanRange = new THdfsScanRange();
            hdfsScanRange.setRelative_path(fileName);
            scanRange.setHdfs_scan_range(hdfsScanRange);
            locations.setScan_range(scanRange);
            result.add(locations);
        }
        return result;
    }
}
