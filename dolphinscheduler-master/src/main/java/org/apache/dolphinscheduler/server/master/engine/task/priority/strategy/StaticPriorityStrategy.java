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

package org.apache.dolphinscheduler.server.master.engine.task.priority.strategy;

import org.apache.dolphinscheduler.server.master.engine.task.priority.PriorityFactors;
import org.apache.dolphinscheduler.server.master.engine.task.priority.PriorityStrategy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * StaticPriorityStrategy uses fixed weights for all priority factors.
 *
 * Formula:
 * score = basePriority * w1 + waitTimeFactor * w2 + retryPenalty * w3
 * + dependencyDepth * w4 + resourceUrgency * w5 + dynamicWeight * w6
 *
 * Default weights:
 * w1 (basePriority) = 0.20
 * w2 (waitTime) = 0.15
 * w3 (retryCount) = 0.10
 * w4 (dependencyDepth) = 0.10
 * w5 (resourceUrgency) = 0.15
 * w6 (dynamicWeight) = 0.30
 */
@Slf4j
@Component
public class StaticPriorityStrategy implements PriorityStrategy {

    private static final double WEIGHT_BASE_PRIORITY = 0.20;
    private static final double WEIGHT_WAIT_TIME = 0.15;
    private static final double WEIGHT_RETRY_COUNT = 0.10;
    private static final double WEIGHT_DEPENDENCY_DEPTH = 0.10;
    private static final double WEIGHT_RESOURCE_URGENCY = 0.15;
    private static final double WEIGHT_DYNAMIC_WEIGHT = 0.30;

    private static final long MAX_WAIT_TIME_SECONDS = 1800;

    @Override
    public int calculate(PriorityFactors factors) {
        double basePriorityNorm = normalize(factors.getBasePriority(), 1, 100);
        double waitTimeNorm = normalizeWaitTime(factors.getWaitTimeSeconds());
        double retryPenalty = normalizeRetryPenalty(factors.getRetryCount());
        double depthNorm = normalize(factors.getDependencyDepth(), 0, 20);
        double urgencyNorm = normalize(factors.getResourceUrgency(), 1, 100);
        double dynamicNorm = normalize(factors.getDynamicWeight(), 1, 100);

        double score = basePriorityNorm * WEIGHT_BASE_PRIORITY
                + waitTimeNorm * WEIGHT_WAIT_TIME
                + retryPenalty * WEIGHT_RETRY_COUNT
                + depthNorm * WEIGHT_DEPENDENCY_DEPTH
                + urgencyNorm * WEIGHT_RESOURCE_URGENCY
                + dynamicNorm * WEIGHT_DYNAMIC_WEIGHT;

        return clamp((int) Math.round(score * 100), 1, 100);
    }

    @Override
    public String getName() {
        return "STATIC";
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
