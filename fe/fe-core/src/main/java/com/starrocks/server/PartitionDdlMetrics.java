// Copyright 2021-present StarRocks, Inc. All rights reserved.

package com.starrocks.server;

import com.starrocks.metric.GaugeMetricImpl;
import com.starrocks.metric.LongCounterMetric;
import com.starrocks.metric.Metric.MetricUnit;
import com.starrocks.metric.MetricLabel;
import com.starrocks.metric.MetricRepo;
import com.starrocks.metric.MetricWithLabelGroup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Metrics for partition DDL operations. */
public final class PartitionDdlMetrics {
    public enum Operation {
        ADD_PARTITION, DROP_PARTITION, REPLACE_PARTITION
    }

    private static final Map<Operation, AtomicLong> RUNNING_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> DB_RUNNING_COUNTS = new ConcurrentHashMap<>();

    private static final MetricWithLabelGroup<LongCounterMetric> SUBMITTED = counterGroup(
            "partition_ddl_submitted", "partition DDL requests submitted");
    private static final MetricWithLabelGroup<LongCounterMetric> STARTED = counterGroup(
            "partition_ddl_started", "partition DDL requests started");
    private static final MetricWithLabelGroup<LongCounterMetric> FINISHED = counterGroup(
            "partition_ddl_finished", "partition DDL requests finished");
    private static final MetricWithLabelGroup<LongCounterMetric> FAILED = counterGroup(
            "partition_ddl_failed", "partition DDL requests failed");

    private static final MetricWithLabelGroup<GaugeMetricImpl<Long>> RUNNING = gaugeGroup(
            "partition_ddl_running", "running partition DDL requests");
    private static final MetricWithLabelGroup<GaugeMetricImpl<Long>> LAST_LATENCY = gaugeGroup(
            "partition_ddl_last_latency_ms", "last partition DDL latency in milliseconds");
    private static final Map<String, LongCounterMetric> DB_SUBMITTED = new ConcurrentHashMap<>();
    private static final Map<String, LongCounterMetric> DB_STARTED = new ConcurrentHashMap<>();
    private static final Map<String, LongCounterMetric> DB_FINISHED = new ConcurrentHashMap<>();
    private static final Map<String, LongCounterMetric> DB_FAILED = new ConcurrentHashMap<>();
    private static final Map<String, GaugeMetricImpl<Long>> DB_RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, GaugeMetricImpl<Long>> DB_LAST_LATENCY = new ConcurrentHashMap<>();

    private PartitionDdlMetrics() {
    }

    public static synchronized void initialize() {
        if (!RUNNING_COUNTS.isEmpty()) {
            return;
        }
        for (Operation operation : Operation.values()) {
            RUNNING_COUNTS.put(operation, new AtomicLong());
        }
    }

    public static Permit begin(long dbId, String dbName, Operation operation) {
        initialize();
        String operationLabel = operation.name();
        increase(SUBMITTED, operationLabel, DB_SUBMITTED, dbId, dbName, "partition_ddl_submitted");
        markStarted(operationLabel, operation, dbId, dbName);
        return new Permit(dbId, dbName, operation, operationLabel, System.nanoTime());
    }

    private static void markStarted(String operationLabel, Operation operation, long dbId, String dbName) {
        RUNNING_COUNTS.get(operation).incrementAndGet();
        dbCount(DB_RUNNING_COUNTS, dbId, dbName, operation).incrementAndGet();
        increase(STARTED, operationLabel, DB_STARTED, dbId, dbName, "partition_ddl_started");
        updateGauge(RUNNING, operationLabel, RUNNING_COUNTS.get(operation).get());
        updateDbGauge(DB_RUNNING, dbId, dbName, operation,
                dbCount(DB_RUNNING_COUNTS, dbId, dbName, operation).get(),
                "partition_ddl_running");
    }

    private static AtomicLong dbCount(Map<String, AtomicLong> counts, long dbId, String dbName, Operation operation) {
        return counts.computeIfAbsent(dbId + ":" + dbName + ":" + operation.name(), ignored -> new AtomicLong());
    }

