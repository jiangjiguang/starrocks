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

package com.starrocks.scheduler;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.ResourceGroupClassifier;
import com.starrocks.common.Config;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TWorkGroup;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Schedule pending task runs to running task runs, it uses priority queue to schedule task runs.
 * The task run with higher priority or older created time will be scheduled first.
 */
public class TaskRunScheduler {
    private static final Logger LOG = LogManager.getLogger(TaskRunScheduler.class);

    // pending task run queue, it will schedule in fifo mode to ensure the task run scheduled in priority order.
    @SerializedName("pendingTaskRunQueue")
    private final TaskRunFIFOQueue pendingTaskRunQueue = new TaskRunFIFOQueue();

    // taskId -> running TaskRun, for each Task only support 1 running taskRun currently,
    // so the map value is not queue
    @SerializedName("runningTaskRunMap")
    private final Map<Long, TaskRun> runningTaskRunMap = Maps.newConcurrentMap();

    @SerializedName("runningSyncTaskRunMap")
    private final Map<Long, TaskRun> runningSyncTaskRunMap = Maps.newConcurrentMap();

    // resourceGroupName -> running TaskRun nums
    // for each resource group only support concurrency_limit running taskRun currently
    @SerializedName("runningTaskRunResourceGroupNameMap")
    private final Map<String, List<String>> runningTaskRunResourceGroupNameMap = Maps.newConcurrentMap();


    ////////// pending task run map //////////
    /**
     * Get the count of pending task run
     */
    public long getPendingQueueCount() {
        return pendingTaskRunQueue.size();
    }

    /**
     * Get the pending task run queue
     */
    public List<TaskRun> getCopiedPendingTaskRuns() {
        return pendingTaskRunQueue.getCopiedPendingTaskRuns();
    }

    /**
     * @param taskId: task id
     * @return: pending task run queue
     */
    public Set<TaskRun> getPendingTaskRunsByTaskId(long taskId) {
        return pendingTaskRunQueue.getByTaskId(taskId);
    }

    /**
     * Add a task run to pending queue
     * @param taskRun: task run
     * @return: true if add success, false if add failed
     */
    public boolean addPendingTaskRun(TaskRun taskRun) {
        if (taskRun == null) {
            return false;
        }
        return pendingTaskRunQueue.add(taskRun);
    }

    public void removePendingTaskRun(TaskRun taskRun, Constants.TaskRunState state) {
        if (taskRun == null) {
            return;
        }
        LOG.info("remove pending task run: {}", taskRun);
        pendingTaskRunQueue.remove(taskRun, state);
    }

    public void removePendingTask(Task task) {
        if (task == null) {
            return;
        }
        LOG.info("remove pending task: {}", task);
        pendingTaskRunQueue.remove(task.getId());
    }

    public TaskRun getTaskRunByQueryId(Long taskId, String queryId) {
        if (taskId == null || queryId == null) {
            return null;
        }
        Collection<TaskRun> taskRunQueue = pendingTaskRunQueue.getByTaskId(taskId);
        if (taskRunQueue == null) {
            return null;
        }
        return taskRunQueue.stream()
                .filter(taskRun -> queryId.equals(taskRun.getStatus().getQueryId()))
                .findFirst()
                .orElse(null);
    }

    boolean canTaskRunBeScheduled(TaskRun taskRun) {
        // if the task is running, it can't be scheduled
        return !runningTaskRunMap.containsKey(taskRun.getTaskId());
    }

