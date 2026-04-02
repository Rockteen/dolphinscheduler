# DolphinScheduler 运行态管理扩展计划

## 一、现状分析

### 1.1 现有架构概览

```
API Layer (dolphinscheduler-api)
  └── ExecutorDelegate 模式 (Pause/Stop/Trigger/Repeat/Recover)
       ↓ RPC (IWorkflowControlClient)
Master Layer (dolphinscheduler-master)
  ├── CommandEngine (命令消费循环)
  ├── WorkflowEngine (工作流编排)
  │   ├── WorkflowStateActionFactory (状态机)
  │   ├── WorkflowExecutionGraph (DAG 执行图)
  │   └── TaskExecutionRunnable (任务执行单元)
  ├── TaskGroupCoordinator (任务组槽位管理)
  ├── WorkerGroupDispatcherCoordinator (按 WorkerGroup 分发)
  │   └── WorkerGroupDispatcher (单线程分发循环)
  └── TaskExecutorClient (任务派发 → Worker)
       ↓ RPC (IPhysicalTaskExecutorOperator)
Worker Layer (dolphinscheduler-worker)
  └── TaskEngine (实际任务执行)
```

### 1.2 现有能力评估

| 功能 | 现状 | 评估 |
|------|------|------|
| 工作流重跑 | `RepeatRunningWorkflowInstanceExecutorDelegate` → `repeatTriggerWorkflowInstance` | ✅ 已有 |
| 暂停/恢复 | `PauseWorkflowInstanceExecutorDelegate` / `RecoverSuspendedWorkflowInstanceExecutorDelegate` | ✅ 已有 |
| 停止 | `StopWorkflowInstanceExecutorDelegate` | ✅ 已有 |
| 任务优先级 | `TaskExecutionRunnable.compareTo()` 三级优先级 (workflow → task → taskGroup) | ⚠️ 基础存在，但分发层未使用 |
| 任务隔离 | WorkerGroup 隔离 + Tenant 隔离 | ⚠️ 粗粒度，无运行态动态调整 |
| 任务组队列 | `TaskGroupQueue.priority` 字段存在 | ❌ 排队逻辑未按优先级排序 |
| 运行态动态管理 | 无 | ❌ 完全缺失 |

### 1.3 核心问题

1. **优先级断层**：`TaskExecutionRunnable` 实现了 `Comparable` 有优先级比较，但 `TaskGroupCoordinator.dealWithWaitingTaskGroupQueue()` 和 `WorkerGroupDispatcher` 的分发队列**未按优先级排序**
2. **隔离粒度粗**：仅有 WorkerGroup 级别的静态隔离，无运行态的资源配额、并发限制、任务级隔离
3. **无运行态管理 API**：无法在运行时动态调整优先级、隔离策略、资源配额

---

## 二、功能设计

### 2.1 功能矩阵

| 功能模块 | 子功能 | 优先级 |
|---------|--------|--------|
| **P0: 任务优先加权** | 运行态优先级动态调整 | P0 |
| | 多级优先级策略 (静态 + 动态加权) | P0 |
| | 优先级队列改造 | P0 |
| **P1: 任务隔离** | 运行态任务组配额管理 | P1 |
| | 租户级资源隔离 | P1 |
| | Worker 级并发限制 | P1 |
| **P2: 增强工作流控制** | 部分重跑 (指定任务范围) | P2 |
| | 暂停/恢复单个任务 | P2 |
| | 工作流运行时参数热更新 | P2 |
| **P3: 运行时监控** | 任务执行热度/负载感知 | P3 |
| | 优先级饥饿检测 | P3 |

---

## 三、详细实施方案

### 3.1 任务优先加权系统

#### 3.1.1 数据模型扩展

**新增字段**：

