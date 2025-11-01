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

package com.starrocks.common.util;

import com.starrocks.analysis.Expr;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.Table;
import com.starrocks.connector.HdfsEnvironment;
import com.starrocks.connector.hive.HiveMetaClient;
import com.starrocks.connector.hive.HiveMetastoreApiConverter;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationFactory;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.PartitionNames;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HiveMetaStoreUtils {
    private static final Logger LOG = LogManager.getLogger(HiveMetaStoreUtils.class);

    public static void addPartition(Table targetTable, InsertStmt insertStmt) {
        try {
            long startTime = System.nanoTime();
            // 1. convert table type
            HiveTable hiveTable = (HiveTable) targetTable;

            // 2. get hive metastore client
            HiveMetaClient hiveMetaClient = getHiveMetaClient(hiveTable);

            // 3. get hms table detail by hms
            org.apache.hadoop.hive.metastore.api.Table hmsTableDetail = hiveMetaClient.getTable(hiveTable.getDbName(), hiveTable.getTableName());

            // 4. get partition values
            PartitionNames partitionNames = insertStmt.getTargetPartitionNames();

            // 4. init partition info
            List<Partition> partitionList = new ArrayList<>();
            for (int i = 0; i < partitionNames.getPartitionColValues().size(); i++) {
                Expr expr = partitionNames.getPartitionColValues().get(i);
                String partitionColumnName = partitionNames.getPartitionColNames().get(i);
                if (!(expr instanceof StringLiteral)) {
                    continue;
                }
                String partitionColumnValue = ((StringLiteral) expr).getValue();
                // check partition if exists
                List<String> checkValueList = new ArrayList<>();
                checkValueList.add(partitionColumnValue);
                if (partitionExists(hiveMetaClient, hiveTable.getDbName(), hiveTable.getTableName(), checkValueList)) {
                    continue;
                }
                // init partition detail
                Partition partition = new Partition();
                partition.setDbName(hiveTable.getDbName());
                partition.setTableName(hiveTable.getTableName());
                partition.setValues(checkValueList);
                Map<String, String> parameters = HiveMetastoreApiConverter.toApiTableProperties(hiveTable);
                partition.setParameters(parameters);
                // Set the storage descriptor
                StorageDescriptor sd = initStorageDescriptor(hmsTableDetail, partitionColumnName, partitionColumnValue);
                partition.setSd(sd);
                // add partition to list
                partitionList.add(partition);
            }

            if (partitionList.isEmpty()) {
                return;
            }

            // add partition by hms client
            hiveMetaClient.addPartitions(hiveTable.getDbName(), hiveTable.getTableName(), partitionList);

            long endTime = System.nanoTime();
            long elapsed = endTime - startTime;
            long elapsedMs = elapsed / 1_000_000;
            if (elapsedMs / 1000 > 1) {
                LOG.info("add hive table [{}.{}] partition elapse [{}] ms",
                        hiveTable.getDbName(), hiveTable.getTableName(), elapsedMs);
            }
        } catch (Exception e) {
            LOG.warn("add partition in insert overwrite sql fail", e);
        }
    }

    /**
     *
     * @param hiveTable HiveTable
     * @return HiveMetaClient
     */
    private static HiveMetaClient getHiveMetaClient(HiveTable hiveTable) {
        Catalog catalog = GlobalStateMgr.getCurrentState().getCatalogMgr().getCatalogByName(hiveTable.getCatalogName());
        CloudConfiguration cloudConfiguration = CloudConfigurationFactory.buildCloudConfigurationForStorage(catalog.getConfig());
        HdfsEnvironment hdfsEnvironment = new HdfsEnvironment(cloudConfiguration);
        HiveConf hiveConf = HiveMetaClient.initConfig(hdfsEnvironment, catalog.getConfig());
        return new HiveMetaClient(hiveConf);
    }

    /**
     * check partition exists
     * @param client HiveMetaClient
     * @param dbName String
     * @param tableName String
     * @param partitionValues List<String>
     * @return boolean
     */
    private static boolean partitionExists(HiveMetaClient client,
                                    String dbName,
                                    String tableName,
                                    List<String> partitionValues) {
        try {
            Partition partition = client.getPartition(dbName, tableName, partitionValues);
            return partition != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     *
     * @param hmsTableDetail org.apache.hadoop.hive.metastore.api.Table
     * @param partitionColumnName String
     * @param partitionColumnValue String
     * @return StorageDescriptor
     */
    private static StorageDescriptor initStorageDescriptor(org.apache.hadoop.hive.metastore.api.Table hmsTableDetail,
                                                           String partitionColumnName,
                                                           String partitionColumnValue) {
        StorageDescriptor sd = new StorageDescriptor();
        sd.setCols(hmsTableDetail.getSd().getCols());
        sd.setLocation(hmsTableDetail.getSd().getLocation() + "/" + partitionColumnName + "=" + partitionColumnValue);
        sd.setInputFormat(hmsTableDetail.getSd().getInputFormat());
        sd.setOutputFormat(hmsTableDetail.getSd().getOutputFormat());
        sd.setSerdeInfo(new SerDeInfo());
        sd.getSerdeInfo().setSerializationLib(hmsTableDetail.getSd().getSerdeInfo().getSerializationLib());
        sd.getSerdeInfo().setName(hmsTableDetail.getTableName());
        return sd;
    }
}
