package com.award.log.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminAccessPathsTest {

    @Test
    void publicPathsDoNotIncludeResetPassword() {
        assertTrue(AdminAccessPaths.isPublic("/admin/user/login"));
        assertTrue(AdminAccessPaths.isPublic("/admin/user/register"));
        assertTrue(AdminAccessPaths.isPublic("/admin/user/check-user"));
        assertFalse(AdminAccessPaths.isPublic("/admin/user/reset-password"));
    }

    @Test
    void adminPathsRequireAdminRole() {
        assertTrue(AdminAccessPaths.requiresAdmin("/admin/user/list"));
        assertTrue(AdminAccessPaths.requiresAdmin("/admin/user/reset-password"));
        assertTrue(AdminAccessPaths.requiresAdmin("/admin/role"));
        assertFalse(AdminAccessPaths.requiresAdmin("/admin/user/logout"));
        assertFalse(AdminAccessPaths.requiresAdmin("/admin/statistics/log-summary"));
        assertFalse(AdminAccessPaths.requiresAdmin("/admin/statistics/task-status"));
    }
}