    /**
     * schedule the pending TaskRun that can be run into running TaskRun map
     * @param action: the action to run the task run before task runs queue is full
     */
    public void scheduledPendingTaskRun(Consumer<TaskRun> action) {
        int currentRunning = runningTaskRunMap.size();
        if (currentRunning >= Config.task_runs_concurrency) {
            return;
        }
        while (!pendingTaskRunQueue.isEmpty()) {
            if (currentRunning >= Config.task_runs_concurrency) {
                break;
            }
            TaskRun taskRun = pendingTaskRunQueue.poll(this::canTaskRunBeScheduled);
            if (taskRun == null) {
                break;
            }

            // if the task run is etl, it should be scheduled by resource group
            if (checkIfLakeWarehouse2SR(taskRun)) {
                // get the resource group name from task run
                TWorkGroup tWorkGroup = getResourceGroupName(taskRun).orElse(null);
                if (tWorkGroup == null) {
                    LOG.warn("task run TWorkGroup is null after getResourceGroupName(), taskName: {}",
                            taskRun.getTask().getName());
                    // put it back to pending queue
                    pendingTaskRunQueue.add(taskRun);
                    // exit the loop, because the task run is not ready to run
                    break;
                }
                // check the resource group concurrency limit
                long currentResourceGroupRunning = getCurrentRunningTaskRunCount(tWorkGroup);
                if (currentResourceGroupRunning >= taskRun.getRunCtx().getResourceGroup().getConcurrency_limit()) {
                    LOG.warn("task run resource group concurrency limit is reached, taskName: {}, TWorkGroup: {}, " +
                                    "currentResourceGroupRunning:{}", taskRun.getTask().getName(), tWorkGroup,
                            currentResourceGroupRunning);
                    setErrorMessageBecauseOfExceedConcurrencyLimit(taskRun, currentResourceGroupRunning, tWorkGroup);
                    // put it back to pending queue
                    pendingTaskRunQueue.add(taskRun);
                    // exit the loop, because the task run is not ready to run
                    break;
                }
                // increment the running task run count for the resource group
                addRunningTaskRunResourceGroupName(tWorkGroup.getName(), taskRun.getTask().getName());
            }

            // do schedule action
            action.accept(taskRun);
            // put it into running task run map
            runningTaskRunMap.put(taskRun.getTaskId(), taskRun);
            currentRunning += 1;
        }
    }

    public long getTaskIdPendingTaskRunCount(long taskId) {
        Set<TaskRun> pendingTaskRuns = getPendingTaskRunsByTaskId(taskId);
        return  pendingTaskRuns == null ? 0L : pendingTaskRuns.size();
    }

    //////////// running task run map ////////////

    public void addRunningTaskRun(TaskRun taskRun) {
        if (taskRun == null) {
            return;
        }
        runningTaskRunMap.put(taskRun.getTaskId(), taskRun);
    }

    public Set<Long> getCopiedRunningTaskIds() {
        return ImmutableSet.copyOf(runningTaskRunMap.keySet());
    }

    public TaskRun removeRunningTask(long taskId) {
        return runningTaskRunMap.remove(taskId);
    }

    public Set<TaskRun> getCopiedRunningTaskRuns() {
        return ImmutableSet.copyOf(runningTaskRunMap.values());
    }

    public boolean isTaskRunning(long taskId) {
        return runningTaskRunMap.containsKey(taskId);
    }

    public long getRunningTaskCount() {
        return runningTaskRunMap.size();
    }

    /**
     * Get the count of running task run
     * @param taskId: task id
     */
    public TaskRun getRunningTaskRun(long taskId) {
        return runningTaskRunMap.get(taskId);
    }

    /**
     * Get the runnable task run by task id
     * @param taskId: task id
     * @return: the runnable task run, null if not found
     */
    public TaskRun getRunnableTaskRun(long taskId) {
        TaskRun res = runningTaskRunMap.get(taskId);
        if (res != null) {
            return res;
        }
        Set<TaskRun> queue = pendingTaskRunQueue.getByTaskId(taskId);
        if (queue != null) {
            return queue.stream().findFirst().orElse(null);
        }
        return null;
    }

    //////////// sync running task run map ////////////
    public void addSyncRunningTaskRun(TaskRun taskRun) {
        if (taskRun == null) {
            return;
        }
        runningSyncTaskRunMap.put(taskRun.getTaskId(), taskRun);
    }

