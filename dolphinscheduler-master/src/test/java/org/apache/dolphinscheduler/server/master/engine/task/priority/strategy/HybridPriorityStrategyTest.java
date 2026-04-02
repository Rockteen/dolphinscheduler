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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridPriorityStrategyTest extends AbstractPriorityStrategyTest {

    @BeforeEach
    @Override
    void setUp() {
        strategy = new HybridPriorityStrategy();
    }

    @Test
    void calculate_shouldBlendStaticAndDynamicScores() {
        PriorityFactors factors = createDefaultFactors();

        StaticPriorityStrategy staticStrategy = new StaticPriorityStrategy();
        DynamicPriorityStrategy dynamicStrategy = new DynamicPriorityStrategy();

        int staticScore = staticStrategy.calculate(factors);
        int dynamicScore = dynamicStrategy.calculate(factors);
        int hybridScore = strategy.calculate(factors);

        int expectedBlend = (int) Math.round(staticScore * 0.6 + dynamicScore * 0.4);
        assertEquals(expectedBlend, hybridScore, 2,
                "Hybrid score should be blend of static and dynamic: static=" + staticScore + ", dynamic="
                        + dynamicScore + ", hybrid=" + hybridScore);
    }

    @Test
    void getName_shouldReturnHybrid() {
        assertEquals("HYBRID", strategy.getName());
    }
}
