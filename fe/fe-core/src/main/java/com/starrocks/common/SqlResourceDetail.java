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
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.proto.PQueryStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqlResourceDetail {
    private static final Logger LOG = LoggerFactory.getLogger(SqlResourceDetail.class);

    @SerializedName(value = "cpuCostNs")
    private Long cpuCostNs = 0L;

    @SerializedName(value = "cpuCost")
    private String cpuCost = "";

    @SerializedName(value = "memCostBytes")
    private Long memCostBytes = 0L;

    @SerializedName(value = "memCost")
    private String memCost = "";

    @SerializedName(value = "queryTimeMs")
    private Long queryTimeMs = 0L;

    @SerializedName(value = "queryTime")
    private String queryTime = "";

    @SerializedName(value = "returnedRows")
    private Long returnedRows = 0L;

    @SerializedName(value = "scanBytes")
    private Long scanBytes = 0L;

    @SerializedName(value = "scanRows")
    private Long scanRows = 0L;

    public Long getCpuCostNs() {
        return cpuCostNs;
    }

    public void setCpuCostNs(Long cpuCostNs) {
        this.cpuCostNs = cpuCostNs;
    }

    public Long getMemCostBytes() {
        return memCostBytes;
    }

    public void setMemCostBytes(Long memCostBytes) {
        this.memCostBytes = memCostBytes;
    }

    public Long getReturnedRows() {
        return returnedRows;
    }

    public void setReturnedRows(Long returnedRows) {
        this.returnedRows = returnedRows;
    }

    public Long getScanBytes() {
        return scanBytes;
    }

    public void setScanBytes(Long scanBytes) {
        this.scanBytes = scanBytes;
    }

    public Long getScanRows() {
        return scanRows;
    }

    public void setScanRows(Long scanRows) {
        this.scanRows = scanRows;
    }

    public String getCpuCost() {
        return cpuCost;
    }

    public void setCpuCost(String cpuCost) {
        this.cpuCost = cpuCost;
    }

    public String getMemCost() {
        return memCost;
    }

    public void setMemCost(String memCost) {
        this.memCost = memCost;
    }

    public Long getQueryTimeMs() {
        return queryTimeMs;
    }

    public void setQueryTimeMs(Long queryTimeMs) {
        this.queryTimeMs = queryTimeMs;
    }

    public String getQueryTime() {
        return queryTime;
    }

    public void setQueryTime(String queryTime) {
        this.queryTime = queryTime;
    }


    private static String formatCpuTime(long cpuCostNs) {
        double seconds = cpuCostNs / 1_000_000_000.0;
        return String.format("%.2f core", seconds);
    }

    private static String formatMemoryUsage(long memCostBytes) {
        if (memCostBytes < 1024) {
            return String.format("%d B", memCostBytes);
        } else if (memCostBytes < 1024 * 1024) {
            return String.format("%.2f KB", memCostBytes / 1024.0);
        } else if (memCostBytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", memCostBytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", memCostBytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private static String formatQueryTime(Long queryTime) {
        double seconds = queryTime / 1_000.0;
        return String.format("%.2f s", seconds);
    }

    public static String initContent(PQueryStatistics statistics, Long startTime) {
        long cpuCostNs = statistics != null && statistics.cpuCostNs != null ? statistics.cpuCostNs : 0L;
        long memCostBytes = statistics != null && statistics.memCostBytes != null ? statistics.memCostBytes : 0L;
        long returnedRows = statistics != null && statistics.returnedRows != null ? statistics.returnedRows : 0L;
        long scanBytes = statistics != null && statistics.scanBytes != null ? statistics.scanBytes : 0L;
        long scanRows = statistics != null && statistics.scanRows != null ? statistics.scanRows : 0L;
        long queryTime = (System.currentTimeMillis() - startTime);


        // Format to human readable
        String cpuTimeStr = formatCpuTime(cpuCostNs);
        String memUsageStr = formatMemoryUsage(memCostBytes);
        String queryTimeStr = formatQueryTime(queryTime);

        // Build JSON result string
        SqlResourceDetail sqlResourceDetail = new SqlResourceDetail();
        sqlResourceDetail.setCpuCostNs(cpuCostNs);
        sqlResourceDetail.setCpuCost(cpuTimeStr);
        sqlResourceDetail.setMemCostBytes(memCostBytes);
        sqlResourceDetail.setMemCost(memUsageStr);
        sqlResourceDetail.setQueryTimeMs(queryTime);
        sqlResourceDetail.setQueryTime(queryTimeStr);
        sqlResourceDetail.setReturnedRows(returnedRows);
        sqlResourceDetail.setScanBytes(scanBytes);
        sqlResourceDetail.setScanRows(scanRows);

        return GsonUtils.GSON.newBuilder().disableHtmlEscaping()
                .create().toJson(sqlResourceDetail);
    }


}
