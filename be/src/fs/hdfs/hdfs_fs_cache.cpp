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

#include "fs/hdfs/hdfs_fs_cache.h"

#include <memory>

#include "common/config.h"
#include "gutil/strings/substitute.h"
#include "udf/java/java_udf.h"
#include "util/hdfs_util.h"
#include "util/starrocks_metrics.h"

namespace starrocks {

// Try to get cloud properties from FSOptions, if cloud configuration not existed, return nullptr.
// TODO(SmithCruise): Should remove when using cpp sdk
static const std::map<std::string, std::string> get_cloud_properties(const FSOptions& options) {
    const TCloudConfiguration* cloud_configuration = nullptr;
    if (options.cloud_configuration != nullptr) {
        // This branch is used by data lake
        cloud_configuration = options.cloud_configuration;
    } else if (options.hdfs_properties() != nullptr && options.hdfs_properties()->__isset.cloud_configuration) {
        // This branch is used by broker load
        cloud_configuration = &options.hdfs_properties()->cloud_configuration;
    }
    if (cloud_configuration != nullptr && cloud_configuration->__isset.cloud_properties) {
        return cloud_configuration->cloud_properties;
    }
    return {};
}

static Status create_hdfs_fs_handle(const std::string& namenode, const std::shared_ptr<HdfsFsClient>& hdfs_client,
                                    const FSOptions& options) {
    // record start time
    auto start_time = std::chrono::steady_clock::now();

    RETURN_IF_ERROR(detect_java_runtime());
    auto hdfs_builder = hdfsNewBuilder();
    hdfsBuilderSetNameNode(hdfs_builder, namenode.c_str());
    const THdfsProperties* properties = options.hdfs_properties();
    if (properties != nullptr) {
        if (properties->__isset.hdfs_username) {
            hdfsBuilderSetUserName(hdfs_builder, properties->hdfs_username.data());
        }
        if (properties->__isset.disable_cache && properties->disable_cache) {
            hdfsBuilderSetForceNewInstance(hdfs_builder);
        }
    }

    // Insert cloud properties(key-value paired) into Hadoop configuration
    // TODO(SmithCruise): Should remove when using cpp sdk
    const std::map<std::string, std::string> cloud_properties = get_cloud_properties(options);

    bool username_set = false;
    bool password_set = false;
    if (!cloud_properties.empty()) {
        // special set hadoop.username into hdfs_builder
        if (auto it = cloud_properties.find("hadoop.username"); it != cloud_properties.end()) {
            if (!it->second.empty()) {
                LOG(INFO) << "get hdfs name from cloud_properties " << it->second;
                hdfsBuilderSetUserName(hdfs_builder, it->second.c_str());
                username_set = true;
            } else {
                LOG(INFO) << "Empty hadoop.username found in cloud_properties";
            }
        }

        // special set hadoop.password into hdfs_builder
        if (auto it = cloud_properties.find("hadoop.password"); it != cloud_properties.end()) {
            if (!it->second.empty()) {
                LOG(INFO) << "get hdfs password from cloud_properties " << it->second;
                hdfsBuilderSetPassword(hdfs_builder, it->second.c_str());
                password_set = true;
            } else {
                LOG(INFO) << "Empty hadoop.password found in cloud_properties";
            }
        }

        for (const auto& cloud_property : cloud_properties) {
            hdfsBuilderConfSetStr(hdfs_builder, cloud_property.first.data(), cloud_property.second.data());
        }
    }

    if (!username_set) {
        const char* env_username = std::getenv("HADOOP_USER_NAME");
        if (env_username != nullptr) {
            LOG(INFO) << "get hdfs name from env " << env_username;
            hdfsBuilderSetUserName(hdfs_builder, env_username);
        } else {
            LOG(INFO) << "get empty hdfs name from env, datanode connection maybe fail, please set env HADOOP_USER_NAME";
        }
    }

    if (!password_set) {
        const char* env_password = std::getenv("HADOOP_USER_PASSWORD");
        if (env_password != nullptr) {
            LOG(INFO) << "get hdfs password from env " << env_password;
            hdfsBuilderSetPassword(hdfs_builder, env_password);
        } else {
            LOG(INFO) << "get empty hdfs password from env, datanode connection maybe fail, please set env HADOOP_USER_PASSWORD";
        }
    }

    // Set for hdfs client hedged read
    std::string hedged_read_threadpool_size = std::to_string(config::hdfs_client_hedged_read_threadpool_size);
    std::string hedged_read_threshold_millis = std::to_string(config::hdfs_client_hedged_read_threshold_millis);
    if (config::hdfs_client_enable_hedged_read) {
        hdfsBuilderConfSetStr(hdfs_builder, "dfs.client.hedged.read.threadpool.size",
                              hedged_read_threadpool_size.data());
        hdfsBuilderConfSetStr(hdfs_builder, "dfs.client.hedged.read.threshold.millis",
                              hedged_read_threshold_millis.data());
    }

    bool enable_report_hdfs_metrics = config::enable_report_hdfs_metrics;

    hdfs_client->hdfs_fs = hdfsBuilderConnect(hdfs_builder);
    if (hdfs_client->hdfs_fs == nullptr) {
        if (enable_report_hdfs_metrics) {
            StarRocksMetrics::instance()->hdfs_namenode_connect_error_total.increment(1);
        }
        return Status::InternalError(strings::Substitute("fail to connect hdfs namenode, namenode=$0, err=$1", namenode,
                                                         get_hdfs_err_msg()));
    }

    // record end time and calculate latency
    if (enable_report_hdfs_metrics) {
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start_time);
        StarRocksMetrics::instance()->hdfs_namenode_connect_latency.update(duration.count());
    }

