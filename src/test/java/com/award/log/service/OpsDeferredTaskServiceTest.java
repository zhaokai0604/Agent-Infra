package com.award.log.service;

import com.award.log.controller.McpExecuteController;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsDeferredTaskServiceTest {

    @Test
    void nonAdminOnlySeesOwnTasks() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            OpsDeferredTaskService service = new OpsDeferredTaskService(
                    scheduler,
                    new McpExecuteController(null, null, null, null, null, null, null, null, null));
            service.schedule(10, "DiskTool", Map.of(), "u1");
            service.schedule(10, "DiskTool", Map.of(), "u2");

            List<Map<String, Object>> user1 = service.listPending("u1", false);
            List<Map<String, Object>> admin = service.listPending("admin", true);

            assertEquals(1, user1.size());
            assertEquals("u1", user1.get(0).get("createdBy"));
            assertEquals(2, admin.size());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void nonAdminCannotCancelOthersTask() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        try {
            OpsDeferredTaskService service = new OpsDeferredTaskService(
                    scheduler,
                    new McpExecuteController(null, null, null, null, null, null, null, null, null));
            String taskId = String.valueOf(service.schedule(10, "DiskTool", Map.of(), "u1").get("taskId"));
            assertFalse(service.cancel(taskId, "u2", false));
            assertTrue(service.cancel(taskId, "u1", false));
        } finally {
            scheduler.shutdown();
        }
    }
}
