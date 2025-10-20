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
//   https://github.com/apache/incubator-doris/blob/master/be/src/util/metrics.h

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

#pragma once

#include <gperftools/malloc_extension.h>
#include <rapidjson/document.h>
#include <rapidjson/rapidjson.h>

#include <atomic>
#include <functional>
#include <iomanip>
#include <mutex>
#include <ostream>
#include <set>
#include <shared_mutex>
#include <sstream>
#include <string>
#include <utility>

#include "common/compiler_util.h"
#include "common/config.h"
#include "util/core_local.h"
#include "util/spinlock.h"

namespace starrocks {

namespace rj = RAPIDJSON_NAMESPACE;

class MetricRegistry;

enum class MetricType { COUNTER, GAUGE, HISTOGRAM, SUMMARY, UNTYPED };

enum class MetricUnit {
    NANOSECONDS,
    MICROSECONDS,
    MILLISECONDS,
    SECONDS,
    BYTES,
    ROWS,
    PERCENT,
    REQUESTS,
    OPERATIONS,
    BLOCKS,
    ROWSETS,
    CONNECTIONS,
    PACKETS,
    NOUNIT
};

std::ostream& operator<<(std::ostream& os, MetricType type);
const char* unit_name(MetricUnit unit);

class Metric {
public:
    Metric(MetricType type, MetricUnit unit) : _type(type), _unit(unit) {}
    virtual ~Metric() noexcept { hide(); }
    virtual std::string to_string() const = 0;
    MetricType type() const { return _type; }
    MetricUnit unit() const { return _unit; }
    void hide();
    virtual void write_value(rj::Value& metric_obj, rj::Document::AllocatorType& allocator) = 0;

private:
    friend class MetricRegistry;

    MetricType _type = MetricType::UNTYPED;
    MetricUnit _unit = MetricUnit::NOUNIT;
    MetricRegistry* _registry{nullptr};
};

// Metric that only can increment
template <typename T>
class AtomicMetric : public Metric {
public:
    AtomicMetric(MetricType type, MetricUnit unit) : Metric(type, unit), _value(T()) {}
    ~AtomicMetric() override = default;

    std::string to_string() const override { return std::to_string(value()); }

    void write_value(rj::Value& metric_obj, rj::Document::AllocatorType& allocator) override {
        metric_obj.AddMember("value", rj::Value(value()), allocator);
    }

    virtual T value() const { return _value.load(); }

    void increment(const T& delta) { _value.fetch_add(delta); }

    void set_value(const T& value) { _value.store(value); }

protected:
    std::atomic<T> _value;
};

template <typename T>
class LockSimpleMetric : public Metric {
public:
    LockSimpleMetric(MetricType type, MetricUnit unit) : Metric(type, unit), _value(T()) {}
    ~LockSimpleMetric() override = default;

    std::string to_string() const override { return std::to_string(value()); }

    void write_value(rj::Value& metric_obj, rj::Document::AllocatorType& allocator) override {
        metric_obj.AddMember("value", rj::Value(value()), allocator);
    }

    T value() const {
        std::lock_guard<SpinLock> l(_lock);
        return _value;
    }

    void increment(const T& delta) {
        std::lock_guard<SpinLock> l(this->_lock);
        this->_value += delta;
    }
    void set_value(const T& value) {
        std::lock_guard<SpinLock> l(this->_lock);
        this->_value = value;
    }

protected:
    // We use spinlock instead of std::atomic is because atomic don't support
    // double's fetch_add
    // TODO(zc): If this is atomic is bottleneck, we change to thread local.
    // performance: on Intel(R) Xeon(R) CPU E5-2450 int64_t
    //  original type: 2ns/op
    //  single thread spinlock: 26ns/op
    //  multiple thread(8) spinlock: 2500ns/op
    mutable SpinLock _lock;
    T _value;
};

template <typename T>
class CoreLocalCounter : public Metric {
public:
    CoreLocalCounter(MetricUnit unit) : Metric(MetricType::COUNTER, unit), _value() {}

    ~CoreLocalCounter() override = default;

    std::string to_string() const override {
        std::stringstream ss;
        ss << value();
        return ss.str();
    }

    void write_value(rj::Value& metric_obj, rj::Document::AllocatorType& allocator) override {
        metric_obj.AddMember("value", rj::Value(value()), allocator);
    }

