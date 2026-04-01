package org.apache.dolphinscheduler.server.master.engine.workflow.trigger;

import org.apache.dolphinscheduler.common.enums.CommandType;
import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.dao.entity.WorkflowInstance;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowScheduleTriggerRequest;
import org.apache.dolphinscheduler.service.calendar.BusinessCalendarService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Unit tests for the business-date / earliest-exec-time / command-state logic
 * inside {@link WorkflowScheduleTrigger#constructTriggerCommand}.
 */
@ExtendWith(MockitoExtension.class)
public class WorkflowScheduleTriggerTest {

    @InjectMocks
    private WorkflowScheduleTrigger trigger;

    @Mock
    private BusinessCalendarService businessCalendarService;

    // ------ helpers ------

    private Date createDate(int year, int month, int day, int hour, int minute) {
        return Date.from(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant());
    }

    private WorkflowInstance stubWorkflowInstance() {
        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(100);
        return wi;
    }

    /**
     * constructTriggerCommand is protected – invoke via reflection so we 
     * can unit-test it in isolation without wiring the full Spring context.
     */
    private Command invokeConstructTriggerCommand(WorkflowScheduleTriggerRequest request,
                                                  WorkflowInstance workflowInstance) throws Exception {
        Method m = WorkflowScheduleTrigger.class.getDeclaredMethod(
                "constructTriggerCommand",
                WorkflowScheduleTriggerRequest.class,
                WorkflowInstance.class);
        m.setAccessible(true);
        return (Command) m.invoke(trigger, request, workflowInstance);
    }

    // =======================================================================
    // 1. businessDate 由 BusinessCalendarService 计算并写入 Command
    // =======================================================================

    @Test
    public void testConstructCommand_BusinessDateFromCalendarService() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);
        Date resolvedBizDate = createDate(2023, 10, 12, 0, 0);

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.eq(1L), Mockito.eq(scheduleTime), Mockito.eq(2), Mockito.eq("15:00")))
                .thenReturn(resolvedBizDate);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(1001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .calendarId(1L)
                .businessDateOffset(2)
                .cutoverTime("15:00")
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Assertions.assertEquals(resolvedBizDate, cmd.getBusinessDate());
        Assertions.assertEquals(CommandType.SCHEDULER, cmd.getCommandType());
        Assertions.assertEquals(1001L, cmd.getWorkflowDefinitionCode());
    }

    // =======================================================================
    // 2. calendarId = null → businessCalendarService 仍被调用(由它内部处理降级)
    // =======================================================================

    @Test
    public void testConstructCommand_NoCalendar() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.isNull(), Mockito.eq(scheduleTime), Mockito.isNull(), Mockito.isNull()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(2001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Assertions.assertEquals(scheduleTime, cmd.getBusinessDate());
    }

    // =======================================================================
    // 3. earliestExecTime = null → earliestTimeoutTime 应为 null
    // =======================================================================

    @Test
    public void testConstructCommand_NoEarliestExecTime() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(3001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .earliestExecTime(null)
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Assertions.assertNull(cmd.getEarliestTimeoutTime(),
                "Should be null when no earliest exec time configured");
    }

    // =======================================================================
    // 4. earliestExecTime = "10:00", scheduleTime = 09:00 → 同日 10:00
    // =======================================================================

    @Test
    public void testConstructCommand_EarliestExecTime_SameDay() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);  // 09:00

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(4001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .earliestExecTime("10:00")
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Date expected = createDate(2023, 10, 10, 10, 0); // same day 10:00
        Assertions.assertNotNull(cmd.getEarliestTimeoutTime());
        Assertions.assertEquals(expected, cmd.getEarliestTimeoutTime());
    }

    // =======================================================================
    // 5. earliestExecTime = "08:00", scheduleTime = 09:00 → 次日 08:00
    //    (当 parsed time < scheduleTime 时, 向后推一天)
    // =======================================================================

    @Test
    public void testConstructCommand_EarliestExecTime_NextDay() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0); // 09:00

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(5001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .earliestExecTime("08:00")
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Date expected = createDate(2023, 10, 11, 8, 0); // next day 08:00
        Assertions.assertNotNull(cmd.getEarliestTimeoutTime());
        Assertions.assertEquals(expected, cmd.getEarliestTimeoutTime());
    }

    // =======================================================================
    // 6. Command 初始状态: commandState=1 (CHECK_PENDING), waitReason=0
    // =======================================================================

    @Test
    public void testConstructCommand_InitialState() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(6001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Assertions.assertEquals(1, cmd.getCommandState(),
                "New scheduled command should start in CHECK_PENDING state");
        Assertions.assertEquals(0, cmd.getWaitReason(),
                "New scheduled command should have initial wait reason = NONE");
    }

    // =======================================================================
    // 7. earliestExecTime 为空字符串 → 等同 null → earliestTimeoutTime = null
    // =======================================================================

    @Test
    public void testConstructCommand_EarliestExecTimeEmptyString() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(7001L)
                .workflowVersion(1)
                .scheduleTIme(scheduleTime)
                .earliestExecTime("  ")
                .build();

        Command cmd = invokeConstructTriggerCommand(request, stubWorkflowInstance());

        Assertions.assertNull(cmd.getEarliestTimeoutTime(),
                "Empty/whitespace-only earliest exec time should result in null");
    }

    // =======================================================================
    // 8. workflowInstanceId 应从 workflowInstance 取值
    // =======================================================================

    @Test
    public void testConstructCommand_WorkflowInstanceIdCarried() throws Exception {
        Date scheduleTime = createDate(2023, 10, 10, 9, 0);

        Mockito.when(businessCalendarService.resolveBusinessDate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(scheduleTime);

        WorkflowScheduleTriggerRequest request = WorkflowScheduleTriggerRequest.builder()
                .workflowCode(8001L)
                .workflowVersion(3)
                .scheduleTIme(scheduleTime)
                .build();

        WorkflowInstance wi = new WorkflowInstance();
        wi.setId(999);

        Command cmd = invokeConstructTriggerCommand(request, wi);

        Assertions.assertEquals(999, cmd.getWorkflowInstanceId());
        Assertions.assertEquals(3, cmd.getWorkflowDefinitionVersion());
    }
}
