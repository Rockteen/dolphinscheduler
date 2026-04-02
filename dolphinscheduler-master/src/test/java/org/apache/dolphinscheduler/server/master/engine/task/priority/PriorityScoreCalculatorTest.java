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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PriorityScoreCalculatorTest {

    private PriorityScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new PriorityScoreCalculator();
    }

    @Test
    void calculate_defaultFactorsShouldReturnMidRangeScore() {
        PriorityFactors factors = new PriorityFactors();
        int score = calculator.calculate(factors);
        assertTrue(score >= 30 && score <= 70,
                "Default factors should yield mid-range score, got: " + score);
    }

    @Test
    void calculate_maxFactorsShouldReturnHighScore() {
        PriorityFactors factors = new PriorityFactors();
        factors.setBasePriority(100);
        factors.setWaitTimeSeconds(1800);
        factors.setRetryCount(0);
        factors.setDependencyDepth(20);
        factors.setResourceUrgency(100);
        factors.setDynamicWeight(100);

        int score = calculator.calculate(factors);
        assertTrue(score >= 90 && score <= 100,
                "Max factors should yield high score, got: " + score);
    }

    @Test
    void calculate_minFactorsShouldReturnLowScore() {
        PriorityFactors factors = new PriorityFactors();
        factors.setBasePriority(1);
        factors.setWaitTimeSeconds(0);
        factors.setRetryCount(5);
        factors.setDependencyDepth(0);
        factors.setResourceUrgency(1);
        factors.setDynamicWeight(1);

        int score = calculator.calculate(factors);
        assertTrue(score >= 1 && score <= 30,
                "Min factors should yield low score, got: " + score);
    }

    @Test
    void calculate_dynamicWeightHasHighestInfluence() {
        PriorityFactors base = new PriorityFactors();
        base.setBasePriority(50);
        base.setResourceUrgency(50);

        PriorityFactors highDynamic = new PriorityFactors();
        highDynamic.setBasePriority(50);
        highDynamic.setResourceUrgency(50);
        highDynamic.setDynamicWeight(100);

        PriorityFactors lowDynamic = new PriorityFactors();
        lowDynamic.setBasePriority(50);
        lowDynamic.setResourceUrgency(50);
        lowDynamic.setDynamicWeight(1);

        int baseScore = calculator.calculate(base);
        int highScore = calculator.calculate(highDynamic);
        int lowScore = calculator.calculate(lowDynamic);

        assertTrue(highScore > baseScore && baseScore > lowScore,
                "Dynamic weight should have highest influence: low=" + lowScore + ", base=" + baseScore
                        + ", high=" + highScore);
    }

    @Test
    void calculate_retryCountPenaltyShouldBeBounded() {
        PriorityFactors factors = new PriorityFactors();
        factors.setBasePriority(50);
        factors.setResourceUrgency(50);
        factors.setDynamicWeight(50);

        int zeroRetry = calculator.calculate(factors);

        factors.setRetryCount(10);
        int tenRetries = calculator.calculate(factors);

        assertTrue(tenRetries >= 1, "Score should never go below 1 even with many retries, got: " + tenRetries);
        assertTrue(tenRetries <= zeroRetry, "More retries should not increase score: zero=" + zeroRetry
                + ", ten=" + tenRetries);
    }

    @Test
    void calculate_waitTimeAntiStarvationShouldCapAtMax() {
        PriorityFactors factors = new PriorityFactors();
        factors.setBasePriority(10);
        factors.setResourceUrgency(10);
        factors.setDynamicWeight(10);

        factors.setWaitTimeSeconds(1800);
        int cappedWait = calculator.calculate(factors);

        factors.setWaitTimeSeconds(3600);
        int overCappedWait = calculator.calculate(factors);

        assertEquals(cappedWait, overCappedWait,
                "Wait time beyond max should not increase score further: capped=" + cappedWait
                        + ", overCapped=" + overCappedWait);
    }
}
