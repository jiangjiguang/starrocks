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

package com.starrocks.qe;

import com.starrocks.common.ErrorReportException;
import com.starrocks.plugin.AuthPlugin;
import com.starrocks.plugin.Plugin;
import com.starrocks.plugin.PluginInfo.PluginType;
import com.starrocks.plugin.PluginMgr;
import com.starrocks.sql.ast.StatementBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;

public class AuthCheckerProcessor {
    private static final Logger LOG = LogManager.getLogger(AuthCheckerProcessor.class);
    private static final long UPDATE_PLUGIN_INTERVAL_MS = 60L * 1000L; // 1min

    private final PluginMgr pluginMgr;

    private List<Plugin> authPlugins;
    private long lastUpdateTime = 0;

    private Thread workerThread;

    private volatile boolean isStopped = false;

    public AuthCheckerProcessor(PluginMgr pluginMgr) {
        this.pluginMgr = pluginMgr;
    }

    public void start() {
        workerThread = new Thread(new Worker(), "AuthCheckerProcessor");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void stop() {
        isStopped = true;
        if (workerThread != null) {
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                LOG.warn("join worker join failed.", e);
            }
        }
    }

    public void authCheck(StatementBase stmt, ConnectContext session) {
        ErrorReportException authException = null;
        try {
            if (authPlugins != null) {
                for (Plugin plugin : authPlugins) {
                    authException = ((AuthPlugin) plugin).checkAuth(stmt, session);
                }
            }
        } catch (Throwable e) {
            UUID queryId = session.getQueryId();
            LOG.warn("encounter exception when handle auth check, queryId={}, skip auth check.", queryId.toString(), e);
        }
        if (authException != null) {
            throw authException;
        }
    }

    public class Worker implements Runnable {
        @Override
        public void run() {
            while (!isStopped) {
                // update audit plugin list every UPDATE_PLUGIN_INTERVAL_MS.
                // because some of plugins may be installed or uninstalled at runtime.
                if (authPlugins == null || System.currentTimeMillis() - lastUpdateTime > UPDATE_PLUGIN_INTERVAL_MS) {
                    authPlugins = pluginMgr.getActivePluginList(PluginType.AUTH);
                    lastUpdateTime = System.currentTimeMillis();
                    LOG.debug("update auth plugins. num: {}", authPlugins.size());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }

            }
        }

    }
}