    T value() const {
        T sum = 0;
        for (int i = 0; i < _value.size(); ++i) {
            sum += *_value.access_at_core(i);
        }
        return sum;
    }

    void increment(const T& delta) { __sync_fetch_and_add(_value.access(), delta); }

protected:
    CoreLocalValue<T> _value;
};

template <typename T>
class AtomicCounter : public AtomicMetric<T> {
public:
    AtomicCounter(MetricUnit unit) : AtomicMetric<T>(MetricType::COUNTER, unit) {}
    ~AtomicCounter() override = default;
};

template <typename T>
class AtomicGauge : public AtomicMetric<T> {
public:
    AtomicGauge(MetricUnit unit) : AtomicMetric<T>(MetricType::GAUGE, unit) {}
    ~AtomicGauge() override = default;
};

template <typename T>
class LockCounter : public LockSimpleMetric<T> {
public:
    LockCounter(MetricUnit unit) : LockSimpleMetric<T>(MetricType::COUNTER, unit) {}
    virtual ~LockCounter() = default;
};

// This can only used for trival type
template <typename T>
class LockGauge : public LockSimpleMetric<T> {
public:
    LockGauge(MetricUnit unit) : LockSimpleMetric<T>(MetricType::GAUGE, unit) {}
    virtual ~LockGauge() = default;
};

// one key-value pair used to
struct MetricLabel {
    std::string name;
    std::string value;

    MetricLabel() = default;
    MetricLabel(std::string name_, std::string value_) : name(std::move(name_)), value(std::move(value_)) {}

    bool operator == (const MetricLabel& other) const { return name == other.name && value == other.value; }
    bool operator != (const MetricLabel& other) const { return !(*this == other); }
    bool operator<(const MetricLabel& other) const {
        auto res = name.compare(other.name);
        if (res == 0) {
            return value < other.value;
        }
        return res < 0;
    }
    int compare(const MetricLabel& other) const {
        auto res = name.compare(other.name);
        if (res == 0) {
            return value.compare(other.value);
        }
        return res;
    }
    std::string to_string() const { return name + "=" + value; }
};

struct MetricLabels {
    static MetricLabels EmptyLabels;
    // used std::set to sort MetricLabel so that we can get compare two MetricLabels
    std::set<MetricLabel> labels;

    MetricLabels& add(const std::string& name, const std::string& value) {
        labels.emplace(name, value);
        return *this;
    }

