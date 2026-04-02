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

-- ============================================================
-- Runtime Management Extension - Phase 1 & 2
-- Adds priority_weight and weight_score fields for priority-aware scheduling
-- ============================================================

-- 1. Add priority_weight to t_ds_task_instance
ALTER TABLE t_ds_task_instance ADD COLUMN IF NOT EXISTS priority_weight int DEFAULT NULL;
COMMENT ON COLUMN t_ds_task_instance.priority_weight IS 'Dynamic priority weight for runtime management (1-100, default 50)';

-- 2. Add priority_weight to t_ds_workflow_instance
ALTER TABLE t_ds_workflow_instance ADD COLUMN IF NOT EXISTS priority_weight int DEFAULT NULL;
COMMENT ON COLUMN t_ds_workflow_instance.priority_weight IS 'Dynamic priority weight for runtime management (1-100, default 50)';

-- 3. Add weight_score to t_ds_task_group_queue
ALTER TABLE t_ds_task_group_queue ADD COLUMN IF NOT EXISTS weight_score int NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_ds_task_group_queue.weight_score IS 'Weighted priority score calculated by PriorityWeightEngine';

-- 4. Add composite index on (priority, weight_score) for priority-aware queries
DROP INDEX IF EXISTS idx_task_group_queue_priority_weight_score;
CREATE INDEX IF NOT EXISTS idx_task_group_queue_priority_weight_score ON t_ds_task_group_queue USING btree(priority, weight_score DESC);

-- 5. Add index on priority_weight for task_instance queries
DROP INDEX IF EXISTS idx_task_instance_priority_weight;
CREATE INDEX IF NOT EXISTS idx_task_instance_priority_weight ON t_ds_task_instance USING btree(priority_weight);