    public TaskRun removeSyncRunningTaskRun(TaskRun taskRun) {
        if (taskRun == null) {
            return null;
        }
        return runningSyncTaskRunMap.remove(taskRun.getTaskId());
    }

    @Override
    public String toString() {
        return GsonUtils.GSON.toJson(this);
    }

    public void addRunningTaskRunResourceGroupName(String resourceGroupName, String taskName) {
        List<String> rgTaskRunList = runningTaskRunResourceGroupNameMap.get(resourceGroupName);
        rgTaskRunList.add(taskName);
        runningTaskRunResourceGroupNameMap.put(resourceGroupName, rgTaskRunList);
    }



    /**
     * get the resource group name from task run
     * @param taskRun: task run
     * @return: the resource group name
     */
    private Optional<TWorkGroup> getResourceGroupName(TaskRun taskRun) {
        ConnectContext runCtx = taskRun.getRunCtx();
        if (runCtx == null) {
            LOG.warn("task run context is null, taskRun: {}", taskRun);
            taskRun.initRunCtx();
        }
        runCtx = taskRun.getRunCtx();
        if (runCtx == null) {
            LOG.warn("task run context is null after initRunCtx(), taskRun: {}", taskRun);
            setErrorMessageBecauseOfRunContextEmpty(taskRun);
            return Optional.empty();
        }
        TWorkGroup tWorkGroup = null;
        ResourceGroupClassifier.QueryType queryType = ResourceGroupClassifier.QueryType.INSERT;
        Set<Long> dbIds = runCtx.getCurrentSqlDbIds();
        SessionVariable sessionVariable = runCtx.getSessionVariable();
        String resourceGroupName = sessionVariable.getResourceGroup();
        // try to get rg from task run context
        if (!StringUtils.isBlank(resourceGroupName)) {
            LOG.info("get resource group name from task run context session, taskName: {}, resourceGroupName: {}",
                    taskRun.getTask().getName(), resourceGroupName);
            tWorkGroup = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroupByName(resourceGroupName);
        }
        // retry to get rg from login username
        if (tWorkGroup == null) {
            LOG.info("get task run tWorkGroup by chooseResourceGroupByName is empty, " +
                            "retry to get resource group by chooseResourceGroup, taskRun: {}, " +
                            "resourceGroupName: {}, queryType: {}, dbIds: {}",
                    taskRun, resourceGroupName, queryType, dbIds);
            tWorkGroup = GlobalStateMgr.getCurrentState().getResourceGroupMgr().chooseResourceGroup(
                    runCtx, queryType, dbIds);
        }
        if (tWorkGroup == null) {
            LOG.warn("task run tWorkGroup is null by chooseResourceGroup, " +
                            "taskRun: {}, createUser:{}, queryType: {}, dbIds:{}", taskRun,
                    runCtx.getQualifiedUser(), queryType, dbIds);
            setErrorMessageBecauseOfMissResourceGroup(taskRun);
            return Optional.empty();
        }
        if (StringUtils.isBlank(tWorkGroup.getName())) {
            LOG.warn("task run tWorkGroup name is empty, taskRun: {}, tWorkGroup: {}",
                    taskRun, tWorkGroup);
            setErrorMessageBecauseOfResourceGroupNameEmpty(taskRun);
            return Optional.empty();
        }
        // set new resource group name to task run
        resetTaskRunByTWorkGroup(taskRun, tWorkGroup);

        return Optional.of(tWorkGroup);
    }

    private void setErrorMessageBecauseOfExceedConcurrencyLimit(TaskRun taskRun,
                                                                long currentResourceGroupRunning,
                                                                TWorkGroup tWorkGroup) {
        String errMsg = String.format("Currently there are already [%s] tasks running, " +
                        "exceeding the maximum concurrent number [%s] of the resource group [%s]",
                currentResourceGroupRunning, tWorkGroup.getConcurrency_limit(), tWorkGroup.getName());
        TaskRunStatus taskRunStatus = taskRun.getStatus();
        taskRunStatus.setErrorMessage(errMsg);
        taskRun.setStatus(taskRunStatus);
    }


