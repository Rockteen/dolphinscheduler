package org.apache.dolphinscheduler.server.master.engine.command;

import org.apache.dolphinscheduler.dao.entity.Command;
import org.apache.dolphinscheduler.dao.mapper.CommandMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Date;

@ExtendWith(MockitoExtension.class)
public class CommandDependencyEvaluatorTest {

    @InjectMocks
    private CommandDependencyEvaluator evaluator;

    @Mock
    private CommandMapper commandMapper;

    private void invokeEvaluateCommand(Command cmd) throws Exception {
        Method evaluateMethod = CommandDependencyEvaluator.class.getDeclaredMethod("evaluateCommand", Command.class);
        evaluateMethod.setAccessible(true);
        evaluateMethod.invoke(evaluator, cmd);
    }

    @Test
    public void testEvaluateCommand_WaitTime() throws Exception {
        Command cmd = new Command();
        cmd.setId(1);
        cmd.setCommandState(1); // CHECK_PENDING
        cmd.setWaitReason(0); // NONE
        
        // set earliest timeout time in the future
        cmd.setEarliestTimeoutTime(new Date(System.currentTimeMillis() + 100000));
        
        invokeEvaluateCommand(cmd);
        
        // Since earliest time is future, it should wait
        Assertions.assertEquals(1, cmd.getCommandState()); // still CHECK_PENDING
        Assertions.assertEquals(1, cmd.getWaitReason());   // WAIT_TIME
        Mockito.verify(commandMapper, Mockito.times(1)).updateById(cmd);
    }
    
    @Test
    public void testEvaluateCommand_Ready() throws Exception {
        Command cmd = new Command();
        cmd.setId(2);
        cmd.setCommandState(1); // CHECK_PENDING
        cmd.setWaitReason(1); // was WAIT_TIME
        
        // set earliest timeout time in the past
        cmd.setEarliestTimeoutTime(new Date(System.currentTimeMillis() - 100000));
        
        invokeEvaluateCommand(cmd);
        
        // Should be converted to READY (0) and wait reason NONE (0)
        Assertions.assertEquals(0, cmd.getCommandState()); 
        Assertions.assertEquals(0, cmd.getWaitReason()); 
        Mockito.verify(commandMapper, Mockito.times(1)).updateById(cmd);
    }

    @Test
    public void testEvaluateCommand_NullTimeIsReady() throws Exception {
        Command cmd = new Command();
        cmd.setId(3);
        cmd.setCommandState(1); // CHECK_PENDING
        cmd.setWaitReason(1); // was WAIT_TIME
        
        // earliest timeout time is null
        cmd.setEarliestTimeoutTime(null);
        
        invokeEvaluateCommand(cmd);
        
        // Should be converted to READY (0) and wait reason NONE (0)
        Assertions.assertEquals(0, cmd.getCommandState()); 
        Assertions.assertEquals(0, cmd.getWaitReason()); 
        Mockito.verify(commandMapper, Mockito.times(1)).updateById(cmd);
    }

    @Test
    public void testEvaluateCommand_NoChangeNoUpdate() throws Exception {
        Command cmd = new Command();
        cmd.setId(4);
        cmd.setCommandState(1); // CHECK_PENDING
        cmd.setWaitReason(1); // was already WAIT_TIME
        
        // earliest timeout time in future
        cmd.setEarliestTimeoutTime(new Date(System.currentTimeMillis() + 100000));
        
        invokeEvaluateCommand(cmd);
        
        // Should not call updateById since state/reason haven't changed
        Assertions.assertEquals(1, cmd.getCommandState()); 
        Assertions.assertEquals(1, cmd.getWaitReason()); 
        Mockito.verify(commandMapper, Mockito.never()).updateById(Mockito.any());
    }
}
