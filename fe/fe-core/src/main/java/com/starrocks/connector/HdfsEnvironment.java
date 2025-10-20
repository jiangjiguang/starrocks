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

package com.starrocks.connector;

import com.starrocks.catalog.Catalog;
import com.starrocks.connector.hadoop.HadoopExt;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationFactory;
import com.starrocks.server.GlobalStateMgr;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class HdfsEnvironment {
    private static final Logger LOG = LogManager.getLogger(HdfsEnvironment.class);
    private final Configuration hdfsConfiguration;
    private CloudConfiguration cloudConfiguration;
    private static final ConcurrentMap<String, UserGroupInformation> UGI_CACHE = new ConcurrentHashMap<>();

    /**
     * Create default Hdfs environment
     */
    public HdfsEnvironment() {
        hdfsConfiguration = new Configuration();
        cloudConfiguration = CloudConfigurationFactory.buildCloudConfigurationForStorage(new HashMap<>());
    }

    public HdfsEnvironment(Map<String, String> properties) {
        this();
        if (properties != null) {
            CloudConfiguration cc = CloudConfigurationFactory.buildCloudConfigurationForStorage(properties);
            this.cloudConfiguration = cc;
            cc.applyToConfiguration(hdfsConfiguration);
        }
    }

    public HdfsEnvironment(CloudConfiguration cc) {
        this();
        if (cloudConfiguration != null) {
            this.cloudConfiguration = cc;
            cc.applyToConfiguration(hdfsConfiguration);
        }
    }

    public Configuration getConfiguration() {
        return hdfsConfiguration;
    }

    public CloudConfiguration getCloudConfiguration() {
        return cloudConfiguration;
    }

    public static UserGroupInformation getUserGroupInformation(Configuration configuration) {
        try {
            String username = configuration.get(HadoopExt.HADOOP_USERNAME);
            String password = configuration.get(HadoopExt.HADOOP_PASSWORD);
            if (!StringUtils.isBlank(username) && !StringUtils.isBlank(password)) {
                LOG.info("Hadoop user is set in configuration, user: [{}], password:[{}]",
                        username, password);
                return getUgiByCache(username, password);
            }
            username = System.getenv(HadoopExt.SYSTEM_ENVIRONMENT_HADOOP_USERNAME);
            password = System.getenv(HadoopExt.SYSTEM_ENVIRONMENT_HADOOP_PASSWORD);
            if (!StringUtils.isBlank(username) && !StringUtils.isBlank(password)) {
                LOG.info("Hadoop user is set from environment, user: [{}], password:[{}]",
                        username, password);
                return getUgiByCache(username, password);
            }
            LOG.warn("Didn't set hadoop user in UserGroupInformation, FileSystem connection maybe fail");
            return getCurrentUserSafe();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get HDFS UserGroupInformation", e);
        }
    }

    private static UserGroupInformation getUgiByCache(String username, String password) {
        String cacheKey = username + "||" + password;

        return UGI_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                long startTime = System.nanoTime();
                UserGroupInformation ugi = UserGroupInformation.createRemoteUser(username, password);
                long endTime = System.nanoTime();
                long elapsed = endTime - startTime;
                long elapsedMs = elapsed / 1_000_000;
                if (elapsedMs / 1000 > 1) {
                    LOG.info("create remote user:[{}:{}] elapse [{}] ms", username, password, elapsedMs);
                }
                return ugi;
            } catch (Exception e) {
                throw new RuntimeException("Create UGI failed", e);
            }
        });
    }


    private static UserGroupInformation getCurrentUserSafe() {
        try {
            return UserGroupInformation.getCurrentUser();
        } catch (IOException e) {
            throw new RuntimeException("Get current UGI failed", e);
        }
    }

    public static FileSystem getFileSystem(Configuration configuration, URI uri) {
        try {
            UserGroupInformation ugi = getUserGroupInformation(configuration);
            return ugi.doAs((PrivilegedExceptionAction<FileSystem>) () ->
                    FileSystem.get(uri, configuration));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get HDFS FileSystem", e);
        }
    }

    public static UserGroupInformation getUserGroupInformation(String catalogName) {
        try {
            if (StringUtils.isBlank(catalogName)) {
                throw new RuntimeException("Catalog name cannot be null or empty");
            }
            Catalog catalog = GlobalStateMgr.getCurrentState().getCatalogMgr().getCatalogByName(catalogName);
            if (catalog == null) {
                throw new RuntimeException("Catalog not found: " + catalogName);
            }
            Map<String, String> properties = catalog.getConfig();
            if (properties == null || properties.isEmpty()) {
                throw new RuntimeException("Catalog properties are empty for catalog: " + catalogName);
            }
            CloudConfiguration cc = CloudConfigurationFactory.buildCloudConfigurationForStorage(properties);
            Configuration hdfsConfiguration = new Configuration();
            cc.applyToConfiguration(hdfsConfiguration);
            return getUserGroupInformation(hdfsConfiguration);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static UserGroupInformation getUserGroupInformation(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            throw new RuntimeException("properties are empty for Configuration");
        }
        CloudConfiguration cc = CloudConfigurationFactory.buildCloudConfigurationForStorage(properties);
        Configuration hdfsConfiguration = new Configuration();
        cc.applyToConfiguration(hdfsConfiguration);
        return getUserGroupInformation(hdfsConfiguration);
    }

}




