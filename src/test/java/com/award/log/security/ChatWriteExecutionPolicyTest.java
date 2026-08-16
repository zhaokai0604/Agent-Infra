package com.award.log.security;

import com.award.log.security.OpsSecurityContext.Ctx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWriteExecutionPolicyTest {

    @AfterEach
    void tearDown() {
        OpsSecurityContext.clear();
    }

    @Test
    void httpConfirmedWriteForcesRealDelete() {
        OpsSecurityContext.open("t", "confirm", true, McpToolSurface.FULL, false, true);
        ChatWriteExecutionPolicy.ResolvedWrite write = ChatWriteExecutionPolicy.resolve(
                null, "/tmp/award-extract", 7, true, false, true);
        assertFalse(write.dryRun());
        assertTrue(write.confirmDelete());
        assertTrue(write.removeDirectory());
        assertTrue(write.days() == 0);
    }

    @Test
    void chatUnconfirmedStaysDryRun() {
        OpsSecurityContext.openChatAgent("t", "preview", McpToolSurface.FULL, false);
        ChatWriteExecutionPolicy.ResolvedWrite write = ChatWriteExecutionPolicy.resolve(
                null, "/tmp", 7, null, null, false);
        assertTrue(write.dryRun());
        assertFalse(write.confirmDelete());
    }
}
