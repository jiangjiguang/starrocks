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

#include "exec/pipeline/sink/connector_sink_operator.h"

#include <gmock/gmock.h>
#include <gtest/gtest-param-test.h>
#include <gtest/gtest.h>

#include <atomic>
#include <future>
#include <thread>

#include "connector/connector_chunk_sink.h"
#include "connector/hive_chunk_sink.h"
#include "exec/pipeline/pipeline_driver_executor.h"
#include "exec/pipeline/query_context.h"
#include "exec/workgroup/pipeline_executor_set.h"
#include "exec/workgroup/work_group.h"
#include "formats/utils.h"
#include "testutil/assert.h"
#include "util/defer_op.h"

namespace starrocks::pipeline {
namespace {

using CommitResult = formats::FileWriter::CommitResult;

class MockDriverExecutor final : public DriverExecutor {
public:
    explicit MockDriverExecutor(std::atomic<int64_t>* report_count)
        : DriverExecutor("mock"), _report_count(report_count) {}
    void submit(DriverRawPtr driver) override {}
    void cancel(DriverRawPtr driver) override {}
    void close() override {}
    void report_audit_statistics(QueryContext* query_ctx, FragmentContext* fragment_ctx) override {
        _report_count->fetch_add(1, std::memory_order_relaxed);
    }
    void report_exec_state(QueryContext* query_ctx, FragmentContext* fragment_ctx, const Status& status, bool done, bool attach_profile) override {}
    void bind_cpus(const CpuUtil::CpuIds& cpuids, const std::vector<CpuUtil::CpuIds>& borrowed_cpuids) override {}
    void iterate_immutable_blocking_driver(const ConstDriverConsumer& call) const override {}
    void report_epoch(ExecEnv* exec_env, QueryContext* query_ctx, std::vector<FragmentContext*> fragment_ctxs) override {}
    size_t calculate_parked_driver(const ConstDriverPredicator& predicate_func) const override { return 0; }
    size_t activate_parked_driver(const ConstDriverPredicator& predicate_func) override { return 0; }
    DriverExecutorMetrics metrics() const override { return{}; }
private:
    std::atomic<int64_t>* _report_count;
};

class ConnectorSinkOperatorTest : public ::testing::Test {
protected:
    void SetUp() override {
        _fragment_context = _pool.add(new FragmentContext);
        _fragment_context->set_runtime_state(std::make_shared<RuntimeState>());
        _runtime_state = _fragment_context->runtime_state();
        _query_ctx = std::make_shared<QueryContext>();
        _runtime_state->set_query_ctx(_query_ctx.get());
        _runtime_state->set_fragment_ctx(_fragment_context);
    }

    void TearDown() override {}

    ObjectPool _pool;
    FragmentContext* _fragment_context;
    RuntimeState* _runtime_state;
    std::shared_ptr<QueryContext> _query_ctx;

    std::unique_ptr<ConnectorSinkOperatorFactory> create_hive_sink_operator_factory() {
        auto provider = std::make_unique<connector::HiveChunkSinkProvider>();
        auto sink_ctx = std::make_shared<connector::HiveChunkSinkContext>();
        sink_ctx->path = "/path/to/directory/";
        sink_ctx->data_column_names = {"k1"};
        sink_ctx->partition_column_names = {"k2"};
        sink_ctx->data_column_evaluators =
                ColumnSlotIdEvaluator::from_types({TypeDescriptor::from_logical_type(TYPE_VARCHAR)});
        sink_ctx->partition_column_evaluators =
                ColumnSlotIdEvaluator::from_types({TypeDescriptor::from_logical_type(TYPE_INT)});
        sink_ctx->executor = nullptr;
        sink_ctx->format = formats::PARQUET;
        sink_ctx->compression_type = TCompressionType::NO_COMPRESSION;
        sink_ctx->options = {}; // default for now
        sink_ctx->max_file_size = 1 << 30;
        sink_ctx->fragment_context = _fragment_context;
        return std::make_unique<ConnectorSinkOperatorFactory>(0, std::move(provider), sink_ctx, _fragment_context);
    }
};

TEST_F(ConnectorSinkOperatorTest, test_factory) {
    {
        auto op_factory = create_hive_sink_operator_factory();
        auto op = op_factory->create(1, 0);
        EXPECT_OK(op->prepare(_runtime_state));
    }
}

TEST_F(ConnectorSinkOperatorTest, test_report_aduit_statistic_after_all_sinkers_finished) {
    std::atomic<int64_t> report_count = 0;
    workgroup::PipelineExecutorSetConfig executor_set_config(1, 1, 1, 1, {}, false, false);
    workgroup::PipelineExecutorSet executor_set(executor_set_config, "mock", {}, {});
    executor_set._driver_executor = std::make_unique<MockDriverExecutor>(&report_count);
    auto wg = std::make_shared<workgroup::WorkGroup>("mock_wg", 0, 0, 1, 1.0, 0, 1.0,
                                                     workgroup::WorkGroupType::WG_NORMAL);
    wg->set_shared_executors(&executor_set);
    _fragment_context->set_workgroup(wg);

    auto op_factory = create_hive_sink_operator_factory();
    auto op0 = op_factory->create(3, 0);
    auto op1 = op_factory->create(3, 1);
    auto op2 = op_factory->create(3, 2);

    EXPECT_OK(op0->set_finishing(_runtime_state));
    EXPECT_EQ(0, report_count.load());

    EXPECT_OK(op1->set_finishing(_runtime_state));
    EXPECT_EQ(0, report_count.load());

    EXPECT_OK(op2->set_finishing(_runtime_state));
    EXPECT_EQ(1, report_count.load());
}

} // namespace
} // namespace starrocks::pipeline
