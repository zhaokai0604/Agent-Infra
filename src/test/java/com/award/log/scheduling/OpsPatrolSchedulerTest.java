package com.award.log.scheduling;

import com.award.log.service.OpsPatrolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpsPatrolSchedulerTest {

    @Mock
    private OpsPatrolService opsPatrolService;

    @InjectMocks
    private OpsPatrolScheduler scheduler;

    @Test
    void runPatrolShouldInvokeServiceWhenEnabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        scheduler.runPatrol();
        verify(opsPatrolService).runPatrolCycle();
    }

    @Test
    void runPatrolShouldSkipWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        scheduler.runPatrol();
        verifyNoInteractions(opsPatrolService);
    }
}
