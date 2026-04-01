package org.apache.dolphinscheduler.scheduler.quartz;

import org.apache.dolphinscheduler.common.enums.FailureStrategy;
import org.apache.dolphinscheduler.common.enums.Priority;
import org.apache.dolphinscheduler.common.enums.ReleaseState;
import org.apache.dolphinscheduler.common.enums.WarningType;
import org.apache.dolphinscheduler.dao.entity.Schedule;
import org.apache.dolphinscheduler.dao.entity.WorkflowDefinition;
import org.apache.dolphinscheduler.dao.repository.ScheduleDao;
import org.apache.dolphinscheduler.dao.repository.WorkflowDefinitionDao;
import org.apache.dolphinscheduler.extract.master.IWorkflowControlClient;
import org.apache.dolphinscheduler.extract.master.transportor.workflow.WorkflowScheduleTriggerRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;

import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that ProcessScheduleTask correctly forwards the new calendar-related fields
 * (calendarId, businessDateOffset, cutoverTime, earliestExecTime) from the Schedule entity
 * into WorkflowScheduleTriggerRequest.
 */
@ExtendWith(MockitoExtension.class)
public class ProcessScheduleTaskTest {

    @InjectMocks
    private ProcessScheduleTask processScheduleTask;

    @Mock
    private ScheduleDao scheduleDao;

    @Mock
    private WorkflowDefinitionDao workflowDefinitionDao;

    @Mock
    private IWorkflowControlClient workflowInstanceController;

    // =====================================================================
    // Helper
    // =====================================================================

    private JobExecutionContext mockContext(int projectId, int scheduleId, Date scheduledFireTime) {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);
        JobDetail jobDetail = Mockito.mock(JobDetail.class);

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("projectId", projectId);
        dataMap.put("scheduleId", scheduleId);

        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);
        when(context.getScheduledFireTime()).thenReturn(scheduledFireTime);
        when(context.getFireTime()).thenReturn(scheduledFireTime);

        return context;
    }

    private Schedule buildSchedule(long workflowCode) {
        Schedule s = new Schedule();
        s.setId(1);
        s.setWorkflowDefinitionCode(workflowCode);
        s.setReleaseState(ReleaseState.ONLINE);
        s.setUserId(1);
        s.setTimezoneId("Asia/Shanghai");
        s.setFailureStrategy(FailureStrategy.CONTINUE);
        s.setWarningType(WarningType.NONE);
        s.setWarningGroupId(0);
        s.setWorkflowInstancePriority(Priority.MEDIUM);
        s.setWorkerGroup("default");
        s.setTenantCode("default");
        s.setEnvironmentCode(-1L);
        return s;
    }

    private WorkflowDefinition buildWorkflowDef(long code) {
        WorkflowDefinition wd = new WorkflowDefinition();
        wd.setCode(code);
        wd.setVersion(1);
        wd.setReleaseState(ReleaseState.ONLINE);
        return wd;
    }

    // =====================================================================
    // 1. calendarId / businessDateOffset / cutoverTime / earliestExecTime
    //    应完整传递到 WorkflowScheduleTriggerRequest
    // =====================================================================

    @Test
    public void testCalendarFieldsForwarded() {
        long wfCode = 10001L;
        Schedule schedule = buildSchedule(wfCode);
        schedule.setCalendarId(42L);
        schedule.setBusinessDateOffset(3);
        schedule.setCutoverTime("15:30");
        schedule.setEarliestExecTime("09:00");

        when(scheduleDao.queryById(1)).thenReturn(schedule);
        when(workflowDefinitionDao.queryByCode(wfCode))
                .thenReturn(Optional.of(buildWorkflowDef(wfCode)));

        Date fireTime = new Date();
        JobExecutionContext ctx = mockContext(100, 1, fireTime);

        processScheduleTask.executeInternal(ctx);

        ArgumentCaptor<WorkflowScheduleTriggerRequest> captor =
                ArgumentCaptor.forClass(WorkflowScheduleTriggerRequest.class);
        verify(workflowInstanceController).scheduleTriggerWorkflow(captor.capture());

        WorkflowScheduleTriggerRequest captured = captor.getValue();
        assertEquals(42L, captured.getCalendarId());
        assertEquals(3, captured.getBusinessDateOffset());
        assertEquals("15:30", captured.getCutoverTime());
        assertEquals("09:00", captured.getEarliestExecTime());
        assertEquals(fireTime, captured.getScheduleTIme());
    }

    // =====================================================================
    // 2. calendarId = null 时也应正常工作
    // =====================================================================

    @Test
    public void testNullCalendarFieldsForwarded() {
        long wfCode = 10002L;
        Schedule schedule = buildSchedule(wfCode);
        // all calendar fields null by default

        when(scheduleDao.queryById(1)).thenReturn(schedule);
        when(workflowDefinitionDao.queryByCode(wfCode))
                .thenReturn(Optional.of(buildWorkflowDef(wfCode)));

        JobExecutionContext ctx = mockContext(100, 1, new Date());
        processScheduleTask.executeInternal(ctx);

        ArgumentCaptor<WorkflowScheduleTriggerRequest> captor =
                ArgumentCaptor.forClass(WorkflowScheduleTriggerRequest.class);
        verify(workflowInstanceController).scheduleTriggerWorkflow(captor.capture());

        WorkflowScheduleTriggerRequest captured = captor.getValue();
        assertNull(captured.getCalendarId());
        assertNull(captured.getBusinessDateOffset());
        assertNull(captured.getCutoverTime());
        assertNull(captured.getEarliestExecTime());
    }

    // =====================================================================
    // 3. schedule 为 null → 不触发 workflow
    // =====================================================================

    @Test
    public void testScheduleNotFound_NoTrigger() {
        when(scheduleDao.queryById(1)).thenReturn(null);

        JobExecutionContext ctx = mockContext(100, 1, new Date());
        processScheduleTask.executeInternal(ctx);

        verify(workflowInstanceController, never())
                .scheduleTriggerWorkflow(any(WorkflowScheduleTriggerRequest.class));
    }

    // =====================================================================
    // 4. schedule OFFLINE → 不触发 workflow
    // =====================================================================

    @Test
    public void testScheduleOffline_NoTrigger() {
        Schedule schedule = buildSchedule(10003L);
        schedule.setReleaseState(ReleaseState.OFFLINE);

        when(scheduleDao.queryById(1)).thenReturn(schedule);

        JobExecutionContext ctx = mockContext(100, 1, new Date());
        processScheduleTask.executeInternal(ctx);

        verify(workflowInstanceController, never())
                .scheduleTriggerWorkflow(any(WorkflowScheduleTriggerRequest.class));
    }
}
