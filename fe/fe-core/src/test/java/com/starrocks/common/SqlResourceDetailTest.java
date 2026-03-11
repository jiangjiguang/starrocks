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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starrocks.proto.PQueryStatistics;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test for SqlResourceDetail
 */
public class SqlResourceDetailTest {

    @Test
    public void testInitContentWithNullStatistics() {
        Long startTime = System.currentTimeMillis() - 1000; // 1 second ago

        String result = SqlResourceDetail.initContent(null, startTime);

        // Verify JSON is valid
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();

        Assert.assertEquals(0, json.get("cpuCostNs").getAsLong());
        Assert.assertEquals("0.00 core", json.get("cpuCost").getAsString());
        Assert.assertEquals(0, json.get("memCostBytes").getAsLong());
        Assert.assertEquals("0 B", json.get("memCost").getAsString());
        Assert.assertTrue(json.get("queryTimeMs").getAsLong() >= 1000);
        Assert.assertEquals(0, json.get("returnedRows").getAsLong());
        Assert.assertEquals(0, json.get("scanBytes").getAsLong());
        Assert.assertEquals(0, json.get("scanRows").getAsLong());
    }

    @Test
    public void testInitContentWithFullStatistics() {
        Long startTime = System.currentTimeMillis() - 5000; // 5 seconds ago

        PQueryStatistics statistics = new PQueryStatistics();
        statistics.setCpuCostNs(1_500_000_000L); // 1.5 seconds
        statistics.setMemCostBytes(256 * 1024 * 1024L); // 256 MB
        statistics.setReturnedRows(1000L);
        statistics.setScanBytes(1024 * 1024 * 100L); // 100 MB
        statistics.setScanRows(5000L);

        String result = SqlResourceDetail.initContent(statistics, startTime);

        // Verify JSON is valid
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();

        Assert.assertEquals(1_500_000_000L, json.get("cpuCostNs").getAsLong());
        Assert.assertEquals("1.50 core", json.get("cpuCost").getAsString());
        Assert.assertEquals(256 * 1024 * 1024L, json.get("memCostBytes").getAsLong());
        Assert.assertEquals("256.00 MB", json.get("memCost").getAsString());
        Assert.assertTrue(json.get("queryTimeMs").getAsLong() >= 5000);
        Assert.assertEquals(1000L, json.get("returnedRows").getAsLong());
        Assert.assertEquals(1024 * 1024 * 100L, json.get("scanBytes").getAsLong());
        Assert.assertEquals(5000L, json.get("scanRows").getAsLong());
    }

    @Test
    public void testInitContentWithPartialStatistics() {
        Long startTime = System.currentTimeMillis() - 2000; // 2 seconds ago

        PQueryStatistics statistics = new PQueryStatistics();
        statistics.setCpuCostNs(500_000_000L); // 0.5 seconds
        // Only set cpuCostNs, other fields are null

        String result = SqlResourceDetail.initContent(statistics, startTime);

        // Verify JSON is valid
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();

        Assert.assertEquals(500_000_000L, json.get("cpuCostNs").getAsLong());
        Assert.assertEquals("0.50 core", json.get("cpuCost").getAsString());
        Assert.assertEquals(0, json.get("memCostBytes").getAsLong());
        Assert.assertEquals("0 B", json.get("memCost").getAsString());
    }

    @Test
    public void testFormatCpuTime() {
        // Test various CPU time formats
        Long startTime = System.currentTimeMillis();

        PQueryStatistics statistics = new PQueryStatistics();

        // Test with nanoseconds < 1 second
        statistics.setCpuCostNs(500_000_000L);
        String result = SqlResourceDetail.initContent(statistics, startTime);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        Assert.assertEquals("0.50 core", json.get("cpuCost").getAsString());

        // Test with seconds
        statistics.setCpuCostNs(3_000_000_000L);
        result = SqlResourceDetail.initContent(statistics, startTime);
        json = JsonParser.parseString(result).getAsJsonObject();
        Assert.assertEquals("3.00 core", json.get("cpuCost").getAsString());
    }

    @Test
    public void testFormatMemoryUsage() {
        Long startTime = System.currentTimeMillis();
        PQueryStatistics statistics = new PQueryStatistics();

        // Test bytes
        statistics.setMemCostBytes(512L);
        String result = SqlResourceDetail.initContent(statistics, startTime);
        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        Assert.assertEquals("512 B", json.get("memCost").getAsString());

        // Test KB
        statistics.setMemCostBytes(3 * 1024L);
        result = SqlResourceDetail.initContent(statistics, startTime);
        json = JsonParser.parseString(result).getAsJsonObject();
        Assert.assertEquals("3.00 KB", json.get("memCost").getAsString());

        // Test MB
        statistics.setMemCostBytes(128 * 1024 * 1024L);
        result = SqlResourceDetail.initContent(statistics, startTime);
        json = JsonParser.parseString(result).getAsJsonObject();
        Assert.assertEquals("128.00 MB", json.get("memCost").getAsString());

        // Test GB
        statistics.setMemCostBytes(2L * 1024 * 1024 * 1024L);
        result = SqlResourceDetail.initContent(statistics, startTime);
        json = JsonParser.parseString(result).getAsJsonObject();
        Assert.assertEquals("2.00 GB", json.get("memCost").getAsString());
    }

    @Test
    public void testQueryTimeCalculation() {
        long startTime = System.currentTimeMillis() - 3000; // Started 3 seconds ago

        PQueryStatistics statistics = new PQueryStatistics();
        String result = SqlResourceDetail.initContent(statistics, startTime);

        JsonObject json = JsonParser.parseString(result).getAsJsonObject();
        // Query time should be approximately 3 seconds (allow some tolerance)
        String queryTime = json.get("queryTime").getAsString();
        Assert.assertTrue(queryTime.matches("\\d+\\.\\d+ s"));
    }

    @Test
    public void testJsonFormat() {
        Long startTime = System.currentTimeMillis();

        PQueryStatistics statistics = new PQueryStatistics();
        statistics.setCpuCostNs(1_000_000_000L);
        statistics.setMemCostBytes(1024 * 1024L);
        statistics.setReturnedRows(10L);
        statistics.setScanBytes(512 * 1024L);
        statistics.setScanRows(100L);

        String result = SqlResourceDetail.initContent(statistics, startTime);

        // Verify the result is valid JSON
        Gson gson = new Gson();
        SqlResourceDetail detail = gson.fromJson(result, SqlResourceDetail.class);

        Assert.assertNotNull(detail);
        Assert.assertEquals(1_000_000_000L, detail.getCpuCostNs().longValue());
        Assert.assertEquals(1024 * 1024L, detail.getMemCostBytes().longValue());
        Assert.assertEquals(10L, detail.getReturnedRows().longValue());
        Assert.assertEquals(512 * 1024L, detail.getScanBytes().longValue());
        Assert.assertEquals(100L, detail.getScanRows().longValue());
    }
}