    private static MetricWithLabelGroup<LongCounterMetric> counterGroup(String name, String description) {
        return new MetricWithLabelGroup<>("operation", () ->
                new LongCounterMetric(name, MetricUnit.REQUESTS, description));
    }

    private static MetricWithLabelGroup<GaugeMetricImpl<Long>> gaugeGroup(String name, String description) {
        return new MetricWithLabelGroup<>("operation", () ->
                new GaugeMetricImpl<>(name, MetricUnit.NOUNIT, description));
    }

    private static void increase(MetricWithLabelGroup<LongCounterMetric> group, String label) {
        if (MetricRepo.hasInit) {
            group.getMetric(label).increase(1L);
        }
    }

    private static void increase(MetricWithLabelGroup<LongCounterMetric> group, String operationLabel,
                                 Map<String, LongCounterMetric> dbMetrics, long dbId, String dbName, String name) {
        increase(group, operationLabel);
        if (MetricRepo.hasInit) {
            dbMetric(dbMetrics, name, dbId, dbName, operationLabel).increase(1L);
        }
    }

    private static void updateGauge(MetricWithLabelGroup<GaugeMetricImpl<Long>> group, String label, long value) {
        if (MetricRepo.hasInit) {
            group.getMetric(label).setValue(value);
        }
    }

    private static void updateDbGauge(Map<String, GaugeMetricImpl<Long>> dbMetrics, long dbId, String dbName,
                                      Operation operation, long value, String name) {
        if (MetricRepo.hasInit) {
            dbMetric(dbMetrics, name, dbId, dbName, operation.name()).setValue(value);
        }
    }

    private static <T> T dbMetric(Map<String, T> metrics, String name, long dbId, String dbName, String operation) {
        String key = name + ":" + dbId + ":" + dbName + ":" + operation;
        return metrics.computeIfAbsent(key, ignored -> {
            T metric;
            if (metrics == DB_RUNNING || metrics == DB_LAST_LATENCY) {
                metric = (T) new GaugeMetricImpl<Long>(name, MetricUnit.NOUNIT,
                        "partition DDL metric by database");
            } else {
                metric = (T) new LongCounterMetric(name, MetricUnit.REQUESTS,
                        "partition DDL metric by database");
            }
            ((com.starrocks.metric.Metric<?>) metric).addLabel(new MetricLabel("db_name", dbName));
            ((com.starrocks.metric.Metric<?>) metric).addLabel(new MetricLabel("operation", operation));
            MetricRepo.addMetric((com.starrocks.metric.Metric<?>) metric);
            return metric;
        });
    }

    public static final class Permit implements AutoCloseable {
        private final String operationLabel;
        private final long dbId;
        private final String dbName;
        private final Operation operation;
        private final long startNanos;
        private boolean closed;

        private Permit(long dbId, String dbName, Operation operation, String operationLabel, long startNanos) {
            this.dbId = dbId;
            this.dbName = dbName;
            this.operation = operation;
            this.operationLabel = operationLabel;
            this.startNanos = startNanos;
        }

        public void close(boolean success) {
            if (closed) {
                return;
            }
            closed = true;
            long running = RUNNING_COUNTS.get(operation).decrementAndGet();
            long dbRunning = dbCount(DB_RUNNING_COUNTS, dbId, dbName, operation).decrementAndGet();
            updateGauge(RUNNING, operationLabel, Math.max(0L, running));
            updateDbGauge(DB_RUNNING, dbId, dbName, operation, Math.max(0L, dbRunning), "partition_ddl_running");
            long latencyMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            if (MetricRepo.hasInit) {
                LAST_LATENCY.getMetric(operationLabel).setValue(latencyMs);
                updateDbGauge(DB_LAST_LATENCY, dbId, dbName, operation, latencyMs, "partition_ddl_last_latency_ms");
            }
            increase(success ? FINISHED : FAILED, operationLabel,
                    success ? DB_FINISHED : DB_FAILED, dbId, dbName,
                    success ? "partition_ddl_finished" : "partition_ddl_failed");
        }

        @Override
        public void close() {
            close(false);
        }
    }
}
