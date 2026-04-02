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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HybridPriorityStrategy combines StaticPriorityStrategy and DynamicPriorityStrategy.
 *
 * Uses static weights as baseline, then applies dynamic adjustments on top.
 * This provides predictable behavior with adaptive improvements.
 */
@Slf4j
@Component
public class HybridPriorityStrategy implements PriorityStrategy {

    private final StaticPriorityStrategy staticStrategy;
    private final DynamicPriorityStrategy dynamicStrategy;

    private static final double STATIC_BLEND = 0.6;
    private static final double DYNAMIC_BLEND = 0.4;

    public HybridPriorityStrategy() {
        this.staticStrategy = null;
        this.dynamicStrategy = null;
    }

    @Autowired
    public HybridPriorityStrategy(StaticPriorityStrategy staticStrategy, DynamicPriorityStrategy dynamicStrategy) {
        this.staticStrategy = staticStrategy;
        this.dynamicStrategy = dynamicStrategy;
    }

    @Override
    public int calculate(PriorityFactors factors) {
        StaticPriorityStrategy staticStrat = this.staticStrategy != null ? this.staticStrategy
                : new StaticPriorityStrategy();
        DynamicPriorityStrategy dynamicStrat = this.dynamicStrategy != null ? this.dynamicStrategy
                : new DynamicPriorityStrategy();

        int staticScore = staticStrat.calculate(factors);
        int dynamicScore = dynamicStrat.calculate(factors);

        int blendedScore = (int) Math.round(staticScore * STATIC_BLEND + dynamicScore * DYNAMIC_BLEND);
        return Math.max(1, Math.min(100, blendedScore));
    }

    @Override
    public String getName() {
        return "HYBRID";
    }
}
