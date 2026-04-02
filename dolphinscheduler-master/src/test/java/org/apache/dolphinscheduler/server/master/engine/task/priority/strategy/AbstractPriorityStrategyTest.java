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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.dolphinscheduler.server.master.engine.task.priority.PriorityFactors;
import org.apache.dolphinscheduler.server.master.engine.task.priority.PriorityStrategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class AbstractPriorityStrategyTest {

    protected PriorityStrategy strategy;

    @BeforeEach
    abstract void setUp();

    @Test
    void calculate_shouldReturnScoreInRange() {
        PriorityFactors factors = createDefaultFactors();
        int score = strategy.calculate(factors);
        assertTrue(score >= 1 && score <= 100, "Score should be in range 1-100, got: " + score);
    }

    @Test
    void calculate_higherBasePriorityShouldYieldHigherScore() {
        PriorityFactors low = createDefaultFactors();
        low.setBasePriority(10);

        PriorityFactors high = createDefaultFactors();
        high.setBasePriority(90);

        int lowScore = strategy.calculate(low);
        int highScore = strategy.calculate(high);

        assertTrue(highScore > lowScore,
                "Higher basePriority should yield higher score: high=" + highScore + ", low=" + lowScore);
    }

    @Test
    void calculate_higherDynamicWeightShouldYieldHigherScore() {
        PriorityFactors low = createDefaultFactors();
        low.setDynamicWeight(10);

        PriorityFactors high = createDefaultFactors();
        high.setDynamicWeight(90);

        int lowScore = strategy.calculate(low);
        int highScore = strategy.calculate(high);

        assertTrue(highScore > lowScore,
                "Higher dynamicWeight should yield higher score: high=" + highScore + ", low=" + lowScore);
    }

    @Test
    void calculate_higherRetryCountShouldDecreaseScore() {
        PriorityFactors noRetry = createDefaultFactors();
        noRetry.setRetryCount(0);

        PriorityFactors manyRetries = createDefaultFactors();
        manyRetries.setRetryCount(5);

        int noRetryScore = strategy.calculate(noRetry);
        int manyRetriesScore = strategy.calculate(manyRetries);

        assertTrue(noRetryScore >= manyRetriesScore,
                "More retries should yield equal or lower score: noRetry=" + noRetryScore + ", retries="
                        + manyRetriesScore);
    }

    @Test
    void calculate_longerWaitTimeShouldIncreaseScore() {
        PriorityFactors shortWait = createDefaultFactors();
        shortWait.setWaitTimeSeconds(10);

        PriorityFactors longWait = createDefaultFactors();
        longWait.setWaitTimeSeconds(1200);

        int shortWaitScore = strategy.calculate(shortWait);
        int longWaitScore = strategy.calculate(longWait);

        assertTrue(longWaitScore >= shortWaitScore,
                "Longer wait should yield equal or higher score: short=" + shortWaitScore + ", long=" + longWaitScore);
    }

    @Test
    void calculate_higherResourceUrgencyShouldYieldHigherScore() {
        PriorityFactors low = createDefaultFactors();
        low.setResourceUrgency(10);

        PriorityFactors high = createDefaultFactors();
        high.setResourceUrgency(90);

        int lowScore = strategy.calculate(low);
        int highScore = strategy.calculate(high);

        assertTrue(highScore > lowScore,
                "Higher resourceUrgency should yield higher score: high=" + highScore + ", low=" + lowScore);
    }

    @Test
    void calculate_defaultFactorsShouldReturnValidScore() {
        PriorityFactors factors = createDefaultFactors();
        int score = strategy.calculate(factors);
        assertTrue(score >= 1 && score <= 100, "Default factors should yield valid score, got: " + score);
    }

    @Test
    void getName_shouldReturnNonEmptyString() {
        String name = strategy.getName();
        assertTrue(name != null && !name.isEmpty(), "Strategy name should not be empty");
    }

    protected PriorityFactors createDefaultFactors() {
        PriorityFactors factors = new PriorityFactors();
        factors.setBasePriority(50);
        factors.setWaitTimeSeconds(0);
        factors.setRetryCount(0);
        factors.setDependencyDepth(0);
        factors.setResourceUrgency(50);
        factors.setDynamicWeight(50);
        return factors;
    }
}