| 表 | 字段 | 类型 | 说明 |
|----|------|------|------|
| `t_ds_task_instance` | `priority_weight` | INT | 动态优先级权重 (1-100, 默认50) |
| `t_ds_workflow_instance` | `priority_weight` | INT | 工作流级动态权重 |
| `t_ds_task_group_queue` | `weight_score` | INT | 加权后的综合分数 |
| `t_ds_command` | `priority_weight` | INT | 命令级优先级权重 |

**新增表**：

```sql
-- 优先级策略配置表
CREATE TABLE t_ds_priority_policy (
    id              INT PRIMARY KEY AUTO_INCREMENT,
    project_code    BIGINT NOT NULL,
    policy_name     VARCHAR(128) NOT NULL,
    policy_type     VARCHAR(32) NOT NULL,  -- STATIC, DYNAMIC, HYBRID
    base_priority   INT DEFAULT 50,        -- 基础优先级 1-100
    weight_formula  TEXT,                  -- 加权公式 JSON
    conditions      TEXT,                  -- 触发条件 JSON
    enabled         TINYINT DEFAULT 1,
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 运行态优先级调整记录表
CREATE TABLE t_ds_priority_adjustment_log (
    id                  INT PRIMARY KEY AUTO_INCREMENT,
    task_instance_id    INT NOT NULL,
    workflow_instance_id INT NOT NULL,
    old_priority        INT,
    new_priority        INT,
    adjust_reason       VARCHAR(256),
    operator_id         INT,
    adjust_time         DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

#### 3.1.2 加权计算引擎

**文件**: `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/priority/`

```
PriorityWeightEngine.java          — 优先级加权计算引擎
├── PriorityStrategy.java          — 策略接口
│   ├── StaticPriorityStrategy     — 静态优先级 (基于定义)
│   ├── DynamicPriorityStrategy    — 动态优先级 (基于运行态指标)
│   └── HybridPriorityStrategy     — 混合策略
├── PriorityFactors.java           — 加权因子
│   ├── basePriority               — 基础优先级
│   ├── waitTimeFactor             — 等待时间因子 (防饥饿)
│   ├── retryCountFactor           — 重试次数因子
│   ├── dependencyDepthFactor      — 依赖深度因子
│   └── resourceUrgencyFactor      — 资源紧急度因子
└── PriorityScoreCalculator.java   — 综合分数计算
```

**加权公式**：
```
finalScore = basePriority * w1 
           + waitTimeFactor * w2 
           + (1 / (1 + retryCount)) * w3 
           + dependencyDepthFactor * w4
           + resourceUrgencyFactor * w5
```

#### 3.1.3 优先级队列改造

**修改文件**:

| 文件 | 修改内容 |
|------|---------|
| `TaskGroupCoordinator.java` | `dealWithWaitingTaskGroupQueue()` 改为按 `weight_score` 排序取出 |
| `WorkerGroupDispatcher.java` | `TaskDispatchableEventBus` 改为 `PriorityBlockingQueue` |
| `TaskExecutionRunnable.java` | 扩展 `compareTo()` 纳入 `priorityWeight` |
| `TaskGroupQueue.java` (DAO) | 新增 `weightScore` 字段 |

**关键修改点 — TaskGroupCoordinator**:
```java
// 当前：FIFO 取等待任务
// 修改后：按加权分数排序
private List<TaskGroupQueue> dealWithWaitingTaskGroupQueue(int taskGroupId) {
    List<TaskGroupQueue> waitingTasks = taskGroupQueueMapper.queryByTaskGroupIdAndStatus(
        taskGroupId, TaskGroupQueueStatus.WAITING_QUEUE);
    
    // 按加权分数降序排列
    return waitingTasks.stream()
        .sorted(Comparator.comparingInt(TaskGroupQueue::getWeightScore).reversed())
        .collect(Collectors.toList());
}
```

#### 3.1.4 运行态优先级调整 API

**新增文件**: `dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/executor/workflow/`

```
AdjustTaskPriorityExecutorDelegate.java  — 调整任务优先级
AdjustWorkflowPriorityExecutorDelegate.java — 调整工作流优先级
```

**RPC 扩展**: `dolphinscheduler-extract/dolphinscheduler-extract-master/`

```java
// IWorkflowControlClient 新增方法
void adjustTaskPriority(WorkflowInstanceTaskPriorityAdjustRequest request);
void adjustWorkflowPriority(WorkflowInstancePriorityAdjustRequest request);
```

---

### 3.2 任务隔离系统

#### 3.2.1 隔离层次设计

```
┌─────────────────────────────────────────────────┐
│                  Tenant Isolation               │  ← 租户级
│  ┌───────────────────────────────────────────┐  │
│  │           Project Isolation               │  │  ← 项目级
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │        TaskGroup Isolation          │  │  │  ← 任务组级 (已有)
│  │  │  ┌───────────────────────────────┐  │  │  │
│  │  │  │    Task Instance Isolation    │  │  │  │  ← 任务实例级 (新增)
│  │  │  │  (资源配额 / 并发限制)         │  │  │  │
│  │  │  └───────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

