/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.master.engine.task.priority;

import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.TaskGroupQueue;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;

import java.util.Date;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * PriorityWeightEngine calculates weighted priority scores for tasks in the queue.
 *
 * The engine applies a configured PriorityStrategy to compute a composite score
 * that factors in base priority, wait time, retry count, dependency depth, and
 * resource urgency. Higher scores mean higher scheduling priority.
 *
 * Integration points:
 * - Called by TaskGroupCoordinator when a task enters the wait queue (acquireTaskGroupSlot)
 * - Called periodically by TaskGroupCoordinator to re-score waiting tasks
 * - Score is persisted as TaskGroupQueue.weightScore
 */
@Slf4j
@Component
public class PriorityWeightEngine {

    @Autowired
    private PriorityStrategy priorityStrategy;

    @Autowired
    private PriorityScoreCalculator scoreCalculator;

    /**
     * Calculate the initial weight score for a newly queued task.
     *
     * @param taskInstance    the task instance
     * @param taskDefinition  the task definition
     * @param workflowInstance the workflow instance
     * @return computed weight score (1-100 range)
     */
    public int calculateInitialScore(TaskInstance taskInstance, TaskDefinition taskDefinition,
            WorkflowInstance workflowInstance) {
        PriorityFactors factors = buildFactors(taskInstance, taskDefinition, workflowInstance, 0);
        int score = scoreCalculator.calculate(factors);
        log.debug("Initial priority score for task {} (id={}): {}", taskInstance.getName(), taskInstance.getId(),
                score);
        return score;
    }

    /**
     * Recalculate weight scores for a batch of waiting tasks.
     * This is called periodically to update scores based on changing conditions
     * (e.g., increased wait time triggers anti-starvation boost).
     *
     * @param taskGroupQueues list of waiting task group queue entries
     * @param taskInstances   map of taskId -> TaskInstance
     * @param taskDefinitions map of taskCode -> TaskDefinition
     * @param workflowInstances map of workflowInstanceId -> WorkflowInstance
     * @return list of updated TaskGroupQueue entries with new weightScores
     */
    public List<TaskGroupQueue> recalculateScores(List<TaskGroupQueue> taskGroupQueues,
            java.util.Map<Integer, TaskInstance> taskInstances,
            java.util.Map<Long, TaskDefinition> taskDefinitions,
            java.util.Map<Integer, WorkflowInstance> workflowInstances) {
        for (TaskGroupQueue queue : taskGroupQueues) {
            TaskInstance taskInstance = taskInstances.get(queue.getTaskId());
            if (taskInstance == null) {
                continue;
            }
            TaskDefinition taskDefinition = taskDefinitions.get(taskInstance.getTaskCode());
            WorkflowInstance workflowInstance = workflowInstances.get(taskInstance.getWorkflowInstanceId());

            // Calculate wait time in seconds since task was queued
            long waitTimeSeconds = 0;
            if (queue.getCreateTime() != null) {
                waitTimeSeconds = (System.currentTimeMillis() - queue.getCreateTime().getTime()) / 1000;
            }

            PriorityFactors factors = buildFactors(taskInstance, taskDefinition, workflowInstance, waitTimeSeconds);
            int newScore = scoreCalculator.calculate(factors);

            // Only update if score changed significantly (avoid unnecessary DB writes)
            if (Math.abs(newScore - queue.getWeightScore()) > 2) {
                log.debug("Recalculated priority score for task {} (queueId={}): {} -> {}",
                        queue.getTaskName(), queue.getId(), queue.getWeightScore(), newScore);
                queue.setWeightScore(newScore);
            }
        }
        return taskGroupQueues;
    }

    /**
     * Build PriorityFactors from task/workflow context.
     */
    private PriorityFactors buildFactors(TaskInstance taskInstance, TaskDefinition taskDefinition,
            WorkflowInstance workflowInstance, long waitTimeSeconds) {
        PriorityFactors factors = new PriorityFactors();

        // Base priority from task definition's taskGroupPriority (0-10 scale)
        int basePriority = 50; // default
        if (taskDefinition != null) {
            basePriority = normalizeTaskGroupPriority(taskDefinition.getTaskGroupPriority());
        }
        factors.setBasePriority(basePriority);

        // Wait time factor (seconds)
        factors.setWaitTimeSeconds(waitTimeSeconds);

        // Retry count factor
        int retryCount = 0;
        if (taskInstance != null) {
            retryCount = taskInstance.getRetryTimes();
        }
        factors.setRetryCount(retryCount);

        // Dependency depth factor (number of upstream tasks)
        factors.setDependencyDepth(0); // TODO: populate from WorkflowExecutionGraph

        // Resource urgency factor (from workflow priority)
        // Priority codes: HIGHEST=0, HIGH=1, MEDIUM=2, LOW=3, LOWEST=4
        // Invert so HIGHEST maps to 100 and LOWEST maps to 0
        int resourceUrgency = 50;
        if (workflowInstance != null && workflowInstance.getWorkflowInstancePriority() != null) {
            int code = workflowInstance.getWorkflowInstancePriority().getCode();
            resourceUrgency = (4 - code) * 25;
        }
        factors.setResourceUrgency(resourceUrgency);

        // Dynamic priority weight overrides (runtime-adjusted)
        if (taskInstance != null && taskInstance.getPriorityWeight() != null) {
            factors.setDynamicWeight(taskInstance.getPriorityWeight());
        } else if (workflowInstance != null && workflowInstance.getPriorityWeight() != null) {
            factors.setDynamicWeight(workflowInstance.getPriorityWeight());
        } else {
            factors.setDynamicWeight(50);
        }

        return factors;
    }

    /**
     * Normalize taskGroupPriority (0-N scale) to 1-100 range.
     * Higher taskGroupPriority = higher priority.
     */
    private int normalizeTaskGroupPriority(int taskGroupPriority) {
        // taskGroupPriority is typically 0-10, map to 10-100
        return Math.min(100, Math.max(10, taskGroupPriority * 10));
    }
}