    return Status::OK();
}

Status HdfsFsCache::get_connection(const std::string& namenode, std::shared_ptr<HdfsFsClient>& hdfs_client,
                                   const FSOptions& options) {
    std::string cache_key = namenode;
    const THdfsProperties* properties = options.hdfs_properties();
    if (properties != nullptr && properties->__isset.hdfs_username) {
        cache_key += properties->hdfs_username;
    }

    // Insert cloud properties into cache key
    const std::map<std::string, std::string> cloud_properties = get_cloud_properties(options);
    if (!cloud_properties.empty()) {
        for (const auto& cloud_property : cloud_properties) {
            if (!cloud_property.first.empty() && !cloud_property.second.empty()) {
                cache_key += cloud_property.first;
                cache_key += cloud_property.second;
            }
        }
    }

    LOG(INFO) << "current datanode cache key is: " << cache_key;

    std::lock_guard<std::mutex> l(_lock);

    auto it = _cache_clients.find(cache_key);
    if (it != _cache_clients.end()) {
        LOG(INFO) << "get datanode client by cache key is: " << cache_key;
        hdfs_client = it->second;
        // Found a cache client, return directly
        return Status::OK();
    }

    LOG(INFO) << "didn't get datanode client by cache key is: " << cache_key;

    const uint32_t max_cache_clients = config::hdfs_client_max_cache_size;
    // Not found a cached client, create a new one
    hdfs_client = std::make_shared<HdfsFsClient>();
    hdfs_client->namenode = namenode;
    RETURN_IF_ERROR(create_hdfs_fs_handle(namenode, hdfs_client, options));
    if (UNLIKELY(_cache_keys.size() >= max_cache_clients)) {
        uint32_t idx = _rand.Uniform(max_cache_clients);
        _cache_clients.erase(_cache_keys[idx]);
        _cache_clients[cache_key] = hdfs_client;
        _cache_keys[idx].swap(cache_key);
    } else {
        _cache_clients[cache_key] = hdfs_client;
        _cache_keys.push_back(std::move(cache_key));
    }
    return Status::OK();
}

} // namespace starrocks
