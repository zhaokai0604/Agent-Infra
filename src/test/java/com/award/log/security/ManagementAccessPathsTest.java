package com.award.log.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementAccessPathsTest {

    @Test
    void recognizesManagementRoutes() {
        assertTrue(ManagementAccessPaths.isManagementPath("/admin/user/login"));
        assertTrue(ManagementAccessPaths.isManagementPath("/api/mcp/tools"));
        assertTrue(ManagementAccessPaths.isManagementPath("/api/ops/patrol/history"));
        assertTrue(ManagementAccessPaths.isManagementPath("/api/ops-trace/recent"));
        assertTrue(ManagementAccessPaths.isManagementPath("/api/platform/info"));
        assertTrue(ManagementAccessPaths.isManagementPath("/ws/performance"));
    }

    @Test
    void leavesBusinessRoutesOnBusinessPort() {
        assertFalse(ManagementAccessPaths.isManagementPath("/log/upload"));
        assertFalse(ManagementAccessPaths.isManagementPath("/api/v1/knowledge/search"));
        assertFalse(ManagementAccessPaths.isManagementPath("/api/profile/user-info"));
        assertFalse(ManagementAccessPaths.isManagementPath("/api/platform/acceptance"));
    }
}