    private void setErrorMessageBecauseOfRunContextEmpty(TaskRun taskRun) {
        String errMsg = "Due to the failure of initializing the running context, the resource group cannot be matched ";
        TaskRunStatus taskRunStatus = taskRun.getStatus();
        taskRunStatus.setErrorMessage(errMsg);
        taskRun.setStatus(taskRunStatus);
    }


    private void setErrorMessageBecauseOfMissResourceGroup(TaskRun taskRun) {
        ConnectContext runCtx = taskRun.getRunCtx();
        String resourceGroupName = runCtx.getSessionVariable().getResourceGroup();
        String errMsg = "This task cannot match the resource group based on the ";
        if (StringUtils.isBlank(resourceGroupName)) {
            errMsg += "submitted account [" + runCtx.getQualifiedUser() + "]";
        } else {
            errMsg += "submitted resource group name [" + resourceGroupName + "]";
        }
        TaskRunStatus taskRunStatus = taskRun.getStatus();
        taskRunStatus.setErrorMessage(errMsg);
        taskRun.setStatus(taskRunStatus);
    }

    private void setErrorMessageBecauseOfResourceGroupNameEmpty(TaskRun taskRun) {
        String errMsg = "The name of the resource group matched by this task is empty ";
        TaskRunStatus taskRunStatus = taskRun.getStatus();
        taskRunStatus.setErrorMessage(errMsg);
        taskRun.setStatus(taskRunStatus);
    }

    private void resetTaskRunByTWorkGroup(TaskRun taskRun, TWorkGroup tWorkGroup) {
        ConnectContext runCtx = taskRun.getRunCtx();

        // set new resource group name to task run
        runCtx.setResourceGroup(tWorkGroup);
        SessionVariable sessionVariable = runCtx.getSessionVariable();
        sessionVariable.setResourceGroup(tWorkGroup.getName());
        runCtx.setSessionVariable(sessionVariable);
        taskRun.setRunCtx(runCtx);

        Map<String, String> properties = taskRun.getProperties();
        properties.put(SessionVariable.RESOURCE_GROUP, tWorkGroup.getName());
        taskRun.setProperties(properties);

        // init pipeline dop
        initPipeLineDopSession(taskRun, tWorkGroup);
        // init connector io tasks per scan operator
        initScanConnectorSession(taskRun, tWorkGroup);
        // init query_timeout
        initLoadQueryTimeoutSession(taskRun);
    }

    private void initLoadQueryTimeoutSession(TaskRun taskRun) {
        Map<String, String> taskRunProperties = taskRun.getProperties();
        if (taskRunProperties.containsKey(SessionVariable.QUERY_TIMEOUT)) {
            // if the task run has set the query_timeout, do not override it
            return;
        }
        Integer queryTime = Config.default_offline_load_query_timeout;
        SessionVariable sessionVariable = taskRun.getRunCtx().getSessionVariable();
        sessionVariable.setQueryTimeoutS(queryTime);
        taskRun.getRunCtx().setSessionVariable(sessionVariable);

        taskRunProperties.put(SessionVariable.QUERY_TIMEOUT, String.valueOf(queryTime));
        taskRun.setProperties(taskRunProperties);
    }


    private void initPipeLineDopSession(TaskRun taskRun, TWorkGroup tWorkGroup) {
        Map<String, String> taskRunProperties = taskRun.getProperties();
        if (taskRunProperties.containsKey(SessionVariable.PIPELINE_DOP)) {
            // if the task run has set the pipeline dop, do not override it
            return;
        }
        int pipelineDop = calcPipelineDop(tWorkGroup);
        SessionVariable sessionVariable = taskRun.getRunCtx().getSessionVariable();
        sessionVariable.setPipelineDop(pipelineDop);
        taskRun.getRunCtx().setSessionVariable(sessionVariable);

        taskRunProperties.put(SessionVariable.PIPELINE_DOP, String.valueOf(pipelineDop));
        taskRunProperties.put(SessionVariable.ENABLE_ADAPTIVE_SINK_DOP, String.valueOf(true));
        taskRun.setProperties(taskRunProperties);
    }

