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

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * PriorityScoreCalculator is the core scoring engine that converts PriorityFactors
 * into a weighted priority score (1-100).
 *
 * This is the default implementation used by PriorityWeightEngine.
 * The formula blends multiple dimensions:
 * - basePriority (20%): static priority from task definition
 * - waitTime (15%): anti-starvation boost for long-waiting tasks
 * - retryCount (10%): penalty for frequently-failing tasks
 * - dependencyDepth (10%): boost for tasks that unblock downstream work
 * - resourceUrgency (15%): workflow-level urgency
 * - dynamicWeight (30%): runtime-adjusted weight (highest influence)
 */
@Slf4j
@Component
public class PriorityScoreCalculator {

    private static final double WEIGHT_BASE_PRIORITY = 0.20;
    private static final double WEIGHT_WAIT_TIME = 0.15;
    private static final double WEIGHT_RETRY_COUNT = 0.10;
    private static final double WEIGHT_DEPENDENCY_DEPTH = 0.10;
    private static final double WEIGHT_RESOURCE_URGENCY = 0.15;
    private static final double WEIGHT_DYNAMIC_WEIGHT = 0.30;

    private static final long MAX_WAIT_TIME_SECONDS = 1800;

    /**
     * Calculate the weighted priority score.
     *
     * @param factors the input priority factors
     * @return score in range 1-100 (higher = higher priority)
     */
    public int calculate(PriorityFactors factors) {
        double baseNorm = normalize(factors.getBasePriority(), 1, 100);
        double waitNorm = normalizeWaitTime(factors.getWaitTimeSeconds());
        double retryPenalty = normalizeRetryPenalty(factors.getRetryCount());
        double depthNorm = normalize(factors.getDependencyDepth(), 0, 20);
        double urgencyNorm = normalize(factors.getResourceUrgency(), 1, 100);
        double dynamicNorm = normalize(factors.getDynamicWeight(), 1, 100);

        double score = baseNorm * WEIGHT_BASE_PRIORITY
                + waitNorm * WEIGHT_WAIT_TIME
                + retryPenalty * WEIGHT_RETRY_COUNT
                + depthNorm * WEIGHT_DEPENDENCY_DEPTH
                + urgencyNorm * WEIGHT_RESOURCE_URGENCY
                + dynamicNorm * WEIGHT_DYNAMIC_WEIGHT;

        return clamp((int) Math.round(score * 100), 1, 100);
    }

    private double normalize(double value, double min, double max) {
        if (max == min) {
            return 0.5;
        }
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    private double normalizeWaitTime(long waitTimeSeconds) {
        if (waitTimeSeconds <= 0) {
            return 0;
        }
        return Math.min(1.0, (double) waitTimeSeconds / MAX_WAIT_TIME_SECONDS);
    }

    private double normalizeRetryPenalty(int retryCount) {
        if (retryCount <= 0) {
            return 1.0;
        }
        return Math.max(0, 1.0 - (retryCount * 0.2));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