    bool operator==(const MetricLabels& other) const {
        if (labels.size() != other.labels.size()) {
            return false;
        }
        auto it = labels.begin();
        auto other_it = other.labels.begin();
        while (it != labels.end()) {
            if (*it != *other_it) {
                return false;
            }
            ++it;
            ++other_it;
        }
        return true;
    }
    bool operator<(const MetricLabels& other) const {
        auto it = labels.begin();
        auto other_it = other.labels.begin();
        while (it != labels.end() && other_it != other.labels.end()) {
            auto res = it->compare(*other_it);
            if (res < 0) {
                return true;
            } else if (res > 0) {
                return false;
            }
            ++it;
            ++other_it;
        }
        if (it == labels.end()) {
            if (other_it == other.labels.end()) {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }
    bool empty() const { return labels.empty(); }

    std::string to_string() const {
        std::stringstream ss;
        int i = 0;
        for (auto& label : labels) {
            if (i++ > 0) {
                ss << ",";
            }
            ss << label.to_string();
        }
        return ss.str();
    }
};

class MetricCollector;

class MetricsVisitor {
public:
    virtual ~MetricsVisitor() = default;

    // visit a collector, you can implement collector visitor, or only implement
    // metric visitor
    virtual void visit(const std::string& prefix, const std::string& name, MetricCollector* collector) = 0;
};

class MetricCollector {
public:
    bool add_metric(const MetricLabels& labels, Metric* metric);
    void remove_metric(Metric* metric);
    void collect(const std::string& prefix, const std::string& name, MetricsVisitor* visitor) {
        visitor->visit(prefix, name, this);
    }
    bool empty() const { return _metrics.empty(); }
    Metric* get_metric(const MetricLabels& labels) const;
    // get all metrics belong to this collector
    void get_metrics(std::vector<Metric*>* metrics);

    const std::map<MetricLabels, Metric*>& metrics() const { return _metrics; }
    MetricType type() const { return _type; }

private:
    MetricType _type = MetricType::UNTYPED;
    std::map<MetricLabels, Metric*> _metrics;
};

class MetricRegistry {
public:
    MetricRegistry(std::string name) : _name(std::move(name)) {}
    ~MetricRegistry() noexcept;
    bool register_metric(const std::string& name, Metric* metric) {
        return register_metric(name, MetricLabels::EmptyLabels, metric);
    }
    bool register_metric(const std::string& name, const MetricLabels& labels, Metric* metric);
    // Now this function is not used frequently, so this is a little time consuming
    void deregister_metric(Metric* metric) {
        std::shared_lock lock(_collector_mutex);
        _deregister_locked(metric);
    }
    Metric* get_metric(const std::string& name) const { return get_metric(name, MetricLabels::EmptyLabels); }
    Metric* get_metric(const std::string& name, const MetricLabels& labels) const;

    // Register a hook, this hook will called before collect is called
    bool register_hook(const std::string& name, const std::function<void()>& hook);
    void deregister_hook(const std::string& name);

    void collect(MetricsVisitor* visitor) {
        if (!config::enable_metric_calculator) {
            // Before we collect, need to call hooks
            std::shared_lock lock(_hooks_mutex);
            unprotected_trigger_hook();
        }

        std::shared_lock lock(_collector_mutex);
        for (auto& it : _collectors) {
            it.second->collect(_name, it.first, visitor);
        }
    }

    void trigger_hook() {
        std::shared_lock lock(_hooks_mutex);
        unprotected_trigger_hook();
    }

private:
    void unprotected_trigger_hook() {
        for (const auto& it : _hooks) {
            it.second();
        }
    }

private:
    void _deregister_locked(Metric* metric);

    const std::string _name;

    // mutable SpinLock _lock;
    mutable std::shared_mutex _collector_mutex;
    mutable std::shared_mutex _hooks_mutex;
    std::map<std::string, MetricCollector*> _collectors;
    std::map<std::string, std::function<void()>> _hooks;
};


struct HistogramSnapshot {
    double sum = 0;
    int64_t count = 0;
    std::vector<double> buckets;
    std::vector<int64_t> bucket_counts;
};


struct alignas(64) RelaxedAtomicCounter {
    std::atomic<int64_t> count{0};

    // Default constructor
    RelaxedAtomicCounter() = default;

    // Move constructor
    RelaxedAtomicCounter(RelaxedAtomicCounter&& other) noexcept {
        count.store(other.count.load(std::memory_order_relaxed), std::memory_order_relaxed);
    }

    // Move assignment
    RelaxedAtomicCounter& operator=(RelaxedAtomicCounter&& other) noexcept {
        count.store(other.count.load(std::memory_order_relaxed), std::memory_order_relaxed);
        return *this;
    }

    void add(int64_t n) noexcept {
        count.store(count.load(std::memory_order_relaxed) + n,
                   std::memory_order_relaxed);
    }

    int64_t sum() const noexcept {
        return count.load(std::memory_order_acquire);
    }
};

class HistogramMetric : public Metric {
public:
    HistogramMetric(MetricUnit unit, std::vector<double> buckets)
        : Metric(MetricType::HISTOGRAM, unit),
          _sum(0),
          _count(0),
          _max_bucket(0)
    {
        prepare_buckets(buckets);
        if (_buckets.size() >= 2) {
            _max_bucket = _buckets[_buckets.size() - 2];
        }
    }

    void update(double value) noexcept {
        _sum.fetch_add(value, std::memory_order_relaxed);
        _count.fetch_add(1, std::memory_order_relaxed);

        if (value > _max_bucket) {
            _bucket_counts.back().add(1);
            return;
        }

        auto it = std::lower_bound(_buckets.begin(), _buckets.end() - 1, value);
        size_t index = std::distance(_buckets.begin(), it);

        for (size_t i = index; i < _buckets.size(); ++i) {
            _bucket_counts[i].add(1);
        }
    }

    HistogramSnapshot precise_snapshot() const {
        HistogramSnapshot snap;

        // 1. Atomic loading base values
        snap.sum = _sum.load(std::memory_order_acquire);
        snap.count = _count.load(std::memory_order_acquire);

        // 2. Check the validity of the bucket definition
        if (_buckets.empty() || _bucket_counts.size() != _buckets.size()) {
            return snap;  // Return an empty snapshot
        }

        // 3. Copy bucket definition (const operation, no need for synchronization)
        snap.buckets = _buckets;
        snap.bucket_counts.resize(_buckets.size());

        // 4. Cumulative counting method (compliant with Prometheus specification)
        // The first bucket is its own count
        snap.bucket_counts[0] = _bucket_counts[0].sum();

        // The subsequent buckets are cumulative counts
        for (size_t i = 1; i < _buckets.size(); ++i) {
            //  sum() is directly used instead of the difference to maintain the cumulative property
            snap.bucket_counts[i] = _bucket_counts[i].sum();
        }

        return snap;
    }

    std::string to_string() const override {
        auto snap = precise_snapshot();
        std::ostringstream ss;
        ss << "sum:" << snap.sum << " count:" << snap.count << " buckets:[";
        for (size_t i = 0; i < snap.buckets.size(); ++i) {
            if (i > 0) ss << ",";
            ss << snap.buckets[i] << ":" << snap.bucket_counts[i];
        }
        ss << "]";
        return ss.str();
    }

    void write_value(rj::Value& metric_obj, rj::Document::AllocatorType& allocator) override {
        auto snap = precise_snapshot();
        metric_obj.AddMember("sum", snap.sum, allocator);
        metric_obj.AddMember("count", snap.count, allocator);

        rj::Value buckets(rj::kArrayType);
        for (size_t i = 0; i < snap.buckets.size(); ++i) {
            rj::Value bucket(rj::kObjectType);
            bucket.AddMember("le", snap.buckets[i], allocator);
            bucket.AddMember("count", snap.bucket_counts[i], allocator);
            buckets.PushBack(bucket, allocator);
        }
        metric_obj.AddMember("buckets", buckets, allocator);
    }

    void debug_print() {
        auto snap = precise_snapshot();
        LOG(INFO) << "Histogram State:";
        LOG(INFO) << "  Sum: " << snap.sum;
        LOG(INFO) << "  Count: " << snap.count;
        for (size_t i = 0; i < snap.buckets.size(); ++i) {
            LOG(INFO) << "  Bucket " << snap.buckets[i] << ": " << snap.bucket_counts[i];
        }
    }

private:
    void prepare_buckets(std::vector<double>& buckets) {
        std::sort(buckets.begin(), buckets.end());
        auto last = std::unique(buckets.begin(), buckets.end());
        buckets.erase(last, buckets.end());

        if (buckets.empty() || !std::isinf(buckets.back())) {
            buckets.push_back(std::numeric_limits<double>::infinity());
        }

        _buckets = std::move(buckets);
        _bucket_counts.resize(_buckets.size());
    }

    std::vector<double> _buckets;
    std::vector<RelaxedAtomicCounter> _bucket_counts;
    std::atomic<double> _sum;
    std::atomic<int64_t> _count;
    double _max_bucket;
};



using IntCounter = CoreLocalCounter<int64_t>;
using IntAtomicCounter = AtomicCounter<int64_t>;
using UIntCounter = CoreLocalCounter<uint64_t>;
using DoubleCounter = LockCounter<double>;
using IntGauge = AtomicGauge<int64_t>;
using UIntGauge = AtomicGauge<uint64_t>;
using DoubleGauge = LockGauge<double>;

} // namespace starrocks

// Convenience macros to metric
#define METRIC_DEFINE_INT_COUNTER(metric_name, unit) \
    starrocks::IntCounter metric_name { unit }

#define METRIC_DEFINE_INT_ATOMIC_COUNTER(metric_name, unit) \
    starrocks::IntAtomicCounter metric_name { unit }

#define METRIC_DEFINE_UINT_COUNTER(metric_name, unit) \
    starrocks::UIntCounter metric_name { unit }

#define METRIC_DEFINE_DOUBLE_COUNTER(metric_name, unit) \
    starrocks::DoubleCounter metric_name { unit }

#define METRIC_DEFINE_INT_GAUGE(metric_name, unit) \
    starrocks::IntGauge metric_name { unit }

#define METRIC_DEFINE_UINT_GAUGE(metric_name, unit) \
    starrocks::UIntGauge metric_name { unit }

#define METRIC_DEFINE_DOUBLE_GAUGE(metric_name, unit) \
    starrocks::DoubleGauge metric_name { unit }

// Histogram metric declaration macro
#define METRIC_DEFINE_HISTOGRAM(metric_name, unit, ...) \
    starrocks::HistogramMetric metric_name { \
        unit, \
        std::vector<double>{__VA_ARGS__} \
    }
