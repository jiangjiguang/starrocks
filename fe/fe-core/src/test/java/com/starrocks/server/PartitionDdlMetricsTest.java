// Copyright 2021-present StarRocks, Inc. All rights reserved.

package com.starrocks.server;

import com.starrocks.common.FeConstants;
import com.starrocks.metric.GaugeMetricImpl;
import com.starrocks.metric.LongCounterMetric;
import com.starrocks.metric.Metric;
import com.starrocks.metric.MetricLabel;
import com.starrocks.metric.MetricRepo;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PartitionDdlMetricsTest {
    @BeforeClass
    public static void setUp() {
        FeConstants.runningUnitTest = true;
        MetricRepo.init();
    }

    @Test
    public void testPartitionDdlLifecycleAndOperationLabel() {
        PartitionDdlMetrics.Permit warmup = PartitionDdlMetrics.begin(230224L, "test_db",
                PartitionDdlMetrics.Operation.ADD_PARTITION);
        warmup.close(true);
        LongCounterMetric submitted = counter("partition_ddl_submitted", "ADD_PARTITION");
        LongCounterMetric started = counter("partition_ddl_started", "ADD_PARTITION");
        LongCounterMetric finished = counter("partition_ddl_finished", "ADD_PARTITION");
        long submittedBefore = submitted.getValue();
        long startedBefore = started.getValue();
        long finishedBefore = finished.getValue();

        PartitionDdlMetrics.Permit permit = PartitionDdlMetrics.begin(230224L, "test_db",
                PartitionDdlMetrics.Operation.ADD_PARTITION);
        assertEquals(Long.valueOf(1L), gauge("partition_ddl_running", "ADD_PARTITION").getValue());
        permit.close(true);
        permit.close(false);

        assertEquals(submittedBefore + 1L, submitted.getValue().longValue());
        assertEquals(startedBefore + 1L, started.getValue().longValue());
        assertEquals(finishedBefore + 1L, finished.getValue().longValue());
        assertEquals(Long.valueOf(0L), gauge("partition_ddl_running", "ADD_PARTITION").getValue());
        assertTrue(gauge("partition_ddl_last_latency_ms", "ADD_PARTITION").getValue() >= 0L);
    }

    @Test
    public void testFailedOperationIsCountedByDatabaseAndOperation() {
        PartitionDdlMetrics.Permit warmup = PartitionDdlMetrics.begin(230224L, "test_db",
                PartitionDdlMetrics.Operation.DROP_PARTITION);
        warmup.close(false);
        LongCounterMetric failed = dbCounter("partition_ddl_failed", "test_db", "DROP_PARTITION");
        long failedBefore = failed.getValue();

        PartitionDdlMetrics.Permit permit = PartitionDdlMetrics.begin(230224L, "test_db",
                PartitionDdlMetrics.Operation.DROP_PARTITION);
        permit.close(false);

        assertEquals(failedBefore + 1L, failed.getValue().longValue());
        assertNotNull(dbGauge("partition_ddl_running", "test_db", "DROP_PARTITION"));
        assertEquals(Long.valueOf(0L), dbGauge("partition_ddl_running", "test_db", "DROP_PARTITION").getValue());
    }

    private static LongCounterMetric counter(String name, String operation) {
        List<Metric> metrics = MetricRepo.getMetricsByName(name);
        return (LongCounterMetric) metrics.stream()
                .filter(metric -> hasLabel(metric, "operation", operation))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static LongCounterMetric dbCounter(String name, String dbName, String operation) {
        return (LongCounterMetric) dbMetric(name, dbName, operation);
    }

    private static GaugeMetricImpl<Long> gauge(String name, String operation) {
        return (GaugeMetricImpl<Long>) MetricRepo.getMetricsByName(name).stream()
                .filter(metric -> hasLabel(metric, "operation", operation))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static GaugeMetricImpl<Long> dbGauge(String name, String dbName, String operation) {
        return (GaugeMetricImpl<Long>) dbMetric(name, dbName, operation);
    }

    private static Metric dbMetric(String name, String dbName, String operation) {
        return MetricRepo.getMetricsByName(name).stream()
                .filter(metric -> hasLabel(metric, "db_name", dbName))
                .filter(metric -> hasLabel(metric, "operation", operation))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static boolean hasLabel(Metric<?> metric, String key, String value) {
        for (MetricLabel label : metric.getLabels()) {
            if (key.equals(label.getKey()) && value.equals(label.getValue())) {
                return true;
            }
        }
        return false;
    }
}
