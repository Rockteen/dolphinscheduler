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
drop PROCEDURE if EXISTS add_t_ds_task_instance_priority_weight;
delimiter d//
CREATE PROCEDURE add_t_ds_task_instance_priority_weight()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_NAME='t_ds_task_instance'
        AND TABLE_SCHEMA=(SELECT DATABASE())
        AND COLUMN_NAME='priority_weight')
    THEN
        ALTER TABLE `t_ds_task_instance` ADD COLUMN `priority_weight` int(11) DEFAULT NULL COMMENT 'Dynamic priority weight for runtime management (1-100, default 50)' AFTER `task_instance_priority`;
    END IF;
END;

d//
delimiter ;
CALL add_t_ds_task_instance_priority_weight;
DROP PROCEDURE add_t_ds_task_instance_priority_weight;

-- 2. Add priority_weight to t_ds_workflow_instance
drop PROCEDURE if EXISTS add_t_ds_workflow_instance_priority_weight;
delimiter d//
CREATE PROCEDURE add_t_ds_workflow_instance_priority_weight()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_NAME='t_ds_workflow_instance'
        AND TABLE_SCHEMA=(SELECT DATABASE())
        AND COLUMN_NAME='priority_weight')
    THEN
        ALTER TABLE `t_ds_workflow_instance` ADD COLUMN `priority_weight` int(11) DEFAULT NULL COMMENT 'Dynamic priority weight for runtime management (1-100, default 50)' AFTER `workflow_instance_priority`;
    END IF;
END;

d//
delimiter ;
CALL add_t_ds_workflow_instance_priority_weight;
DROP PROCEDURE add_t_ds_workflow_instance_priority_weight;

-- 3. Add weight_score to t_ds_task_group_queue
drop PROCEDURE if EXISTS add_t_ds_task_group_queue_weight_score;
delimiter d//
CREATE PROCEDURE add_t_ds_task_group_queue_weight_score()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_NAME='t_ds_task_group_queue'
        AND TABLE_SCHEMA=(SELECT DATABASE())
        AND COLUMN_NAME='weight_score')
    THEN
        ALTER TABLE `t_ds_task_group_queue` ADD COLUMN `weight_score` int(11) NOT NULL DEFAULT 0 COMMENT 'Weighted priority score calculated by PriorityWeightEngine' AFTER `priority`;
    END IF;
END;

d//
delimiter ;
CALL add_t_ds_task_group_queue_weight_score;
DROP PROCEDURE add_t_ds_task_group_queue_weight_score;

-- 4. Add index on weight_score for priority-aware queries
drop PROCEDURE if EXISTS add_t_ds_task_group_queue_idx_weight_score;
delimiter d//
CREATE PROCEDURE add_t_ds_task_group_queue_idx_weight_score()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_NAME='t_ds_task_group_queue'
        AND TABLE_SCHEMA=(SELECT DATABASE())
        AND INDEX_NAME='idx_priority_weight_score')
    THEN
        ALTER TABLE `t_ds_task_group_queue` ADD INDEX `idx_priority_weight_score` (`priority`, `weight_score` DESC);
    END IF;
END;

d//
delimiter ;
CALL add_t_ds_task_group_queue_idx_weight_score;
DROP PROCEDURE add_t_ds_task_group_queue_idx_weight_score;

-- 5. Add index on priority_weight for task_instance queries
drop PROCEDURE if EXISTS add_t_ds_task_instance_idx_priority_weight;
delimiter d//
CREATE PROCEDURE add_t_ds_task_instance_idx_priority_weight()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
        WHERE TABLE_NAME='t_ds_task_instance'
        AND TABLE_SCHEMA=(SELECT DATABASE())
        AND INDEX_NAME='idx_priority_weight')
    THEN
        ALTER TABLE `t_ds_task_instance` ADD INDEX `idx_priority_weight` (`priority_weight`);
    END IF;
END;

d//
delimiter ;
CALL add_t_ds_task_instance_idx_priority_weight;
DROP PROCEDURE add_t_ds_task_instance_idx_priority_weight;