#### 3.2.2 运行态资源配额管理

**新增表**:

```sql
-- 任务组运行时配额表
CREATE TABLE t_ds_task_group_runtime_quota (
    id                  INT PRIMARY KEY AUTO_INCREMENT,
    task_group_id       INT NOT NULL,
    project_code        BIGINT NOT NULL,
    max_concurrent      INT DEFAULT 10,      -- 最大并发数
    cpu_quota_percent   INT DEFAULT 100,     -- CPU 配额百分比
    memory_quota_mb     INT DEFAULT 4096,    -- 内存配额 MB
    queue_capacity      INT DEFAULT 100,     -- 队列容量
    throttle_threshold  INT DEFAULT 90,      -- 节流阈值 %
    enabled             TINYINT DEFAULT 1,
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Worker 级并发限制表
CREATE TABLE t_ds_worker_runtime_limit (
    id                  INT PRIMARY KEY AUTO_INCREMENT,
    worker_host          VARCHAR(128) NOT NULL,
    worker_group         VARCHAR(128) NOT NULL,
    max_task_slots       INT DEFAULT 50,     -- 最大任务槽位
    max_cpu_percent      INT DEFAULT 80,     -- CPU 上限
    max_memory_mb        INT DEFAULT 8192,   -- 内存上限
    current_load         INT DEFAULT 0,      -- 当前负载
    is_throttled         TINYINT DEFAULT 0,  -- 是否节流
    update_time          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

#### 3.2.3 隔离执行器

**新增文件**: `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/isolation/`

```
TaskIsolationEngine.java           — 任务隔离引擎
├── IsolationPolicy.java           — 隔离策略接口
│   ├── TenantIsolationPolicy      — 租户隔离
│   ├── ProjectIsolationPolicy     — 项目隔离
│   └── TaskGroupIsolationPolicy   — 任务组隔离
├── ResourceQuotaManager.java      — 资源配额管理
├── ConcurrencyController.java     — 并发控制器
└── ThrottleManager.java           — 节流管理器
```

**修改文件**:

| 文件 | 修改内容 |
|------|---------|
| `WorkerGroupDispatcherCoordinator.java` | 集成 `ConcurrencyController`，分发前检查配额 |
| `WorkerGroupDispatcher.java` | 分发时检查 Worker 负载，支持节流 |
| `TaskGroupCoordinator.java` | 获取槽位时检查运行时配额 |
| `WorkerHeartBeatTask.java` | 心跳数据增加负载指标 (CPU/内存/槽位使用率) |

#### 3.2.4 运行态隔离管理 API

**新增文件**: `dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/executor/runtime/`

```
AdjustTaskGroupQuotaExecutorDelegate.java   — 调整任务组配额
AdjustWorkerLimitExecutorDelegate.java       — 调整 Worker 限制
ToggleTaskThrottleExecutorDelegate.java      — 开启/关闭任务节流
```

---

### 3.3 增强工作流控制

#### 3.3.1 部分重跑 (指定任务范围)

**新增 CommandType**:
```java
// dolphinscheduler-common/.../CommandType.java
PARTIAL_RERUN(14, "部分重跑"),
```

**新增文件**:
```
dolphinscheduler-api/.../executor/workflow/
  └── PartialRerunWorkflowInstanceExecutorDelegate.java

