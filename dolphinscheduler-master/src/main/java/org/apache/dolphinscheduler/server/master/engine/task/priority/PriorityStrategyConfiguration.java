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

import org.apache.dolphinscheduler.server.master.engine.task.priority.strategy.DynamicPriorityStrategy;
import org.apache.dolphinscheduler.server.master.engine.task.priority.strategy.HybridPriorityStrategy;
import org.apache.dolphinscheduler.server.master.engine.task.priority.strategy.StaticPriorityStrategy;

import lombok.Data;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * PriorityStrategyConfiguration selects the active PriorityStrategy based on configuration.
 *
 * Supported strategies: STATIC, DYNAMIC, HYBRID (default)
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "master.priority")
public class PriorityStrategyConfiguration {

    /**
     * Active strategy name: STATIC, DYNAMIC, or HYBRID.
     */
    private String strategy = "HYBRID";

    @Autowired
    private StaticPriorityStrategy staticStrategy;

    @Autowired
    private DynamicPriorityStrategy dynamicStrategy;

    @Autowired
    private HybridPriorityStrategy hybridStrategy;

    @Bean
    public PriorityStrategy priorityStrategy() {
        switch (strategy.toUpperCase()) {
            case "STATIC":
                return staticStrategy;
            case "DYNAMIC":
                return dynamicStrategy;
            case "HYBRID":
            default:
                return hybridStrategy;
        }
    }
}
