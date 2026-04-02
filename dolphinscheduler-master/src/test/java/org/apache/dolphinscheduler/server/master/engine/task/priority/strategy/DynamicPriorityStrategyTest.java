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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.dolphinscheduler.server.master.engine.task.priority.PriorityFactors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicPriorityStrategyTest extends AbstractPriorityStrategyTest {

    @BeforeEach
    @Override
    void setUp() {
        strategy = new DynamicPriorityStrategy();
    }

    @Test
    void calculate_antiStarvationShouldBoostLongWaitingTasks() {
        PriorityFactors normal = createDefaultFactors();
        normal.setWaitTimeSeconds(100);

        PriorityFactors starving = createDefaultFactors();
        starving.setWaitTimeSeconds(700);

        int normalScore = strategy.calculate(normal);
        int starvingScore = strategy.calculate(starving);

        assertTrue(starvingScore > normalScore,
                "Anti-starvation should boost long-waiting tasks: normal=" + normalScore + ", starving="
                        + starvingScore);
    }

    @Test
    void calculate_highRetryShouldIncreasePenalty() {
        PriorityFactors lowRetry = createDefaultFactors();
        lowRetry.setRetryCount(1);

        PriorityFactors highRetry = createDefaultFactors();
        highRetry.setRetryCount(5);

        int lowRetryScore = strategy.calculate(lowRetry);
        int highRetryScore = strategy.calculate(highRetry);

        assertTrue(lowRetryScore > highRetryScore,
                "Dynamic strategy should penalize high retry counts more: low=" + lowRetryScore + ", high="
                        + highRetryScore);
    }
}
