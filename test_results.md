# Advanced Business Date Scheduling - Test Report

## 目的
本报告总结了 Apache DolphinScheduler 中新实现的高级业务日期调度功能（Advanced Business Date Scheduling/Custom Calendar）的研发与测试情况。该功能引入了 `业务日历`、`基于自然日/工作日的 T+/-N 偏移` 以及 `切分时间 (Cutover Time)` 计算规则，涉及到的多维时间校验已从 Master 和 Service 两侧完成覆盖。

## 测试环境配置
*   **操作系统:** Windows
*   **JDK 版本:** OpenJDK 11 (`C:\Program Files\Microsoft\jdk-11.0.30.7-hotspot`)。为了兼容 DolphinScheduler 中 `spotless-maven-plugin:2.27.2` 的 JDK 版本限制（在 JDK 21 下会报错），我们通过临时将环境变量 `JAVA_HOME` 指向 JDK 11 并更新 `PATH`，顺利完成了所有 Maven 构建操作。
*   **依赖构建策略:** `mvn install` / `mvn test` 均通过忽略部分耗时的插件参数实现快速编译与验证 (`-Dspotless.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true -Djacoco.skip=true -Dspotbugs.skip=true -Dmdep.analyze.skip=true`)。

## 测试覆盖范围 (Unit Tests)

针对新业务日期的核心组件，共增设并成功执行了 28 个单元测试用例，涵盖以下四个主要类及其业务逻辑边界：

### 1. `BusinessCalendarServiceImplTest` (Service Layer) 
**目标:** 测试自定义日历规则下基准日期、切分时间和偏移量如何精确转换。
*   **用例总数:** 12 个。
*   **测试重点:** 
    *   **没有日历的情况 (Fallback):** 没有配置特殊日历时，直接返回基础日计划执行时间。
    *   **常规的正/负偏移 (Simple Offset):** T-1 或 T+2 是否能够正确对应。
    *   **切分时间逻辑 (Cutover Time):** 判断是否跨越了一天的切分时间（例如下午 6 点之后作为下一个业务日的一部分）。
    *   **边界保护 (Null Safety):** 当从数据库(`CalendarDateMapper`)查不到对应数据时，业务抛出或者平滑降级的防护逻辑。
*   **执行状态:** **12/12 Passed**。

### 2. `CommandDependencyEvaluatorTest` (Master Layer)
**目标:** 测试通过时间调度或依赖触发生成的任务 Command，如何基于新的 `waitTime` / `earliestTimeoutTime` 和相关状态 (e.g. `WAIT_TIME`) 判定自身依赖状态并解锁为 `READY_PAUSE` 或 `READY_STOP` 等可执行阶段。
*   **用例总数:** 4 个。
*   **测试重点:**
    *   通过反射 (Reflection) 测试其私有的 `evaluateCommand` 扫描计算方法。
    *   测试当最早超时时间未到(即仍需继续等待)以及触发超时重置时的状态流转保护。
*   **执行状态:** **4/4 Passed**。

### 3. `WorkflowScheduleTriggerTest` (Master Layer)
**目标:** 测试触发器接到 Quartz 传来的调度激活请求时，能否正确构造完整的 `Command` 实体并填入新增业务时间属性（`businessDate`, `earliestExecTime`）。
*   **用例总数:** 8 个。
*   **测试重点:**
    *   模拟业务日历被触发并处理偏移量逻辑验证（基于同天内触发，或是越界至下一天的翻转触发）。
    *   空指针拦截以及核心属性字段的一致性。
*   **执行状态:** **8/8 Passed**。

### 4. `ProcessScheduleTaskTest` (Scheduler Quartz Plugin)
**目标:** 验证 Quartz 定时调度插件能否读取定义好的带自定义日历的计划 (`Schedule` Entity) 并将上下文参数正确转换为统一的 `WorkflowScheduleTriggerRequest` 请求体向下游触发。
*   **用例总数:** 4 个。
*   **测试重点:**
    *   校验 `calendarId`、`cutoverTime` 以及 `businessDateOffset` 等新增特殊日程字段能够一字不差地在调用链路间实现透传。
*   **执行状态:** **4/4 Passed**。

## 联调及后续推进
*   **DB 层依赖:** 因为涉及到对工作日和节假日的精确定义，当前在测试中对含有 SQL 层运算逻辑的 mapper `CalendarDateMapper.calculateBusinessDate` 均采取了 Mock 策略。后续在正式集成环境中需配套相关的 DB 层表结构 (`t_ds_calendar`, `t_ds_calendar_date`) 及其初始化数据进行功能级整合验证。
*   **前端联调:** 控制台界面在配置触发器定时调度规则时需要相应支持自定义日历选择、切分时间和偏移值的录入能力。

## 结论
新的**高级业务日期调度功能**在核心的算法与调度触发链路上的单元用例**已经全部通过 (`Exit Code: 0`)**。整体设计能够很好地适应现有 DolphinScheduler 的架构扩展并提供了兼容降级的冗余支持，现提交合并入库。
