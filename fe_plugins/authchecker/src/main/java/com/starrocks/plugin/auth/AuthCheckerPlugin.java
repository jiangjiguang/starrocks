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

package com.starrocks.plugin.auth;

import com.google.common.base.Stopwatch;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.plugin.AuthPlugin;
import com.starrocks.plugin.Plugin;
import com.starrocks.plugin.PluginContext;
import com.starrocks.plugin.PluginException;
import com.starrocks.plugin.PluginInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ShortCircuitPlanner;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.UpdateStmt;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.Optimizer;
import com.starrocks.sql.optimizer.OptimizerConfig;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.transformer.LogicalPlan;
import com.starrocks.sql.optimizer.transformer.RelationTransformer;
import com.starrocks.sql.optimizer.transformer.TransformerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.starrocks.sql.optimizer.rule.RuleType.TF_PARTITION_PRUNE;
import static com.starrocks.sql.optimizer.rule.RuleType.TF_PRUNE_EMPTY_SCAN;

/*
 * This plugin will check query authorization before
 */
public class AuthCheckerPlugin extends Plugin implements AuthPlugin {
    private final static Logger LOG = LogManager.getLogger(AuthCheckerPlugin.class);

    private AuthCheckerConf conf;
    private RemoteAuthChecker checker;
    private volatile boolean isInit = false;

    @Override
    public void init(PluginInfo info, PluginContext ctx) throws PluginException {
        super.init(info, ctx);

        synchronized (this) {
            if (isInit) {
                return;
            }
            loadConfig(ctx, info.getProperties());

            checker = new RemoteAuthChecker(conf);
            checker.open();
            isInit = true;
        }
    }

    private void loadConfig(PluginContext ctx, Map<String, String> pluginInfoProperties) throws PluginException {
        Path pluginPath = FileSystems.getDefault().getPath(ctx.getPluginPath());
        if (!Files.exists(pluginPath)) {
            throw new PluginException("plugin path does not exist: " + pluginPath);
        }

        Path confFile = pluginPath.resolve("plugin.conf");
        if (!Files.exists(confFile)) {
            throw new PluginException("plugin conf file does not exist: " + confFile);
        }

        final Properties props = new Properties();
        try (InputStream stream = Files.newInputStream(confFile)) {
            props.load(stream);
        } catch (IOException e) {
            throw new PluginException(e.getMessage());
        }

        for (Map.Entry<String, String> entry : pluginInfoProperties.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }

        final Map<String, String> properties = props.stringPropertyNames().stream()
                .collect(Collectors.toMap(Function.identity(), props::getProperty));
        conf = new AuthCheckerConf();
        conf.init(properties);

    }

    @Override
    public void close() throws IOException {
        checker.close();
        super.close();
    }

