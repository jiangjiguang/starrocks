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
//   https://github.com/apache/incubator-doris/blob/master/be/test/util/doris_metrics_test.cpp

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

#include "util/starrocks_metrics.h"

#include <gtest/gtest.h>

#include "common/config.h"
#include "runtime/mem_tracker.h"
#include "storage/page_cache.h"
#include "util/logging.h"

namespace starrocks {
class HistogramHdfsMetricsTest : public testing::Test {
    protected:
    void SetUp() override {
        // standard bucket setting
        std::vector<double> buckets = {10, 50, 100, 500, 1000};
        metric = std::make_unique<HistogramMetric>(MetricUnit::MICROSECONDS, buckets);
    }

    std::unique_ptr<HistogramMetric> metric;
};

TEST_F(HistogramHdfsMetricsTest, InitialState) {
    auto snap = metric->precise_snapshot();

    EXPECT_EQ(snap.sum, 0);
    EXPECT_EQ(snap.count, 0);
    EXPECT_EQ(snap.buckets.size(), 6); // 5 + infinity
    EXPECT_EQ(snap.bucket_counts.size(), 6);

    for (auto count : snap.bucket_counts) {
        EXPECT_EQ(count, 0);
    }
}

TEST_F(HistogramHdfsMetricsTest, SingleValueBelowFirstBucket) {
    metric->update(5.0);
    auto snap = metric->precise_snapshot();

    EXPECT_EQ(snap.sum, 5.0);
    EXPECT_EQ(snap.count, 1);

    // All buckets should be counted +1 (cumulative)
    for (auto count : snap.bucket_counts) {
        EXPECT_EQ(count, 1);
    }
}

TEST_F(HistogramHdfsMetricsTest, SingleValueInMiddleBucket) {
    metric->update(75.0); // It should be between 50 and 100 bucket
    auto snap = metric->precise_snapshot();

    EXPECT_EQ(snap.sum, 75.0);
    EXPECT_EQ(snap.count, 1);

    // Check the cumulative count
    EXPECT_EQ(snap.bucket_counts[0], 0); // <10
    EXPECT_EQ(snap.bucket_counts[1], 1); // >=10
    EXPECT_EQ(snap.bucket_counts[2], 1); // >=50
    EXPECT_EQ(snap.bucket_counts[3], 0); // >=100 (75 < 100)
    EXPECT_EQ(snap.bucket_counts[4], 0); // >=500
    EXPECT_EQ(snap.bucket_counts[5], 0); // >=1000
}

TEST_F(HistogramHdfsMetricsTest, SingleValueAboveMaxBucket) {
    metric->update(1500.0); // Exceed the maximum defined bucket
    auto snap = metric->precise_snapshot();

    EXPECT_EQ(snap.sum, 1500.0);
    EXPECT_EQ(snap.count, 1);

    // Only the +Inf bucket should have a count
    for (size_t i = 0; i < snap.bucket_counts.size() - 1; ++i) {
        EXPECT_EQ(snap.bucket_counts[i], 0);
    }
    EXPECT_EQ(snap.bucket_counts.back(), 1); // +Inf
}

TEST_F(HistogramHdfsMetricsTest, MultipleValues) {
    metric->update(5.0);
    metric->update(75.0);
    metric->update(200.0);
    metric->update(1500.0);

    auto snap = metric->precise_snapshot();

    EXPECT_EQ(snap.sum, 5.0 + 75.0 + 200.0 + 1500.0);
    EXPECT_EQ(snap.count, 4);

    // Check the cumulative count of each bucket
    EXPECT_EQ(snap.bucket_counts[0], 1); // >=10 (only 5.0)
    EXPECT_EQ(snap.bucket_counts[1], 3); // >=50 (75, 200, 1500)
    EXPECT_EQ(snap.bucket_counts[2], 2); // >=100 (200, 1500)
    EXPECT_EQ(snap.bucket_counts[3], 1); // >=500 (1500)
    EXPECT_EQ(snap.bucket_counts[4], 0); // >=1000
    EXPECT_EQ(snap.bucket_counts[5], 1); // +Inf (1500)
}

TEST_F(HistogramHdfsMetricsTest, ThreadSafety) {
    const int num_threads = 4;
    const int updates_per_thread = 10000;

    std::vector<std::thread> threads;
    for (int i = 0; i < num_threads; ++i) {
        threads.emplace_back([this, i]() {
            for (int j = 0; j < updates_per_thread; ++j) {
                double value = 10 + (i * 20) + (j % 50);
                metric->update(value);
            }
        });
    }

    for (auto& thread : threads) {
        thread.join();
    }

    auto snap = metric->precise_snapshot();
    EXPECT_EQ(snap.count, num_threads * updates_per_thread);

    // Check whether the total sum is reasonable
    double expected_min = num_threads * updates_per_thread * 10;
    double expected_max = num_threads * updates_per_thread * (10 + 60 + 49);
    EXPECT_GT(snap.sum, expected_min);
    EXPECT_LT(snap.sum, expected_max);
}

TEST_F(HistogramHdfsMetricsTest, BucketPreparation) {
    // The preparation logic of the test bucket
    std::vector<double> buckets = {100, 50, 50, 200, 1000, 500};
    HistogramMetric m(MetricUnit::MICROSECONDS, buckets);

    auto snap = m.precise_snapshot();

    // Check whether the buckets have been sorted and de-duplicated
    EXPECT_EQ(snap.buckets.size(), 6); // 5 + infinity
    EXPECT_EQ(snap.buckets[0], 50);
    EXPECT_EQ(snap.buckets[1], 100);
    EXPECT_EQ(snap.buckets[2], 200);
    EXPECT_EQ(snap.buckets[3], 500);
    EXPECT_EQ(snap.buckets[4], 1000);
    EXPECT_TRUE(std::isinf(snap.buckets[5])); // +Inf
}

TEST_F(HistogramHdfsMetricsTest, ToString) {
    metric->update(75.0);
    metric->update(200.0);

    std::string str = metric->to_string();
    EXPECT_NE(str.find("sum:275"), std::string::npos);
    EXPECT_NE(str.find("count:2"), std::string::npos);
    EXPECT_NE(str.find("buckets:["), std::string::npos);
}

TEST_F(HistogramHdfsMetricsTest, WriteValue) {
    metric->update(75.0);

    rapidjson::Document doc;
    doc.SetObject();
    metric->write_value(doc, doc.GetAllocator());

    EXPECT_TRUE(doc.HasMember("sum"));
    EXPECT_TRUE(doc.HasMember("count"));
    EXPECT_TRUE(doc.HasMember("buckets"));

    EXPECT_EQ(doc["sum"].GetDouble(), 75.0);
    EXPECT_EQ(doc["count"].GetInt64(), 1);
    EXPECT_TRUE(doc["buckets"].IsArray());
}

// RelaxedAtomicCounter test
TEST(RelaxedAtomicCounterTest, BasicOperations) {
    RelaxedAtomicCounter counter;

    EXPECT_EQ(counter.sum(), 0);

    counter.add(5);
    EXPECT_EQ(counter.sum(), 5);

    counter.add(10);
    EXPECT_EQ(counter.sum(), 15);

    RelaxedAtomicCounter moved = std::move(counter);
    EXPECT_EQ(moved.sum(), 15);
};




} // namespace starrocks