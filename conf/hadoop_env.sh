# Copyright 2021-present StarRocks, Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https:#www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

for f in ${STARROCKS_HOME}/lib/hadoop/common/*.jar ${STARROCKS_HOME}/lib/hadoop/common/lib/*.jar ${STARROCKS_HOME}/lib/hadoop/hdfs/*.jar ${STARROCKS_HOME}/lib/hadoop/hdfs/lib/*.jar; do
  HADOOP_CLASSPATH=$f:${HADOOP_CLASSPATH};
done
export HADOOP_CLASSPATH=$HADOOP_CLASSPATH

if [ ${HADOOP_USER_NAME}"X" != "X" ]; then
    export HADOOP_USER_NAME=${USER}
fi

# the purpose is to use local hadoop configuration first.
# under HADOOP_CONF_DIR(eg. /etc/ecm/hadoop-conf), there are hadoop/hdfs/hbase conf files.
# and by putting HADOOP_CONF_DIR at front of HADOOP_CLASSPATH, local hadoop conf file will be searched & used first.

# local hadoop configuration is usually well-tailored and optimized, we'd better to leverage that.
# for example, if local hdfs has enabled short-circuit read, then we can use short-circuit read and save io time

if [ ${HADOOP_CONF_DIR}"X" != "X" ]; then
    export HADOOP_CLASSPATH=${HADOOP_CONF_DIR}:${HADOOP_CLASSPATH}
fi
