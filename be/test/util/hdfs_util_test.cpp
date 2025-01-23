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


#include <gtest/gtest.h>
#include "util/hdfs_util.h"

namespace starrocks {


TEST(HdfsUtilsTest, GetNamenodeFromPath) {
    std::string namenode;

    // test har:// path
    std::string har_path = "har://hdfs-DClusterNmg4/user/dj_dw/htw_dw/har/file.har";
    Status status = get_namenode_from_path(har_path, &namenode);
    EXPECT_TRUE(status.ok());
    EXPECT_EQ(namenode, har_path);

    // test hdfs:// path
    std::string hdfs_path = "hdfs://DClusterNmg4/path/to/file";
    status = get_namenode_from_path(hdfs_path, &namenode);
    EXPECT_TRUE(status.ok());
    EXPECT_EQ(namenode, "hdfs://DClusterNmg4/");

    // test file:// path
    std::string local_path = "file:/path/to/file";
    status = get_namenode_from_path(local_path, &namenode);
    EXPECT_TRUE(status.ok());
    EXPECT_EQ(namenode, "file:///");

    // test not specified scheme path
    std::string unqualified_path = "/path/to/file";
    status = get_namenode_from_path(unqualified_path, &namenode);
    EXPECT_TRUE(status.ok());
    EXPECT_EQ(namenode, "default");

    // test invalid path（without scheme）
    std::string invalid_path = "://invalid/path";
    status = get_namenode_from_path(invalid_path, &namenode);
    EXPECT_FALSE(status.ok());
    EXPECT_EQ(status.to_string(), "Invalid argument: Path missing scheme: ://invalid/path");
}

} // namespace starrocks