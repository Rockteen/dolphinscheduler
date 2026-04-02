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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.dolphinscheduler.common.enums.Priority;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.TaskGroupQueue;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.server.master.engine.task.priority.strategy.HybridPriorityStrategy;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriorityWeightEngineTest {

    private PriorityWeightEngine priorityWeightEngine;
    private PriorityScoreCalculator scoreCalculator;

    private TaskInstance taskInstance;
    private TaskDefinition taskDefinition;
    private WorkflowInstance workflowInstance;

    @BeforeEach
    void setUp() {
        scoreCalculator = new PriorityScoreCalculator();
        priorityWeightEngine = new PriorityWeightEngine();
        // Use reflection to inject dependencies since we're not using Spring in tests
        try {
            java.lang.reflect.Field strategyField = PriorityWeightEngine.class.getDeclaredField("priorityStrategy");
            strategyField.setAccessible(true);
            strategyField.set(priorityWeightEngine, new HybridPriorityStrategy());

            java.lang.reflect.Field calcField = PriorityWeightEngine.class.getDeclaredField("scoreCalculator");
            calcField.setAccessible(true);
            calcField.set(priorityWeightEngine, scoreCalculator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        taskInstance = new TaskInstance();
        taskInstance.setId(1);
        taskInstance.setName("test_task");
        taskInstance.setTaskInstancePriority(Priority.MEDIUM);
        taskInstance.setRetryTimes(0);
        taskInstance.setFirstSubmitTime(new Date());

        taskDefinition = new TaskDefinition();
        taskDefinition.setName("test_task");
        taskDefinition.setTaskGroupPriority(5);

        workflowInstance = new WorkflowInstance();
        workflowInstance.setId(1);
        workflowInstance.setWorkflowInstancePriority(Priority.MEDIUM);
    }

    @Test
    void calculateInitialScore_shouldReturnValidScore() {
        int score = priorityWeightEngine.calculateInitialScore(taskInstance, taskDefinition, workflowInstance);

        assertNotNull(score);
        assertTrue(score >= 1 && score <= 100, "Score should be in range 1-100, got: " + score);
    }

    @Test
    void calculateInitialScore_higherPriorityShouldYieldHigherScore() {
        TaskDefinition highPriorityDef = new TaskDefinition();
        highPriorityDef.setTaskGroupPriority(10);

        TaskDefinition lowPriorityDef = new TaskDefinition();
        lowPriorityDef.setTaskGroupPriority(1);

        int highScore = priorityWeightEngine.calculateInitialScore(taskInstance, highPriorityDef, workflowInstance);
        int lowScore = priorityWeightEngine.calculateInitialScore(taskInstance, lowPriorityDef, workflowInstance);

        assertTrue(highScore > lowScore,
                "Higher taskGroupPriority should yield higher score: high=" + highScore + ", low=" + lowScore);
    }

    @Test
    void calculateInitialScore_dynamicWeightOverrideShouldIncreaseScore() {
        taskInstance.setPriorityWeight(90);
        int scoreWithHighWeight = priorityWeightEngine.calculateInitialScore(taskInstance, taskDefinition,
                workflowInstance);

        taskInstance.setPriorityWeight(10);
        int scoreWithLowWeight = priorityWeightEngine.calculateInitialScore(taskInstance, taskDefinition,
                workflowInstance);

        assertTrue(scoreWithHighWeight > scoreWithLowWeight,
                "Higher dynamic weight should yield higher score: high=" + scoreWithHighWeight + ", low="
                        + scoreWithLowWeight);
    }

    @Test
    void calculateInitialScore_workflowPriorityAffectsScore() {
        workflowInstance.setWorkflowInstancePriority(Priority.HIGHEST);
        int highPriorityScore = priorityWeightEngine.calculateInitialScore(taskInstance, taskDefinition,
                workflowInstance);

        workflowInstance.setWorkflowInstancePriority(Priority.LOWEST);
        int lowPriorityScore = priorityWeightEngine.calculateInitialScore(taskInstance, taskDefinition,
                workflowInstance);

        assertTrue(highPriorityScore > lowPriorityScore,
                "Higher workflow priority should yield higher score: high=" + highPriorityScore + ", low="
                        + lowPriorityScore);
    }

    @Test
    void recalculateScores_shouldUpdateScoresForWaitingTasks() {
        List<TaskGroupQueue> queues = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TaskGroupQueue queue = new TaskGroupQueue();
            queue.setId(i + 1);
            queue.setTaskId(i + 1);
            queue.setTaskName("task_" + i);
            queue.setWeightScore(0);
            queue.setCreateTime(new Date(System.currentTimeMillis() - 60000 * (i + 1)));
            queues.add(queue);
        }

        Map<Integer, TaskInstance> taskInstances = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            TaskInstance ti = new TaskInstance();
            ti.setId(i + 1);
            ti.setName("task_" + i);
            ti.setTaskInstancePriority(Priority.MEDIUM);
            ti.setTaskCode(100L + i);
            ti.setWorkflowInstanceId(1);
            taskInstances.put(i + 1, ti);
        }

        Map<Integer, WorkflowInstance> workflowInstances = new HashMap<>();
        workflowInstances.put(1, workflowInstance);

        List<TaskGroupQueue> updatedQueues = priorityWeightEngine.recalculateScores(queues, taskInstances,
                Map.of(), workflowInstances);

        for (TaskGroupQueue queue : updatedQueues) {
            assertTrue(queue.getWeightScore() >= 1 && queue.getWeightScore() <= 100,
                    "Score should be in range 1-100 for queue " + queue.getId() + ", got: " + queue.getWeightScore());
        }
    }

    @Test
    void recalculateScores_longerWaitTimeShouldIncreaseScore() {
        List<TaskGroupQueue> queues = new ArrayList<>();

        TaskGroupQueue shortWait = new TaskGroupQueue();
        shortWait.setId(1);
        shortWait.setTaskId(1);
        shortWait.setWeightScore(0);
        shortWait.setCreateTime(new Date(System.currentTimeMillis() - 10000));

        TaskGroupQueue longWait = new TaskGroupQueue();
        longWait.setId(2);
        longWait.setTaskId(2);
        longWait.setWeightScore(0);
        longWait.setCreateTime(new Date(System.currentTimeMillis() - 600000));

        queues.add(shortWait);
        queues.add(longWait);

        Map<Integer, TaskInstance> taskInstances = new HashMap<>();
        for (int i = 1; i <= 2; i++) {
            TaskInstance ti = new TaskInstance();
            ti.setId(i);
            ti.setName("task_" + i);
            ti.setTaskInstancePriority(Priority.MEDIUM);
            ti.setTaskCode(100L);
            ti.setWorkflowInstanceId(1);
            taskInstances.put(i, ti);
        }

        Map<Integer, WorkflowInstance> workflowInstances = new HashMap<>();
        workflowInstances.put(1, workflowInstance);

        List<TaskGroupQueue> updatedQueues = priorityWeightEngine.recalculateScores(queues, taskInstances,
                Map.of(), workflowInstances);

        int shortWaitScore = updatedQueues.get(0).getWeightScore();
        int longWaitScore = updatedQueues.get(1).getWeightScore();

        assertTrue(longWaitScore >= shortWaitScore,
                "Longer wait time should yield equal or higher score: short=" + shortWaitScore + ", long="
                        + longWaitScore);
    }
}