    private int calcPipelineDop(TWorkGroup tWorkGroup) {
        int concurrencyLimit = tWorkGroup.getConcurrency_limit();
        int cpuCoreLimit = tWorkGroup.getCpu_core_limit();
        if (concurrencyLimit <= 0 || cpuCoreLimit <= 0) {
            LOG.warn("resource group concurrency limit or cpu core limit is not set, " +
                            "initial pipeline_dop = 1, concurrencyLimit: {}, cpuCoreLimit: {}, resourceGroupName: {}",
                    concurrencyLimit, cpuCoreLimit, tWorkGroup.getName());
            return Config.default_offline_load_pipeline_dop;
        }
        int pipelineDop = cpuCoreLimit / concurrencyLimit;
        if (pipelineDop == 0) {
            LOG.warn("pipeline dop is set to 1, " +
                            "concurrencyLimit: {}, cpuCoreLimit: {}, resourceGroupName:{}",
                    concurrencyLimit, cpuCoreLimit, tWorkGroup.getName());
            pipelineDop = Config.default_offline_load_pipeline_dop;
        }
        return pipelineDop;
    }

    private void initScanConnectorSession(TaskRun taskRun, TWorkGroup tWorkGroup) {
        Map<String, String> taskRunProperties = taskRun.getProperties();
        ConnectContext runCtx = taskRun.getRunCtx();
        SessionVariable sessionVariable = runCtx.getSessionVariable();

        // if the task run has set the connector io tasks per scan operator, do not override it
        if (taskRunProperties.containsKey(SessionVariable.CONNECTOR_IO_TASKS_PER_SCAN_OPERATOR)) {
            return;
        }
        int pipelineDop = calcPipelineDop(tWorkGroup);
        double memRatio = sessionVariable.getConnectorScanUseQueryMemRatio();
        if (pipelineDop > 0) {
            memRatio = Math.min(memRatio, (double) pipelineDop / tWorkGroup.getConcurrency_limit());
        }
        taskRunProperties.put(SessionVariable.CONNECTOR_IO_TASKS_PER_SCAN_OPERATOR,
                String.valueOf(pipelineDop));
        taskRunProperties.put(SessionVariable.CONNECTOR_SCAN_USE_QUERY_MEM_RATIO, String.valueOf(memRatio));
        taskRunProperties.put(SessionVariable.ENABLE_CONNECTOR_ADAPTIVE_IO_TASKS, String.valueOf(false));
        taskRun.setProperties(taskRunProperties);

        sessionVariable.setConnectorIoTasksPerScanOperator(pipelineDop);
        sessionVariable.setConnectorScanUseQueryMemRatio(memRatio);
        sessionVariable.setEnableConnectorAdaptiveIoTasks(false);

        runCtx.setSessionVariable(sessionVariable);
        taskRun.setRunCtx(runCtx);
    }

    private boolean checkIfLakeWarehouse2SR(TaskRun taskRun) {
        if (!Config.enable_offline_load_control) {
            return false;
        }
        if (taskRun == null || taskRun.getTask() == null || taskRun.getTask().getProperties() == null) {
            return false;
        }
        return Config.default_offline_load_flag.equalsIgnoreCase(
                taskRun.getTask().getProperties().get(Constants.SUBMIT_TYPE)
        );
    }

    public long getCurrentRunningTaskRunCount(TWorkGroup tWorkGroup) {
        if (!runningTaskRunResourceGroupNameMap.containsKey(tWorkGroup.getName())) {
            runningTaskRunResourceGroupNameMap.put(tWorkGroup.getName(), new ArrayList<>());
        }
        List<String> rgTaskRunList = runningTaskRunResourceGroupNameMap.get(tWorkGroup.getName());
        return rgTaskRunList.size();
    }

}
