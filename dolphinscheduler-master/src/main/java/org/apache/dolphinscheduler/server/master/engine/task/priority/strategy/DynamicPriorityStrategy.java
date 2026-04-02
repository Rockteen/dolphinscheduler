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
 * DynamicPriorityStrategy adapts weights based on runtime conditions.
 *
 * Key differences from StaticPriorityStrategy:
 * - Increases waitTime weight when tasks have been waiting long (anti-starvation)
 * - Increases retry penalty when retryCount is high (runaway task protection)
 * - Boosts dynamicWeight influence for runtime-adjusted priorities
 */
@Slf4j
@Component
public class DynamicPriorityStrategy implements PriorityStrategy {

    private static final long ANTI_STARVATION_THRESHOLD_SECONDS = 600;
    private static final int HIGH_RETRY_THRESHOLD = 3;
    private static final double BOOST_FACTOR = 1.5;

    @Override
    public int calculate(PriorityFactors factors) {
        double baseWeight = 0.20;
        double waitWeight = 0.15;
        double retryWeight = 0.10;
        double depthWeight = 0.10;
        double urgencyWeight = 0.15;
        double dynamicWeight = 0.30;

        if (factors.getWaitTimeSeconds() > ANTI_STARVATION_THRESHOLD_SECONDS) {
            waitWeight *= BOOST_FACTOR;
            baseWeight *= 0.8;
        }

        if (factors.getRetryCount() > HIGH_RETRY_THRESHOLD) {
            retryWeight *= BOOST_FACTOR;
            dynamicWeight *= 0.8;
        }

        if (factors.getDynamicWeight() > 70) {
            dynamicWeight *= BOOST_FACTOR;
            baseWeight *= 0.7;
        }

        double totalWeight = baseWeight + waitWeight + retryWeight + depthWeight + urgencyWeight + dynamicWeight;

        double baseNorm = normalize(factors.getBasePriority(), 1, 100);
        double waitNorm = normalizeWaitTime(factors.getWaitTimeSeconds());
        double retryPenalty = normalizeRetryPenalty(factors.getRetryCount());
        double depthNorm = normalize(factors.getDependencyDepth(), 0, 20);
        double urgencyNorm = normalize(factors.getResourceUrgency(), 1, 100);
        double dynamicNorm = normalize(factors.getDynamicWeight(), 1, 100);

        double score = (baseNorm * baseWeight
                + waitNorm * waitWeight
                + retryPenalty * retryWeight
                + depthNorm * depthWeight
                + urgencyNorm * urgencyWeight
                + dynamicNorm * dynamicWeight) / totalWeight;

        return clamp((int) Math.round(score * 100), 1, 100);
    }

    @Override
    public String getName() {
        return "DYNAMIC";
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
        return Math.min(1.0, (double) waitTimeSeconds / 1800);
    }

    private double normalizeRetryPenalty(int retryCount) {
        if (retryCount <= 0) {
            return 1.0;
        }
        return Math.max(0, 1.0 - (retryCount * 0.15));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
