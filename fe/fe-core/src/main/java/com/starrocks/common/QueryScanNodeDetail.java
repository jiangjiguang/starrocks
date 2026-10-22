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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/FeConstants.java

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

package com.starrocks.common;

import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.HudiTable;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.OlapTable;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.planner.HdfsScanNode;
import com.starrocks.planner.HudiScanNode;
import com.starrocks.planner.IcebergScanNode;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.ScanNode;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.plan.HDFSScanNodePredicates;
import com.starrocks.thrift.TScanRangeLocations;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QueryScanNodeDetail {
    private static final Logger LOG = LoggerFactory.getLogger(QueryScanNodeDetail.class);

    @SerializedName(value = "tableName")
    private String tableName;

    @SerializedName(value = "scanPartitions")
    private Integer scanPartitions;

    @SerializedName(value = "totalPartitions")
    private Integer totalPartitions;

    @SerializedName(value = "scanFiles")
    private Integer scanFiles;

    @SerializedName(value = "partitionPruneFail")
    private Boolean partitionPruneFail = false;


    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public Integer getScanPartitions() {
        return scanPartitions;
    }

    public void setScanPartitions(Integer scanPartitions) {
        this.scanPartitions = scanPartitions;
    }

    public Integer getScanFiles() {
        return scanFiles;
    }

    public void setScanFiles(Integer scanFiles) {
        this.scanFiles = scanFiles;
    }

    public Integer getTotalPartitions() {
        return totalPartitions;
    }

    public void setTotalPartitions(Integer totalPartitions) {
        this.totalPartitions = totalPartitions;
    }

    public Boolean getPartitionPruneFail() {
        return partitionPruneFail;
    }

    public void setPartitionPruneFail(Boolean partitionPruneFail) {
        this.partitionPruneFail = partitionPruneFail;
    }

    public static String getScanNodeExplain(ConnectContext connectContext, List<ScanNode> scanNodes) {
        List<QueryScanNodeDetail> list = new ArrayList<>();
        scanNodes.forEach(scanNode -> {
            QueryScanNodeDetail detail = new QueryScanNodeDetail();
            long start = System.currentTimeMillis();
            if (scanNode instanceof HdfsScanNode) {
                dealHdfsScanNode(scanNode, detail);
            }

            if (scanNode instanceof IcebergScanNode) {
                dealIcebergScanNode(scanNode, detail);
            }

            if (scanNode instanceof HudiScanNode) {
                dealHudiScanNode(scanNode, detail);
            }

            if (scanNode instanceof OlapScanNode) {
                dealOlapScanNode(scanNode, detail);
            }
            long end = System.currentTimeMillis();
            long elapseMs = end - start;
            if (elapseMs > 1000) {
                LOG.warn("deal scan node elapse: {}ms", elapseMs);
            }
            list.add(detail);
        });


        connectContext.setQueryScanNodeDetail(list);

        return GsonUtils.GSON.newBuilder().disableHtmlEscaping()
                .create().toJson(list);
    }

    private static void dealHdfsScanNode(ScanNode scanNode, QueryScanNodeDetail detail) {
        try {
            HdfsScanNode hdfsScanNode = (HdfsScanNode) scanNode;
            HDFSScanNodePredicates scanNodePredicates = hdfsScanNode.getScanNodePredicates();
            if (!CollectionUtils.isEmpty(scanNodePredicates.getNoEvalPartitionConjuncts())) {
                detail.setPartitionPruneFail(true);
            }
            HiveTable table = hdfsScanNode.getHiveTable();
            String dbName = table.getDbName();
            String tableName = table.getTableName();
            detail.setTableName(dbName + "." + tableName);
            detail.setScanPartitions(scanNodePredicates.getSelectedPartitionIds().size());
            detail.setTotalPartitions(table.getPartitionCount());

            // Use already computed scanRangeLocations to count unique files
            // instead of re-fetching from metadata (which may be stale or unavailable)
            List<TScanRangeLocations> scanRanges = hdfsScanNode.getScanRangeLocations(0);
            Set<String> uniqueFiles = new HashSet<>();
            for (TScanRangeLocations scanRange : scanRanges) {
                if (scanRange.getScan_range() != null && scanRange.getScan_range().isSetHdfs_scan_range()) {
                    uniqueFiles.add(scanRange.getScan_range().getHdfs_scan_range().getRelative_path());
                }
            }
            detail.setScanFiles(uniqueFiles.size());
        } catch (Exception e) {
            LOG.error("Failed to get explain sql_scan_detail in HdfsScanNode", e);
            throw e;
        }
    }

    private static void dealIcebergScanNode(ScanNode scanNode, QueryScanNodeDetail detail) {
        try {
            IcebergScanNode icebergScanNode = (IcebergScanNode) scanNode;
            IcebergTable table = icebergScanNode.getIcebergTable();
            String catalogName = table.getCatalogName();
            String dbName = table.getRemoteDbName();
            String tableName = table.getRemoteTableName();
            detail.setTableName(dbName + "." + tableName);
            HDFSScanNodePredicates scanNodePredicates = icebergScanNode.getScanNodePredicates();
            if (!CollectionUtils.isEmpty(scanNodePredicates.getNoEvalPartitionConjuncts())) {
                detail.setPartitionPruneFail(true);
            }
            detail.setScanPartitions(scanNodePredicates.getSelectedPartitionIds().size());
            List<TScanRangeLocations> locationsList = icebergScanNode.getScanRangeLocations(0);
            detail.setScanFiles(locationsList.size());
            // Iceberg table needs to get partition count via MetadataMgr
            List<String> partitionNames = GlobalStateMgr.getCurrentState().getMetadataMgr()
                    .listPartitionNames(catalogName, dbName, tableName);
            detail.setTotalPartitions(partitionNames.size() == 0 ? 1 : partitionNames.size());
        } catch (Exception e) {
            LOG.error("Failed to get explain sql_scan_detail in IcebergScanNode", e);
            throw e;
        }
    }

    private static void dealHudiScanNode(ScanNode scanNode, QueryScanNodeDetail detail) {
        try {
            HudiScanNode hudiScanNode = (HudiScanNode) scanNode;
            HudiTable table = hudiScanNode.getHudiTable();
            String dbName = table.getDbName();
            String tableName = table.getTableName();
            detail.setTableName(dbName + "." + tableName);
            HDFSScanNodePredicates scanNodePredicates = hudiScanNode.getScanNodePredicates();
            if (!CollectionUtils.isEmpty(scanNodePredicates.getNoEvalPartitionConjuncts())) {
                detail.setPartitionPruneFail(true);
            }
            detail.setScanPartitions(scanNodePredicates.getSelectedPartitionIds().size());
            List<TScanRangeLocations> locationsList = hudiScanNode.getScanRangeLocations(0);
            detail.setScanFiles(locationsList.size());
            detail.setTotalPartitions(table.getPartitionCount());
        } catch (Exception e) {
            LOG.error("Failed to get explain sql_scan_detail in HudiScanNode", e);
            throw e;
        }
    }

    private static void dealOlapScanNode(ScanNode scanNode, QueryScanNodeDetail detail) {
        try {
            OlapScanNode olapScanNode = (OlapScanNode) scanNode;
            OlapTable table = olapScanNode.getOlapTable();
            detail.setTableName(table.getTableIdentifier());
            detail.setPartitionPruneFail(olapScanNode.isPartitionPruneFail());
            detail.setScanPartitions(olapScanNode.getSelectedPartitionIds().size());
            detail.setTotalPartitions(olapScanNode.getOlapTable().getPartitionsCount());
            detail.setScanFiles(olapScanNode.getScanTabletIds().size());
        } catch (Exception e) {
            LOG.error("Failed to get explain sql_scan_detail in OlapScanNode", e);
            throw e;
        }
    }


    public static void checkQueryScanSizeValid(ConnectContext connectContext, List<ScanNode> scanNodes)
            throws UserException {
        if (scanNodes.isEmpty()) {
            return;
        }
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        if (sessionVariable == null) {
            return;
        }

        long start = System.currentTimeMillis();
        int totalScanPartitionSize = 0;
        int totalScanFileSize = 0;
        boolean partitionPrunedFail = false;
        List<String> fullTableNames = new ArrayList<>();
        try {
            List<QueryScanNodeDetail> scanNodeDetailList = new ArrayList<>();
            for (ScanNode scanNode : scanNodes) {
                QueryScanNodeDetail detail = new QueryScanNodeDetail();
                String fullTableName = null;
                boolean currentPartitionPrunedFail = false;
                int currentScanPartitionSize = 0;
                int currentScanFileSize = 0;
                int totalPartitionSize = 0;
                if (scanNode instanceof HdfsScanNode) {
                    HdfsScanNode hdfsScanNode = (HdfsScanNode) scanNode;
                    HiveTable hiveTable = hdfsScanNode.getHiveTable();
                    fullTableName = hiveTable.getDbName() + "." + hiveTable.getTableName();
                    currentScanPartitionSize = hdfsScanNode.getScanNodePredicates().getSelectedPartitionIds().size();
                    // Use getScanRangeLocations(0) to get already computed scan ranges
                    // and count unique files from relative_path
                    List<TScanRangeLocations> scanRanges = hdfsScanNode.getScanRangeLocations(0);
                    Set<String> uniqueFiles = new HashSet<>();
                    for (TScanRangeLocations scanRange : scanRanges) {
                        if (scanRange.getScan_range() != null && scanRange.getScan_range().isSetHdfs_scan_range()) {
                            uniqueFiles.add(scanRange.getScan_range().getHdfs_scan_range().getRelative_path());
                        }
                    }
                    currentScanFileSize = uniqueFiles.size();
                    currentPartitionPrunedFail = !CollectionUtils.isEmpty(hdfsScanNode.getScanNodePredicates().getNoEvalPartitionConjuncts());
                    totalPartitionSize = hiveTable.getPartitionCount();
                }
                if (scanNode instanceof IcebergScanNode) {
                    IcebergScanNode icebergScanNode = (IcebergScanNode) scanNode;
                    IcebergTable icebergTable = icebergScanNode.getIcebergTable();
                    String catalogName = icebergTable.getCatalogName();
                    String dbName = icebergTable.getRemoteDbName();
                    String tableName = icebergTable.getRemoteTableName();
                    fullTableName = dbName + "." + tableName;
                    currentScanPartitionSize = icebergScanNode.getScanNodePredicates().getSelectedPartitionIds().size();
                    // Scan ranges have been computed in prepareExec(), use the computed result directly
                    currentScanFileSize = icebergScanNode.getScanRangeLocations(0).size();
                    currentPartitionPrunedFail = !CollectionUtils.isEmpty(icebergScanNode.getScanNodePredicates().getNoEvalPartitionConjuncts());
                    // Iceberg table needs to get partition count via MetadataMgr
                    List<String> partitionNames = GlobalStateMgr.getCurrentState().getMetadataMgr()
                            .listPartitionNames(catalogName, dbName, tableName);
                    totalPartitionSize = partitionNames.size() == 0 ? 1 : partitionNames.size();
                }
                if (scanNode instanceof HudiScanNode) {
                    HudiScanNode hudiScanNode = (HudiScanNode) scanNode;
                    HudiTable hudiTable = hudiScanNode.getHudiTable();
                    fullTableName = hudiTable.getDbName() + "." + hudiTable.getTableName();
                    currentScanPartitionSize = hudiScanNode.getScanNodePredicates().getSelectedPartitionIds().size();
                    // Scan ranges have been computed in prepareExec(), use the computed result directly
                    currentScanFileSize = hudiScanNode.getScanRangeLocations(0).size();
                    currentPartitionPrunedFail = !CollectionUtils.isEmpty(hudiScanNode.getScanNodePredicates().getNoEvalPartitionConjuncts());
                    totalPartitionSize = hudiTable.getPartitionCount();
                }
                if (scanNode instanceof OlapScanNode) {
                    OlapScanNode olapScanNode = (OlapScanNode) scanNode;
                    OlapTable table = olapScanNode.getOlapTable();
                    fullTableName = table.getTableIdentifier();
                    currentScanPartitionSize = olapScanNode.getSelectedPartitionIds().size();
                    currentScanFileSize = olapScanNode.getScanTabletIds().size();
                    currentPartitionPrunedFail = olapScanNode.isPartitionPruneFail();
                    totalPartitionSize = table.getPartitions().size();
                }
                fullTableNames.add(fullTableName);
                totalScanPartitionSize += currentScanPartitionSize;
                totalScanFileSize += currentScanFileSize;
                if (!partitionPrunedFail) {
                    partitionPrunedFail = currentPartitionPrunedFail;
                }
                detail.setTableName(fullTableName);
                detail.setPartitionPruneFail(currentPartitionPrunedFail);
                detail.setScanPartitions(currentScanPartitionSize);
                detail.setScanFiles(currentScanFileSize);
                detail.setTotalPartitions(totalPartitionSize);
                scanNodeDetailList.add(detail);
            }

            connectContext.setQueryScanNodeDetail(scanNodeDetailList);
        } catch (Exception e) {
            LOG.warn("check query scan detail fail, reason is ", e);
            return;
        }

        checkScanTableLimit(start, sessionVariable, fullTableNames.size());
        checkScanPartitionLimit(start, sessionVariable, totalScanPartitionSize);
        checkScanFileLimit(start, sessionVariable, totalScanFileSize);
        checkPartitionPruneFail(start, sessionVariable, partitionPrunedFail);

        long end = System.currentTimeMillis();
        long elapseMs = end - start;
        if (elapseMs > 1000) {
            LOG.warn("check sql scan size valid elapse: {}ms", elapseMs);
        }
    }

    private static void checkScanTableLimit(Long start,
                                            SessionVariable sessionVariable,
                                            int fullTableNameSize)
            throws UserException {
        int maxTableSize = sessionVariable.getScanTablesLimit();
        if (maxTableSize > 0) {
            if (fullTableNameSize > maxTableSize) {
                long end = System.currentTimeMillis();
                long elapseMs = end - start;
                if (elapseMs > 1000) {
                    LOG.warn("check sql scan tables size valid elapse: {}ms", elapseMs);
                }
                throw new UserException(String.format("The number of sql scan tables [%s] has exceeded the limit [%s]",
                        fullTableNameSize, maxTableSize));
            }
        }
    }


    private static void checkScanPartitionLimit(Long start,
                                                SessionVariable sessionVariable,
                                                Integer currentTotalScanPartitionSize) throws UserException {
        int maxPartitionSize = sessionVariable.getScanPartitionsLimit();
        if (maxPartitionSize > 0) {
            if (currentTotalScanPartitionSize > maxPartitionSize) {
                long end = System.currentTimeMillis();
                long elapseMs = end - start;
                if (elapseMs > 1000) {
                    LOG.warn("check sql scan partition size valid elapse: {}ms", elapseMs);
                }
                throw new UserException(String.format("The number of sql scan partitions [%s] has exceeded the limit [%s]",
                        currentTotalScanPartitionSize, maxPartitionSize));
            }
        }
    }

    private static void checkScanFileLimit(Long start,
                                 SessionVariable sessionVariable,
                                 Integer currentTotalScanFileSize)
            throws UserException {
        int maxFileSize = sessionVariable.getScanFilesLimit();
        if (maxFileSize > 0) {
            if (currentTotalScanFileSize > maxFileSize) {
                long end = System.currentTimeMillis();
                long elapseMs = end - start;
                if (elapseMs > 1000) {
                    LOG.warn("check sql scan files size valid elapse: {}ms", elapseMs);
                }
                throw new UserException(String.format("The number of sql scan files [%s] has exceeded the limit [%s]",
                        currentTotalScanFileSize, maxFileSize));
            }
        }
    }

    private static void checkPartitionPruneFail(Long start,
                                            SessionVariable sessionVariable,
                                            Boolean partitionPruneFail)
            throws UserException {
        boolean enablePartitionPruneFailLimit = sessionVariable.isEnablePartitionPruneFailLimit();
        if (enablePartitionPruneFailLimit) {
            if (partitionPruneFail) {
                long end = System.currentTimeMillis();
                long elapseMs = end - start;
                if (elapseMs > 1000) {
                    LOG.warn("check sql scan partition prune fail elapse: {}ms", elapseMs);
                }
                throw new UserException("The sql query partition clipping failed, Query prohibited");
            }
        }
    }
}