    @Override
    public ErrorReportException checkAuth(StatementBase stmt, ConnectContext connectContext) {
        QueryStatement queryStmt;
        if (stmt instanceof InsertStmt) {
            InsertStmt insertStmt = (InsertStmt) stmt;
            queryStmt = insertStmt.getQueryStatement();
        } else if (stmt instanceof QueryStatement) {
            queryStmt = (QueryStatement) stmt;
        } else {
            return null;
        }
        SessionVariable sessionVariable = connectContext.getSessionVariable();
        if (sessionVariable.getCatalog().equals(InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME)) {
            return null;
        }

        if (!sessionVariable.isEnablePrestoTranslation()) {
            return null;
        }
        String resourceGroup = sessionVariable.getResourceGroup();
        if (resourceGroup == null) {
            return ErrorReportException.report(ErrorCode.ERR_COMMON_ERROR,
                    "external hive catalog query must set `resource_group` hint as account.");
        }

        Stopwatch analyzeTime = Stopwatch.createStarted();
        ScanExprCollector.Context context = new ScanExprCollector.Context();
        context.setAccount(resourceGroup);

        AuthAnalyzer authAnalyzer = new AuthAnalyzer(connectContext, context);
        authAnalyzer.analyze(queryStmt);
        analyzeTime.stop();

        Stopwatch optimizeTime = Stopwatch.createStarted();
        QueryRelation query = queryStmt.getQueryRelation();
        // 1. Build Logical plan
        ColumnRefFactory columnRefFactory = new ColumnRefFactory();
        LogicalPlan logicalPlan;


        // get a logicalPlan without inlining views
        TransformerContext transformerContext = new TransformerContext(columnRefFactory, connectContext, null);
        logicalPlan = new RelationTransformer(transformerContext).transformWithSelectLimit(query);

        OptExpression root = ShortCircuitPlanner.checkSupportShortCircuitRead(logicalPlan.getRoot(), connectContext);

        OptExpression optimizedPlan;
        // 2. Optimize logical plan and build physical plan
        OptimizerConfig config = new OptimizerConfig(OptimizerConfig.OptimizerAlgorithm.RULE_BASED);
        config.disableRule(TF_PARTITION_PRUNE);
        config.disableRule(TF_PRUNE_EMPTY_SCAN);
        Optimizer optimizer = new Optimizer(config);
        optimizedPlan = optimizer.optimize(
                connectContext,
                root,
                null,
                queryStmt,
                new PhysicalPropertySet(),
                new ColumnRefSet(logicalPlan.getOutputColumn()),
                columnRefFactory);
        ScanExprCollector exprCollector = new ScanExprCollector();
        optimizedPlan.getOp().accept(exprCollector, optimizedPlan, context);
        optimizeTime.stop();

        Stopwatch rpcTime = Stopwatch.createStarted();
        ErrorReportException check = checker.check(context, connectContext.getQueryId());
        rpcTime.stop();

        LOG.info("check query auth, query_id={}, result={}, time: analyze={} ms, optimize={} ms, rpc={} ms",
                connectContext.getQueryId(),
                check == null,
                analyzeTime.elapsed().toMillis(),
                optimizeTime.elapsed().toMillis(),
                rpcTime.elapsed().toMillis());
        return check;
    }

    public static class AuthCheckerConf {
        public static final String AUTH_SERVER_HOST = "auth_server_host";
        public static final String AUTH_SERVER_PORT = "auth_server_port";
        public static final String TIMEOUT = "timeout";
        public static final String MAX_TOTAL = "max_total";
        public static final String MAX_IDLE = "max_idle";
        public static final String MAX_RETRIES = "max_retries";
        public static final String RETRY_INTERVAL = "retry_interval";
        public static final String APP_ID = "app_id";

        private String host;
        private int port;
        private int timeout;
        private int maxTotal;
        private int maxIdle;
        private int maxRetries;
        private int retryInterval;
        private String appId;

        public void init(Map<String, String> properties) throws PluginException {
            try {
                if (properties.containsKey(AUTH_SERVER_HOST)) {
                    host = properties.get(AUTH_SERVER_HOST);
                }
                if (properties.containsKey(AUTH_SERVER_PORT)) {
                    port = Integer.parseInt(properties.get(AUTH_SERVER_PORT));
                }
                if (properties.containsKey(TIMEOUT)) {
                    timeout = Integer.parseInt(properties.get(TIMEOUT));
                }
                if (properties.containsKey(MAX_TOTAL)) {
                    maxTotal = Integer.parseInt(properties.get(MAX_TOTAL));
                }
                if (properties.containsKey(MAX_IDLE)) {
                    maxIdle = Integer.parseInt(properties.get(MAX_IDLE));
                }
                if (properties.containsKey(MAX_RETRIES)) {
                    maxRetries = Integer.parseInt(properties.get(MAX_RETRIES));
                }
                if (properties.containsKey(RETRY_INTERVAL)) {
                    retryInterval = Integer.parseInt(properties.get(RETRY_INTERVAL));
                }
                if (properties.containsKey(APP_ID)) {
                    appId = properties.get(APP_ID);
                }
            } catch (Exception e) {
                throw new PluginException(e.getMessage());
            }
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public int getTimeout() {
            return timeout;
        }

        public int getMaxTotal() {
            return maxTotal;
        }

        public int getMaxIdle() {
            return maxIdle;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public int getRetryInterval() {
            return retryInterval;
        }

        public String getAppId() {
            return appId;
        }
    }

}
