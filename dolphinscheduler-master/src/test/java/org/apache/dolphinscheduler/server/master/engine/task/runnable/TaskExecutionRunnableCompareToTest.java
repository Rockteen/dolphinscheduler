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

package org.apache.dolphinscheduler.server.master.engine.task.runnable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.apache.dolphinscheduler.common.enums.Priority;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.entity.WorkflowDefinition;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.server.master.engine.WorkflowEventBus;
import org.apache.dolphinscheduler.server.master.engine.graph.IWorkflowExecutionGraph;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskExecutionRunnableCompareToTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private IWorkflowExecutionGraph workflowExecutionGraph;

    @Mock
    private WorkflowEventBus workflowEventBus;

    private WorkflowDefinition workflowDefinition;
    private WorkflowInstance workflowInstance;
    private TaskDefinition taskDefinition;
    private TaskInstance taskInstance;

    @BeforeEach
    void setUp() {
        workflowDefinition = new WorkflowDefinition();

        workflowInstance = new WorkflowInstance();
        workflowInstance.setId(1);
        workflowInstance.setWorkflowInstancePriority(Priority.MEDIUM);

        taskDefinition = new TaskDefinition();
        taskDefinition.setName("test_task");
        taskDefinition.setTaskGroupPriority(5);

        taskInstance = new TaskInstance();
        taskInstance.setId(1);
        taskInstance.setName("test_task");
        taskInstance.setTaskInstancePriority(Priority.MEDIUM);
        taskInstance.setFirstSubmitTime(new Date(System.currentTimeMillis() - 10000));
    }

    private TaskExecutionRunnable createRunnable(TaskInstance ti, WorkflowInstance wi,
            TaskDefinition td) {
        TaskExecutionRunnableBuilder builder = TaskExecutionRunnableBuilder.builder()
                .applicationContext(applicationContext)
                .workflowExecutionGraph(workflowExecutionGraph)
                .workflowEventBus(workflowEventBus)
                .workflowDefinition(workflowDefinition)
                .project(new org.apache.dolphinscheduler.dao.entity.Project())
                .workflowInstance(wi)
                .taskDefinition(td)
                .taskInstance(ti)
                .build();
        return new TaskExecutionRunnable(builder);
    }

    @Test
    void compareTo_nullShouldReturnPositive() {
        TaskExecutionRunnable runnable = createRunnable(taskInstance, workflowInstance, taskDefinition);
        int result = runnable.compareTo(null);
        assertTrue(result > 0, "compareTo(null) should return positive value");
    }

    @Test
    void compareTo_higherPriorityWeightShouldHaveHigherPriority() {
        TaskInstance highWeight = new TaskInstance();
        highWeight.setId(2);
        highWeight.setName("high_weight_task");
        highWeight.setTaskInstancePriority(Priority.MEDIUM);
        highWeight.setPriorityWeight(90);
        highWeight.setFirstSubmitTime(new Date(System.currentTimeMillis() - 10000));

        TaskInstance lowWeight = new TaskInstance();
        lowWeight.setId(3);
        lowWeight.setName("low_weight_task");
        lowWeight.setTaskInstancePriority(Priority.MEDIUM);
        lowWeight.setPriorityWeight(10);
        lowWeight.setFirstSubmitTime(new Date(System.currentTimeMillis() - 10000));

        TaskExecutionRunnable high = createRunnable(highWeight, workflowInstance, taskDefinition);
        TaskExecutionRunnable low = createRunnable(lowWeight, workflowInstance, taskDefinition);

        int result = high.compareTo(low);
        assertTrue(result < 0, "Higher priorityWeight should come first (negative result), got: " + result);
    }

    @Test
    void compareTo_samePriorityWeightShouldFallBackToWorkflowPriority() {
        WorkflowInstance highWf = new WorkflowInstance();
        highWf.setId(1);
        highWf.setWorkflowInstancePriority(Priority.HIGHEST);

        WorkflowInstance lowWf = new WorkflowInstance();
        lowWf.setId(2);
        lowWf.setWorkflowInstancePriority(Priority.LOWEST);

        TaskInstance ti1 = new TaskInstance();
        ti1.setId(1);
        ti1.setName("task1");
        ti1.setTaskInstancePriority(Priority.MEDIUM);
        ti1.setFirstSubmitTime(new Date());

        TaskInstance ti2 = new TaskInstance();
        ti2.setId(2);
        ti2.setName("task2");
        ti2.setTaskInstancePriority(Priority.MEDIUM);
        ti2.setFirstSubmitTime(new Date());

        TaskExecutionRunnable high = createRunnable(ti1, highWf, taskDefinition);
        TaskExecutionRunnable low = createRunnable(ti2, lowWf, taskDefinition);

        int result = high.compareTo(low);
        assertTrue(result < 0, "Higher workflow priority should come first, got: " + result);
    }

    @Test
    void compareTo_sameAllPrioritiesShouldFallBackToSubmitTime() {
        Date earlier = new Date(System.currentTimeMillis() - 20000);
        Date later = new Date(System.currentTimeMillis() - 10000);

        TaskInstance early = new TaskInstance();
        early.setId(1);
        early.setName("early_task");
        early.setTaskInstancePriority(Priority.MEDIUM);
        early.setFirstSubmitTime(earlier);

        TaskInstance late = new TaskInstance();
        late.setId(2);
        late.setName("late_task");
        late.setTaskInstancePriority(Priority.MEDIUM);
        late.setFirstSubmitTime(later);

        TaskExecutionRunnable earlyRunnable = createRunnable(early, workflowInstance, taskDefinition);
        TaskExecutionRunnable lateRunnable = createRunnable(late, workflowInstance, taskDefinition);

        int result = earlyRunnable.compareTo(lateRunnable);
        assertTrue(result < 0, "Earlier submit time should come first, got: " + result);
    }

    @Test
    void compareTo_taskGroupPriorityShouldAffectOrder() {
        TaskDefinition highGroupPriority = new TaskDefinition();
        highGroupPriority.setName("high_group_task");
        highGroupPriority.setTaskGroupPriority(10);

        TaskDefinition lowGroupPriority = new TaskDefinition();
        lowGroupPriority.setName("low_group_task");
        lowGroupPriority.setTaskGroupPriority(1);

        TaskInstance ti1 = new TaskInstance();
        ti1.setId(1);
        ti1.setName("task1");
        ti1.setTaskInstancePriority(Priority.MEDIUM);
        ti1.setFirstSubmitTime(new Date());

        TaskInstance ti2 = new TaskInstance();
        ti2.setId(2);
        ti2.setName("task2");
        ti2.setTaskInstancePriority(Priority.MEDIUM);
        ti2.setFirstSubmitTime(new Date());

        TaskExecutionRunnable high = createRunnable(ti1, workflowInstance, highGroupPriority);
        TaskExecutionRunnable low = createRunnable(ti2, workflowInstance, lowGroupPriority);

        int result = high.compareTo(low);
        assertTrue(result < 0, "Higher taskGroupPriority should come first, got: " + result);
    }
}
