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

import lombok.Data;

/**
 * PriorityFactors holds all input factors used by PriorityScoreCalculator
 * to compute the final weighted priority score.
 *
 * Each factor represents a dimension that influences task scheduling priority:
 * - basePriority: static priority from task definition (1-100)
 * - waitTimeSeconds: how long the task has been waiting (anti-starvation)
 * - retryCount: number of retry attempts (higher retry = slightly lower priority)
 * - dependencyDepth: number of upstream dependencies (deeper = higher priority)
 * - resourceUrgency: workflow-level urgency indicator (1-100)
 * - dynamicWeight: runtime-adjusted weight (1-100, default 50)
 */
@Data
public class PriorityFactors {

    /**
     * Base priority derived from task definition's taskGroupPriority.
     * Range: 1-100, default: 50.
     */
    private int basePriority = 50;

    /**
     * How long the task has been waiting in queue (in seconds).
     * Used for anti-starvation: longer wait = higher boost.
     */
    private long waitTimeSeconds = 0;

    /**
     * Number of retry attempts. Higher retry count slightly reduces priority
     * to prevent runaway tasks from consuming excessive resources.
     */
    private int retryCount = 0;

    /**
     * Dependency depth (number of upstream tasks that must complete first).
     * Deeper tasks get slightly higher priority to unblock downstream work.
     */
    private int dependencyDepth = 0;

    /**
     * Resource urgency derived from workflow instance priority.
     * Range: 1-100, default: 50.
     */
    private int resourceUrgency = 50;

    /**
     * Dynamic weight set at runtime via priority adjustment API.
     * Range: 1-100, default: 50.
     */
    private int dynamicWeight = 50;
}
