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

package com.starrocks.common;

import com.google.common.collect.Sets;
import com.starrocks.common.util.NetUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TNetworkAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThriftServer {
    private static final Logger LOG = LogManager.getLogger(ThriftServer.class);
    private int port;
    private TProcessor processor;
    private TServer server;
    private Thread serverThread;
    private Set<TNetworkAddress> connects;
    private static ThreadPoolExecutor executor;

    public ThriftServer(int port, TProcessor processor) {
        this.port = port;
        this.processor = processor;
        this.connects = Sets.newConcurrentHashSet();
    }

    private void createThreadPoolServer() throws TTransportException {
        executor = ThreadPoolManager.newDaemonCacheThreadPool(
                Config.thrift_server_min_worker_threads, Config.thrift_server_max_worker_threads,
                Config.thrift_server_queue_size, Config.thrift_server_worker_keep_alive_second,
                "thrift-server-pool", true);
        // allow core thread time out so that the thread can be released
        // if not used for ThreadPoolManager.KEEP_ALIVE_TIME
        executor.allowCoreThreadTimeOut(true);

        TBinaryProtocol.Factory factory = new TBinaryProtocol.Factory(Config.thrift_rpc_strict_mode, true,
                Config.thrift_rpc_max_body_size, -1);

        if (Config.thrift_server_nio_enabled) {
            TNonblockingServerSocket.NonblockingAbstractServerSocketArgs socketTransportArgs =
                    new TNonblockingServerSocket.NonblockingAbstractServerSocketArgs()
                            .bindAddr(NetUtils.getSockAddrBasedOnCurrIpVersion(port))
                            .clientTimeout(Config.thrift_client_timeout_ms)
                            .backlog(Config.thrift_backlog_num);

            SRTThreadedSelectorServer.Args serverArgs = new SRTThreadedSelectorServer.Args(
                    new TNonblockingServerSocket(socketTransportArgs)).protocolFactory(factory).executorService(executor)
                    .selectorThreads(Config.thrift_server_nio_selector_threads)
                    .processor(processor);

            server = new SRTThreadedSelectorServer(serverArgs);
        } else {
            TServerSocket.ServerSocketTransportArgs socketTransportArgs = new TServerSocket.ServerSocketTransportArgs().bindAddr(
                            NetUtils.getSockAddrBasedOnCurrIpVersion(port)).clientTimeout(Config.thrift_client_timeout_ms)
                    .backlog(Config.thrift_backlog_num);

            SRTThreadPoolServer.Args serverArgs = new SRTThreadPoolServer.Args(
                    new TServerSocket(socketTransportArgs)).protocolFactory(factory).executorService(executor)
                    .processor(processor);

            server = new SRTThreadPoolServer(serverArgs);
        }


        GlobalStateMgr.getCurrentState().getConfigRefreshDaemon().registerListener(() -> {
            if (executor.getCorePoolSize() != Config.thrift_server_min_worker_threads) {
                executor.setCorePoolSize(Config.thrift_server_min_worker_threads);
            }
            if (executor.getMaximumPoolSize() != Config.thrift_server_max_worker_threads) {
                executor.setMaximumPoolSize(Config.thrift_server_max_worker_threads);
            }
            if (executor.getKeepAliveTime(TimeUnit.SECONDS) != Config.thrift_server_worker_keep_alive_second) {
                executor.setKeepAliveTime(Config.thrift_server_max_worker_threads, TimeUnit.SECONDS);
            }
        });
    }

    public void start() throws IOException {
        try {
            createThreadPoolServer();
        } catch (TTransportException ex) {
            LOG.warn("create thrift server failed.", ex);
            throw new IOException("create thrift server failed.", ex);
        }

        ThriftServerEventProcessor eventProcessor = new ThriftServerEventProcessor(this);
        server.setServerEventHandler(eventProcessor);

        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                server.serve();
            }
        });
        serverThread.setName("thrift-server-accept");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    public void join() throws InterruptedException {
        if (server != null && server.isServing()) {
            server.stop();
        }
        serverThread.join();
    }

    public void addConnect(TNetworkAddress clientAddress) {
        connects.add(clientAddress);
    }

    public void removeConnect(TNetworkAddress clientAddress) {
        connects.remove(clientAddress);
    }

    public static ThreadPoolExecutor getExecutor() {
        return executor;
    }

    public boolean isServing() {
        return server.isServing();
    }
}