dolphinscheduler-master/.../command/handler/
  └── PartialRerunCommandHandler.java
```

**RPC 扩展**:
```java
// IWorkflowControlClient
void partialRerunWorkflowInstance(WorkflowInstancePartialRerunRequest request);
```

**请求体**:
```java
class WorkflowInstancePartialRerunRequest {
    int workflowInstanceId;
    List<Long> taskCodes;        // 指定要重跑的任务
    RerunStrategy strategy;      // DEPENDENCY_ONLY / SELECTED_TASKS / DOWNSTREAM
    int priorityWeight;          // 重跑优先级
}
```

#### 3.3.2 单任务暂停/恢复

**新增文件**:
```
dolphinscheduler-api/.../executor/workflow/
  ├── PauseTaskInstanceExecutorDelegate.java
  └── ResumeTaskInstanceExecutorDelegate.java

dolphinscheduler-master/.../task/client/
  └── TaskControlClient.java (扩展 ITaskExecutorClient)
```

#### 3.3.3 运行时参数热更新

**新增文件**:
```
dolphinscheduler-api/.../executor/workflow/
  └── UpdateWorkflowRuntimeParamsExecutorDelegate.java

dolphinscheduler-master/.../workflow/
  └── WorkflowRuntimeParamManager.java
```

---

## 四、实施阶段

### Phase 1: 优先级队列改造 (2-3 天)

| 步骤 | 文件 | 工作内容 |
|------|------|---------|
| 1.1 | `TaskGroupQueue.java` (DAO) | 新增 `weightScore` 字段 |
| 1.2 | `TaskGroupCoordinator.java` | `dealWithWaitingTaskGroupQueue()` 改为优先级排序 |
| 1.3 | `WorkerGroupDispatcher.java` | 分发队列改为 `PriorityBlockingQueue` |
| 1.4 | `TaskExecutionRunnable.java` | `compareTo()` 纳入 `priorityWeight` |
| 1.5 | `TaskInstance.java` / `WorkflowInstance.java` | 新增 `priorityWeight` 字段 |
| 1.6 | Mapper XML | 新增字段映射和查询 |

### Phase 2: 优先级加权引擎 (2-3 天)

| 步骤 | 文件 | 工作内容 |
|------|------|---------|
| 2.1 | `PriorityWeightEngine.java` | 创建加权计算引擎 |
| 2.2 | `PriorityStrategy.java` + 实现 | 实现三种策略 |
| 2.3 | `PriorityScoreCalculator.java` | 综合分数计算 |
| 2.4 | `t_ds_priority_policy` | 创建策略配置表 |
| 2.5 | `t_ds_priority_adjustment_log` | 创建调整日志表 |

### Phase 3: 运行态优先级调整 API (1-2 天)

| 步骤 | 文件 | 工作内容 |
|------|------|---------|
| 3.1 | `AdjustTaskPriorityExecutorDelegate.java` | 任务级优先级调整 |
| 3.2 | `AdjustWorkflowPriorityExecutorDelegate.java` | 工作流级优先级调整 |
| 3.3 | `IWorkflowControlClient` | 扩展 RPC 接口 |
| 3.4 | 请求/响应 DTO | 新增传输对象 |

### Phase 4: 任务隔离系统 (3-4 天)

| 步骤 | 文件 | 工作内容 |
|------|------|---------|
| 4.1 | `t_ds_task_group_runtime_quota` | 创建配额表 |
| 4.2 | `t_ds_worker_runtime_limit` | 创建 Worker 限制表 |
| 4.3 | `TaskIsolationEngine.java` | 创建隔离引擎 |
| 4.4 | `ResourceQuotaManager.java` | 资源配额管理 |
| 4.5 | `ConcurrencyController.java` | 并发控制器 |
| 4.6 | `ThrottleManager.java` | 节流管理器 |
| 4.7 | `WorkerGroupDispatcherCoordinator.java` | 集成隔离检查 |
| 4.8 | `WorkerHeartBeatTask.java` | 心跳增加负载指标 |

### Phase 5: 隔离管理 API (1-2 天)

| 步骤 | 文件 | 工作内容 |
|------|------|---------|
| 5.1 | `AdjustTaskGroupQuotaExecutorDelegate.java` | 任务组配额调整 |
| 5.2 | `AdjustWorkerLimitExecutorDelegate.java` | Worker 限制调整 |
| 5.3 | `ToggleTaskThrottleExecutorDelegate.java` | 节流开关 |

### Phase 6: 增强工作流控制 (2-3 天)

| 步骤 | 文件 | 工作内容 |
|------|------|---------|
| 6.1 | `CommandType.java` | 新增 `PARTIAL_RERUN` |
| 6.2 | `PartialRerunWorkflowInstanceExecutorDelegate.java` | 部分重跑 API |
| 6.3 | `PartialRerunCommandHandler.java` | 部分重跑命令处理 |
| 6.4 | `PauseTaskInstanceExecutorDelegate.java` | 单任务暂停 |
| 6.5 | `ResumeTaskInstanceExecutorDelegate.java` | 单任务恢复 |
| 6.6 | `UpdateWorkflowRuntimeParamsExecutorDelegate.java` | 运行时参数更新 |

### Phase 7: 测试与验证 (2-3 天)

| 步骤 | 工作内容 |
|------|---------|
| 7.1 | 单元测试：优先级计算、队列排序 |
| 7.2 | 集成测试：优先级调整端到端 |
| 7.3 | 集成测试：隔离策略端到端 |
| 7.4 | 压力测试：高并发下优先级正确性 |
| 7.5 | 回归测试：确保现有功能不受影响 |

---

## 五、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 优先级队列改造影响现有调度 | 高 | 保留原有 FIFO 作为降级策略，通过配置开关 |
| 加权计算性能开销 | 中 | 缓存计算结果，仅在状态变更时重新计算 |
| 隔离策略与现有 WorkerGroup 冲突 | 中 | 隔离策略作为 WorkerGroup 之上的附加层，不修改现有逻辑 |
| 数据库迁移影响生产 | 高 | 所有新增字段带默认值，向下兼容 |
| RPC 接口扩展兼容性 | 中 | 使用可选字段，老版本客户端不受影响 |

---

## 六、关键文件索引

### 修改文件清单

| 模块 | 文件路径 | 修改类型 |
|------|---------|---------|
| DAO | `dolphinscheduler-dao/src/main/java/org/apache/dolphinscheduler/dao/entity/TaskInstance.java` | 新增字段 |
| DAO | `dolphinscheduler-dao/src/main/java/org/apache/dolphinscheduler/dao/entity/WorkflowInstance.java` | 新增字段 |
| DAO | `dolphinscheduler-dao/src/main/java/org/apache/dolphinscheduler/dao/entity/TaskGroupQueue.java` | 新增字段 |
| DAO | `dolphinscheduler-dao/src/main/java/org/apache/dolphinscheduler/dao/mapper/TaskInstanceMapper.java` | 新增查询 |
| DAO | `dolphinscheduler-dao/src/main/java/org/apache/dolphinscheduler/dao/mapper/TaskGroupQueueMapper.java` | 新增查询 |
| Master | `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/TaskGroupCoordinator.java` | 核心修改 |
| Master | `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/dispatcher/WorkerGroupDispatcher.java` | 核心修改 |
| Master | `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/dispatcher/WorkerGroupDispatcherCoordinator.java` | 集成隔离 |
| Master | `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/engine/task/runnable/TaskExecutionRunnable.java` | 扩展比较 |
| Master | `dolphinscheduler-master/src/main/java/org/apache/dolphinscheduler/server/master/registry/WorkerHeartBeatTask.java` | 增加负载指标 |
| Common | `dolphinscheduler-common/src/main/java/org/apache/dolphinscheduler/common/enums/CommandType.java` | 新增枚举 |
| Extract | `dolphinscheduler-extract/dolphinscheduler-extract-master/src/main/java/org/apache/dolphinscheduler/extract/master/IWorkflowControlClient.java` | 扩展接口 |
| API | `dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/executor/workflow/` | 新增 Delegate |

### 新增文件清单

| 模块 | 文件路径 | 说明 |
|------|---------|------|
| Master | `.../engine/task/priority/PriorityWeightEngine.java` | 优先级加权引擎 |
| Master | `.../engine/task/priority/PriorityStrategy.java` | 策略接口 |
| Master | `.../engine/task/priority/strategy/StaticPriorityStrategy.java` | 静态策略 |
| Master | `.../engine/task/priority/strategy/DynamicPriorityStrategy.java` | 动态策略 |
| Master | `.../engine/task/priority/strategy/HybridPriorityStrategy.java` | 混合策略 |
| Master | `.../engine/task/priority/PriorityScoreCalculator.java` | 分数计算 |
| Master | `.../engine/task/isolation/TaskIsolationEngine.java` | 任务隔离引擎 |
| Master | `.../engine/task/isolation/IsolationPolicy.java` | 隔离策略接口 |
| Master | `.../engine/task/isolation/ResourceQuotaManager.java` | 资源配额管理 |
| Master | `.../engine/task/isolation/ConcurrencyController.java` | 并发控制器 |
| Master | `.../engine/task/isolation/ThrottleManager.java` | 节流管理器 |
| Master | `.../engine/task/client/TaskControlClient.java` | 任务控制客户端 |
| Master | `.../workflow/WorkflowRuntimeParamManager.java` | 运行时参数管理 |
| API | `.../executor/workflow/AdjustTaskPriorityExecutorDelegate.java` | 优先级调整 |
| API | `.../executor/workflow/AdjustWorkflowPriorityExecutorDelegate.java` | 工作流优先级调整 |
| API | `.../executor/workflow/PartialRerunWorkflowInstanceExecutorDelegate.java` | 部分重跑 |
| API | `.../executor/workflow/PauseTaskInstanceExecutorDelegate.java` | 单任务暂停 |
| API | `.../executor/workflow/ResumeTaskInstanceExecutorDelegate.java` | 单任务恢复 |
| API | `.../executor/workflow/UpdateWorkflowRuntimeParamsExecutorDelegate.java` | 参数热更新 |
| API | `.../executor/runtime/AdjustTaskGroupQuotaExecutorDelegate.java` | 配额调整 |
| API | `.../executor/runtime/AdjustWorkerLimitExecutorDelegate.java` | Worker 限制调整 |
| API | `.../executor/runtime/ToggleTaskThrottleExecutorDelegate.java` | 节流开关 |

---

## 七、配置管理

新增配置项 (`application.yaml`):

```yaml
dolphinscheduler.runtime-management:
  priority:
    enabled: true
    default-strategy: HYBRID          # STATIC | DYNAMIC | HYBRID
    weights:
      base-priority: 0.40
      wait-time: 0.20
      retry-count: 0.15
      dependency-depth: 0.15
      resource-urgency: 0.10
    anti-starvation:
      enabled: true
      max-wait-time-minutes: 30      # 超过此时间自动提升优先级
      boost-factor: 1.5              # 提升系数

  isolation:
    enabled: true
    default-concurrency-per-group: 10
    default-cpu-quota-percent: 100
    default-memory-quota-mb: 4096
    throttle:
      enabled: true
      default-threshold: 90          # 负载超过 90% 触发节流
    
  partial-rerun:
    enabled: true
    max-tasks-per-rerun: 50          # 单次部分重跑最大任务数
```
