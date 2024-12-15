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

package com.starrocks.common;

import com.starrocks.metric.MetricRepo;
import com.starrocks.rpc.ThriftConnectionPool;
import com.starrocks.thrift.FrontendService;
import com.starrocks.thrift.TAbortRemoteTxnRequest;
import com.starrocks.thrift.TAbortRemoteTxnResponse;
import com.starrocks.thrift.TAllocateAutoIncrementIdParam;
import com.starrocks.thrift.TAllocateAutoIncrementIdResult;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TBatchReportExecStatusParams;
import com.starrocks.thrift.TBatchReportExecStatusResult;
import com.starrocks.thrift.TBeginRemoteTxnRequest;
import com.starrocks.thrift.TBeginRemoteTxnResponse;
import com.starrocks.thrift.TCommitRemoteTxnRequest;
import com.starrocks.thrift.TCommitRemoteTxnResponse;
import com.starrocks.thrift.TCreatePartitionRequest;
import com.starrocks.thrift.TCreatePartitionResult;
import com.starrocks.thrift.TDescribeTableParams;
import com.starrocks.thrift.TDescribeTableResult;
import com.starrocks.thrift.TFeLocksReq;
import com.starrocks.thrift.TFeLocksRes;
import com.starrocks.thrift.TFeMemoryReq;
import com.starrocks.thrift.TFeMemoryRes;
import com.starrocks.thrift.TFeResult;
import com.starrocks.thrift.TFetchResourceResult;
import com.starrocks.thrift.TFinishSlotRequirementRequest;
import com.starrocks.thrift.TFinishSlotRequirementResponse;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.TGetDBPrivsParams;
import com.starrocks.thrift.TGetDBPrivsResult;
import com.starrocks.thrift.TGetDbsParams;
import com.starrocks.thrift.TGetDbsResult;
import com.starrocks.thrift.TGetDictQueryParamRequest;
import com.starrocks.thrift.TGetDictQueryParamResponse;
import com.starrocks.thrift.TGetGrantsToRolesOrUserRequest;
import com.starrocks.thrift.TGetGrantsToRolesOrUserResponse;
import com.starrocks.thrift.TGetKeysRequest;
import com.starrocks.thrift.TGetKeysResponse;
import com.starrocks.thrift.TGetLoadTxnStatusRequest;
import com.starrocks.thrift.TGetLoadTxnStatusResult;
import com.starrocks.thrift.TGetLoadsParams;
import com.starrocks.thrift.TGetLoadsResult;
import com.starrocks.thrift.TGetPartitionsMetaRequest;
import com.starrocks.thrift.TGetPartitionsMetaResponse;
import com.starrocks.thrift.TGetProfileRequest;
import com.starrocks.thrift.TGetProfileResponse;
import com.starrocks.thrift.TGetQueryStatisticsRequest;
import com.starrocks.thrift.TGetQueryStatisticsResponse;
import com.starrocks.thrift.TGetRoleEdgesRequest;
import com.starrocks.thrift.TGetRoleEdgesResponse;
import com.starrocks.thrift.TGetRoutineLoadJobsResult;
import com.starrocks.thrift.TGetStreamLoadsResult;
import com.starrocks.thrift.TGetTableMetaRequest;
import com.starrocks.thrift.TGetTableMetaResponse;
import com.starrocks.thrift.TGetTablePrivsParams;
import com.starrocks.thrift.TGetTablePrivsResult;
import com.starrocks.thrift.TGetTablesConfigRequest;
import com.starrocks.thrift.TGetTablesConfigResponse;
import com.starrocks.thrift.TGetTablesInfoRequest;
import com.starrocks.thrift.TGetTablesInfoResponse;
import com.starrocks.thrift.TGetTablesParams;
import com.starrocks.thrift.TGetTablesResult;
import com.starrocks.thrift.TGetTabletScheduleRequest;
import com.starrocks.thrift.TGetTabletScheduleResponse;
import com.starrocks.thrift.TGetTaskInfoResult;
import com.starrocks.thrift.TGetTaskRunInfoResult;
import com.starrocks.thrift.TGetTasksParams;
import com.starrocks.thrift.TGetTemporaryTablesInfoRequest;
import com.starrocks.thrift.TGetTemporaryTablesInfoResponse;
import com.starrocks.thrift.TGetTrackingLoadsResult;
import com.starrocks.thrift.TGetUserPrivsParams;
import com.starrocks.thrift.TGetUserPrivsResult;
import com.starrocks.thrift.TGetWarehousesRequest;
import com.starrocks.thrift.TGetWarehousesResponse;
import com.starrocks.thrift.TImmutablePartitionRequest;
import com.starrocks.thrift.TImmutablePartitionResult;
import com.starrocks.thrift.TIsMethodSupportedRequest;
import com.starrocks.thrift.TListMaterializedViewStatusResult;
import com.starrocks.thrift.TListPipeFilesParams;
import com.starrocks.thrift.TListPipeFilesResult;
import com.starrocks.thrift.TListPipesParams;
import com.starrocks.thrift.TListPipesResult;
import com.starrocks.thrift.TListSessionsRequest;
import com.starrocks.thrift.TListSessionsResponse;
import com.starrocks.thrift.TListTableStatusResult;
import com.starrocks.thrift.TLoadTxnBeginRequest;
import com.starrocks.thrift.TLoadTxnBeginResult;
import com.starrocks.thrift.TLoadTxnCommitRequest;
import com.starrocks.thrift.TLoadTxnCommitResult;
import com.starrocks.thrift.TLoadTxnRollbackRequest;
import com.starrocks.thrift.TLoadTxnRollbackResult;
import com.starrocks.thrift.TMVMaintenanceTasks;
import com.starrocks.thrift.TMVReportEpochResponse;
import com.starrocks.thrift.TMasterOpRequest;
import com.starrocks.thrift.TMasterOpResult;
import com.starrocks.thrift.TMasterResult;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TObjectDependencyReq;
import com.starrocks.thrift.TObjectDependencyRes;
import com.starrocks.thrift.TRefreshTableRequest;
import com.starrocks.thrift.TRefreshTableResponse;
import com.starrocks.thrift.TReleaseSlotRequest;
import com.starrocks.thrift.TReleaseSlotResponse;
import com.starrocks.thrift.TReportAuditStatisticsParams;
import com.starrocks.thrift.TReportAuditStatisticsResult;
import com.starrocks.thrift.TReportExecStatusParams;
import com.starrocks.thrift.TReportExecStatusResult;
import com.starrocks.thrift.TReportFragmentFinishParams;
import com.starrocks.thrift.TReportFragmentFinishResponse;
import com.starrocks.thrift.TReportLakeCompactionRequest;
import com.starrocks.thrift.TReportLakeCompactionResponse;
import com.starrocks.thrift.TReportRequest;
import com.starrocks.thrift.TRequireSlotRequest;
import com.starrocks.thrift.TRequireSlotResponse;
import com.starrocks.thrift.TSetConfigRequest;
import com.starrocks.thrift.TSetConfigResponse;
import com.starrocks.thrift.TShowVariableRequest;
import com.starrocks.thrift.TShowVariableResult;
import com.starrocks.thrift.TSnapshotLoaderReportRequest;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TStreamLoadPutRequest;
import com.starrocks.thrift.TStreamLoadPutResult;
import com.starrocks.thrift.TTableReplicationRequest;
import com.starrocks.thrift.TTableReplicationResponse;
import com.starrocks.thrift.TUpdateExportTaskStatusRequest;
import com.starrocks.thrift.TUpdateResourceUsageRequest;
import com.starrocks.thrift.TUpdateResourceUsageResponse;
import com.starrocks.utframe.UtFrameUtils;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class FeThriftServerTest {
    static ThriftConnectionPool<FrontendService.Client> frontendService;
    static ExecutorService clientExecutorThreads;
    static ThriftServer service;
    static String ip = "127.0.0.1";
    static int port = UtFrameUtils.findValidPort();
    static TNetworkAddress address = new TNetworkAddress(ip, port);

    static Runnable unsetConfig;

    private static volatile TBase CURRENT_MESSAGE;

    @BeforeClass
    public static void beforeClass() throws IOException {
        try {
            MetricRepo.init();

            GenericKeyedObjectPoolConfig config = new GenericKeyedObjectPoolConfig();
            config.setLifo(true); // set Last In First Out strategy
            config.setMaxIdlePerKey(2); // (default 8)
            config.setMinIdlePerKey(0); // (default 0)
            config.setMaxTotalPerKey(10); // (default 8)
            config.setMaxTotal(10); // (default -1)
            config.setMaxWaitMillis(500);
            // new ClientPool
            frontendService = new ThriftConnectionPool<>("FrontendService", config, 0);

            // use multi thread simulate concurrency request
            clientExecutorThreads = Executors.newFixedThreadPool(20);

            int preThriftServerQueueSize = Config.thrift_server_queue_size;
            int preThriftServerMinWorkerThreads = Config.thrift_server_min_worker_threads;
            boolean preThriftServerNioMode = Config.thrift_server_nio_enabled;
            unsetConfig = () -> {
                Config.thrift_server_queue_size = preThriftServerQueueSize;
                Config.thrift_server_min_worker_threads = preThriftServerMinWorkerThreads;
                Config.thrift_server_nio_enabled = preThriftServerNioMode;
            };
            Config.thrift_server_min_worker_threads = 10;
            Config.thrift_server_queue_size = 1024;
            Config.thrift_server_nio_enabled = true;
            // new ThriftService
            TProcessor tprocessor = new FrontendService.Processor<FrontendService.Iface>(new InternalFrontendService());
            service = new ThriftServer(port, tprocessor);
            service.start();
            while (!service.isServing()) {
                Thread.sleep(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            close();
        }
    }

    @AfterClass
    public static void afterClass() throws IOException {
        close();
        if (unsetConfig != null) {
            unsetConfig.run();
        }
    }

    private static void close() {
        if (service != null) {
            service.stop();
        }
        clientExecutorThreads.shutdown();
    }

    @Test
    public void testCall() throws Exception {
        {
            TReportRequest reportRequest = new TReportRequest();
            reportRequest.setBackend(new TBackend("verylonghostnamexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                    1000, 2000));
            FrontendService.Client client = frontendService.borrowObject(address);
            TMasterResult report = client.report(reportRequest);
            frontendService.returnObject(address, client);
        }


        List<Future<TGetDbsResult>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            FrontendService.Client client = frontendService.borrowObject(address);
            Future<TGetDbsResult> future = clientExecutorThreads.submit(() -> {
                try {
                    TGetDbsParams params = new TGetDbsParams();
                    return client.getDbNames(params);
                } catch (TException e) {
                    throw new RuntimeException(e);
                } finally {
                    frontendService.returnObject(address, client);
                }
            });
            futures.add(future);
        }
        for (Future<TGetDbsResult> future : futures) {
            TGetDbsResult result = future.get();
            Assert.assertEquals("db1", result.getDbs().get(0));
        }
    }

    @Test(expected = TApplicationException.class)
    public void testCallError() throws Exception {
        FrontendService.Client client = frontendService.borrowObject(address);
        TGetTablesParams params = new TGetTablesParams();
        params.setDb("db1");
        try {
            TGetTablesResult tableNames = client.getTableNames(params);
        } finally {
            frontendService.invalidateObject(address, client);
        }
    }

    @Test
    public void testTxn() throws Exception {
        FrontendService.Client client = frontendService.borrowObject(address);
        TLoadTxnBeginRequest request = new TLoadTxnBeginRequest();
        request.setCluster(null);
        request.setUser("user123456");
        request.setPasswd("passwdpasswd");
        request.setDb("audit");
        request.setTbl("query_log");
        request.setUser_ip("111.111.111.111");
        request.setLabel("audit_20241105_072241_10_190_34_193_9010_8030");
        CURRENT_MESSAGE = request;

        TLoadTxnBeginResult tLoadTxnBeginResult = client.loadTxnBegin(request);
        frontendService.returnObject(address, client);
        Assert.assertEquals(TStatusCode.OK, tLoadTxnBeginResult.getStatus().getStatus_code());
    }

    @Test
    public void testLarge() throws Exception {
        ExecutorService threadPool = Executors.newFixedThreadPool(1);

        // thread 1 huge msg
        byte[] bytes = new byte[1024 * 1024 * 200];
        String hugeMsg = new String(bytes);
        Future<Status> firstCall = threadPool.submit(() -> {
            TReportRequest reportRequest = new TReportRequest();
            reportRequest.setBackend(new TBackend(hugeMsg, 1000, 2000));
            FrontendService.Client client = null;
            try {
                client = frontendService.borrowObject(address, 60000);
                TMasterResult report = client.report(reportRequest);
                return Status.OK;
            } catch (Exception e) {
                return new Status(TStatusCode.INTERNAL_ERROR, e.getMessage());
            } finally {
                if (client != null) {
                    frontendService.returnObject(address, client);
                }
            }
        });
        threadPool.shutdown();

        while (!firstCall.isDone()) {
            TReportRequest reportRequest = new TReportRequest();
            reportRequest.setBackend(new TBackend("123", 1000, 2000));
            FrontendService.Client client = null;
            try {
                client = frontendService.borrowObject(address, 100);
                TMasterResult report = client.report(reportRequest);
            } finally {
                if (client != null) {
                    frontendService.returnObject(address, client);
                }
            }
        }
    }

    private static class InternalFrontendService implements FrontendService.Iface {

        @Override
        public TGetDbsResult getDbNames(TGetDbsParams params) throws TException {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return new TGetDbsResult(Arrays.asList("db1"));
        }

        @Override
        public TGetTablesResult getTableNames(TGetTablesParams params) throws TException {
            throw new TException("test exception");
        }

        @Override
        public TGetTablesInfoResponse getTablesInfo(TGetTablesInfoRequest request) throws TException {
            return null;
        }

        @Override
        public TGetTablesConfigResponse getTablesConfig(TGetTablesConfigRequest request) throws TException {
            return null;
        }

        @Override
        public TGetUserPrivsResult getUserPrivs(TGetUserPrivsParams params) throws TException {
            return null;
        }

        @Override
        public TGetDBPrivsResult getDBPrivs(TGetDBPrivsParams params) throws TException {
            return null;
        }

        @Override
        public TGetTablePrivsResult getTablePrivs(TGetTablePrivsParams params) throws TException {
            return null;
        }

        @Override
        public TGetKeysResponse getKeys(TGetKeysRequest params) throws TException {
            return null;
        }

        @Override
        public TGetLoadsResult getLoads(TGetLoadsParams params) throws TException {
            return null;
        }

        @Override
        public TGetTrackingLoadsResult getTrackingLoads(TGetLoadsParams params) throws TException {
            return null;
        }

        @Override
        public TGetRoutineLoadJobsResult getRoutineLoadJobs(TGetLoadsParams params) throws TException {
            return null;
        }

        @Override
        public TGetStreamLoadsResult getStreamLoads(TGetLoadsParams params) throws TException {
            return null;
        }

        @Override
        public TGetProfileResponse getQueryProfile(TGetProfileRequest request) throws TException {
            return null;
        }

        @Override
        public TDescribeTableResult describeTable(TDescribeTableParams params) throws TException {
            return null;
        }

        @Override
        public TShowVariableResult showVariables(TShowVariableRequest params) throws TException {
            return null;
        }

        @Override
        public TReportExecStatusResult reportExecStatus(TReportExecStatusParams params) throws TException {
            return null;
        }

        @Override
        public TReportAuditStatisticsResult reportAuditStatistics(TReportAuditStatisticsParams params) throws TException {
            return null;
        }

        @Override
        public TBatchReportExecStatusResult batchReportExecStatus(TBatchReportExecStatusParams params) throws TException {
            return null;
        }

        @Override
        public TMasterResult finishTask(TFinishTaskRequest request) throws TException {
            return null;
        }

        @Override
        public TMasterResult report(TReportRequest request) throws TException {
            TMasterResult tMasterResult = new TMasterResult();
            TStatus status = new TStatus(TStatusCode.OK);
            tMasterResult.setStatus(status);
            return tMasterResult;
        }

        @Override
        public TFetchResourceResult fetchResource() throws TException {
            return null;
        }

        @Override
        public TFeResult isMethodSupported(TIsMethodSupportedRequest request) throws TException {
            return null;
        }

        @Override
        public TMasterOpResult forward(TMasterOpRequest params) throws TException {
            return null;
        }

        @Override
        public TListTableStatusResult listTableStatus(TGetTablesParams params) throws TException {
            return null;
        }

        @Override
        public TListMaterializedViewStatusResult listMaterializedViewStatus(TGetTablesParams params) throws TException {
            return null;
        }

        @Override
        public TListPipesResult listPipes(TListPipesParams params) throws TException {
            return null;
        }

        @Override
        public TListPipeFilesResult listPipeFiles(TListPipeFilesParams params) throws TException {
            return null;
        }

        @Override
        public TGetTaskInfoResult getTasks(TGetTasksParams params) throws TException {
            return null;
        }

        @Override
        public TGetTaskRunInfoResult getTaskRuns(TGetTasksParams params) throws TException {
            return null;
        }

        @Override
        public TFeResult updateExportTaskStatus(TUpdateExportTaskStatusRequest request) throws TException {
            return null;
        }

        @Override
        public TLoadTxnBeginResult loadTxnBegin(TLoadTxnBeginRequest request) throws TException {
            // verify serde correct
            Assert.assertEquals(CURRENT_MESSAGE, request);
            return new TLoadTxnBeginResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TLoadTxnCommitResult loadTxnCommit(TLoadTxnCommitRequest request) throws TException {
            return null;
        }

        @Override
        public TLoadTxnRollbackResult loadTxnRollback(TLoadTxnRollbackRequest request) throws TException {
            return null;
        }

        @Override
        public TLoadTxnCommitResult loadTxnPrepare(TLoadTxnCommitRequest request) throws TException {
            return null;
        }

        @Override
        public TStreamLoadPutResult streamLoadPut(TStreamLoadPutRequest request) throws TException {
            return null;
        }

        @Override
        public TStatus snapshotLoaderReport(TSnapshotLoaderReportRequest request) throws TException {
            return null;
        }

        @Override
        public TRefreshTableResponse refreshTable(TRefreshTableRequest request) throws TException {
            return null;
        }

        @Override
        public TGetTableMetaResponse getTableMeta(TGetTableMetaRequest request) throws TException {
            return null;
        }

        @Override
        public TBeginRemoteTxnResponse beginRemoteTxn(TBeginRemoteTxnRequest request) throws TException {
            return null;
        }

        @Override
        public TCommitRemoteTxnResponse commitRemoteTxn(TCommitRemoteTxnRequest request) throws TException {
            return null;
        }

        @Override
        public TAbortRemoteTxnResponse abortRemoteTxn(TAbortRemoteTxnRequest request) throws TException {
            return null;
        }

        @Override
        public TSetConfigResponse setConfig(TSetConfigRequest request) throws TException {
            return null;
        }

        @Override
        public TCreatePartitionResult createPartition(TCreatePartitionRequest request) throws TException {
            return null;
        }

        @Override
        public TImmutablePartitionResult updateImmutablePartition(TImmutablePartitionRequest request)
                throws TException {
            return null;
        }

        @Override
        public TUpdateResourceUsageResponse updateResourceUsage(TUpdateResourceUsageRequest request) throws TException {
            return null;
        }

        @Override
        public TGetWarehousesResponse getWarehouses(TGetWarehousesRequest request) throws TException {
            return null;
        }

        @Override
        public TGetQueryStatisticsResponse getQueryStatistics(TGetQueryStatisticsRequest request) throws TException {
            return null;
        }

        @Override
        public TMVReportEpochResponse mvReport(TMVMaintenanceTasks request) throws TException {
            return null;
        }

        @Override
        public TGetTabletScheduleResponse getTabletSchedule(TGetTabletScheduleRequest request) throws TException {
            return null;
        }

        @Override
        public TGetRoleEdgesResponse getRoleEdges(TGetRoleEdgesRequest request) throws TException {
            return null;
        }

        @Override
        public TGetGrantsToRolesOrUserResponse getGrantsTo(TGetGrantsToRolesOrUserRequest request) throws TException {
            return null;
        }

        @Override
        public TObjectDependencyRes listObjectDependencies(TObjectDependencyReq request) throws TException {
            return null;
        }

        @Override
        public TFeLocksRes listFeLocks(TFeLocksReq request) throws TException {
            return null;
        }

        @Override
        public TFeMemoryRes listFeMemoryUsage(TFeMemoryReq request) throws TException {
            return null;
        }

        @Override
        public TRequireSlotResponse requireSlotAsync(TRequireSlotRequest request) throws TException {
            return null;
        }

        @Override
        public TFinishSlotRequirementResponse finishSlotRequirement(TFinishSlotRequirementRequest request)
                throws TException {
            return null;
        }

        @Override
        public TReleaseSlotResponse releaseSlot(TReleaseSlotRequest request) throws TException {
            return null;
        }

        @Override
        public TGetLoadTxnStatusResult getLoadTxnStatus(TGetLoadTxnStatusRequest request) throws TException {
            return null;
        }

        @Override
        public TAllocateAutoIncrementIdResult allocAutoIncrementId(TAllocateAutoIncrementIdParam params) throws TException {
            return null;
        }

        @Override
        public TGetDictQueryParamResponse getDictQueryParam(TGetDictQueryParamRequest request) throws TException {
            return null;
        }

        @Override
        public TTableReplicationResponse startTableReplication(TTableReplicationRequest request) throws TException {
            return null;
        }

        @Override
        public TGetPartitionsMetaResponse getPartitionsMeta(TGetPartitionsMetaRequest request) throws TException {
            return null;
        }

        @Override
        public TReportLakeCompactionResponse reportLakeCompaction(TReportLakeCompactionRequest request)
                throws TException {
            return null;
        }

        @Override
        public TListSessionsResponse listSessions(TListSessionsRequest request) throws TException {
            return null;
        }

        @Override
        public TGetTemporaryTablesInfoResponse getTemporaryTablesInfo(TGetTemporaryTablesInfoRequest request)
                throws TException {
            return null;
        }

        @Override
        public TReportFragmentFinishResponse reportFragmentFinish(TReportFragmentFinishParams request)
                throws TException {
            return null;
        }
    }

}
