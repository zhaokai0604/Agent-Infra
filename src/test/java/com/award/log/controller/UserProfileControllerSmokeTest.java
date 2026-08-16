package com.award.log.controller;

import com.award.log.model.SysUser;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.SysUserService;
import com.award.log.service.UserApiKeyService;
import com.award.log.service.UserProfilePreferenceService;
import com.award.log.service.impl.AiAuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserProfileControllerSmokeTest {

    @Mock private SysUserService sysUserService;
    @Mock private UserProfilePreferenceService preferenceService;
    @Mock private UserApiKeyService userApiKeyService;
    @Mock private AiAuditLogService aiAuditLogService;
    @Mock private RequestUserResolver requestUserResolver;

    private UserProfileController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new UserProfileController(
                sysUserService, preferenceService, userApiKeyService, aiAuditLogService, requestUserResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void userInfoRequiresLogin() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(null);

        mockMvc.perform(get("/api/profile/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void userInfoReturnsProfile() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        SysUser user = new SysUser();
        user.setUserId(1);
        user.setUsername("admin");
        when(sysUserService.getUserById(1)).thenReturn(user);

        mockMvc.perform(get("/api/profile/user-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void userStatsReturnsSummary() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(requestUserResolver.authMode(any())).thenReturn("session");
        when(sysUserService.getUserById(1)).thenReturn(new SysUser());
        when(aiAuditLogService.countByUserId(anyString())).thenReturn(3L);
        when(aiAuditLogService.listRecentByUserId(anyString(), anyInt())).thenReturn(List.of());
        when(userApiKeyService.countActiveForUser(1)).thenReturn(1L);

        mockMvc.perform(get("/api/profile/user-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.auditRequestCount").value(3));
    }

    @Test
    void updatePasswordPreferenceAndTrailEndpointsCoverHappyAndErrorPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(requestUserResolver.currentUserId(request)).thenReturn(11);
        when(sysUserService.updateOwnProfile(11, "ops@example.com", "wx-1")).thenReturn(true);
        when(sysUserService.getUserById(11)).thenReturn(user(11, "ops"));
        when(sysUserService.changeOwnPassword(11, "old-pass", "new-password")).thenReturn(true);
        when(preferenceService.getEffectivePreference(11)).thenReturn(Map.of("emailEnabled", true));
        when(preferenceService.savePreference(11, Map.of("emailEnabled", false))).thenReturn(Map.of("emailEnabled", false));
        when(aiAuditLogService.countByUserId("11")).thenReturn(2L);
        when(aiAuditLogService.listRecentByUserId("11", 1, 50)).thenReturn(List.of(Map.of("path", "/api/test")));
        when(aiAuditLogService.listRecentByUserId("11", 1, 10)).thenReturn(List.of(Map.of("path", "/api/test")));

        SysUser payload = new SysUser();
        payload.setEmail("ops@example.com");
        payload.setWechatUserid("wx-1");

        assertEquals(200, controller.updateUserInfo(request, payload).getCode());
        assertEquals(500, controller.updateUserInfo(request, new SysUser()).getCode());
        assertEquals(200, controller.changePassword(request, Map.of(
                "oldPassword", "old-pass",
                "newPassword", "new-password",
                "confirmPassword", "new-password")).getCode());
        assertEquals(500, controller.changePassword(request, Map.of(
                "oldPassword", "old-pass",
                "newPassword", "short",
                "confirmPassword", "short")).getCode());
        assertEquals(200, controller.getNotificationSettings(request).getCode());
        assertEquals(200, controller.updateNotificationSettings(request, Map.of("emailEnabled", false)).getCode());
        assertEquals(200, controller.accessTrail(request, 0, 80).getCode());
        assertEquals(200, controller.loginHistoryAlias(request, 1, 10).getCode());
    }

    @Test
    void apiKeyAndStatsEndpointsCoverLegacyAndFailureBranches() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(requestUserResolver.currentUserId(request)).thenReturn(12);
        when(requestUserResolver.authMode(request)).thenReturn("session");
        when(sysUserService.getUserById(12)).thenReturn(user(12, "alice"));
        when(aiAuditLogService.countByUserId("12")).thenReturn(4L);
        when(aiAuditLogService.listRecentByUserId("12", 1)).thenReturn(List.of(Map.of("created_at", "2026-07-04T04:30:00Z")));
        when(userApiKeyService.listForUser(12)).thenReturn(List.of(Map.of("id", 1L)));
        when(userApiKeyService.createForUser(12, "Primary")).thenReturn(Map.of("id", 2L, "name", "Primary"));
        when(userApiKeyService.rotateForUser(12, 2L)).thenReturn(Map.of("id", 2L, "rotated", true));
        when(userApiKeyService.revokeForUser(12, 2L)).thenReturn(Map.of("id", 2L, "revoked", true));
        when(userApiKeyService.countActiveForUser(12)).thenReturn(1L);

        assertEquals(200, controller.listApiKeys(request).getCode());
        assertEquals(200, controller.createApiKey(request, Map.of("name", "Primary")).getCode());
        assertEquals(200, controller.rotateApiKey(request, 2L).getCode());
        assertEquals(200, controller.revokeApiKey(request, 2L).getCode());
        assertEquals(200, controller.generateApiKeyLegacy(request).getCode());
        assertEquals(200, controller.userStats(request).getCode());

        when(userApiKeyService.createForUser(12, "Bad")).thenThrow(new IllegalArgumentException("bad name"));
        assertEquals(500, controller.createApiKey(request, Map.of("name", "Bad")).getCode());

        when(requestUserResolver.currentUserId(request)).thenReturn(null);
        assertEquals(401, controller.listApiKeys(request).getCode());
    }

    @Test
    void updateAndChangePasswordRejectInvalidInput() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(requestUserResolver.currentUserId(request)).thenReturn(13);
        when(sysUserService.updateOwnProfile(13, "ops@example.com", null)).thenReturn(false);
        when(sysUserService.changeOwnPassword(13, "old", "new-password")).thenReturn(false);

        SysUser invalidEmail = new SysUser();
        invalidEmail.setEmail("not-an-email");
        assertEquals(500, controller.updateUserInfo(request, invalidEmail).getCode());
        assertEquals(500, controller.updateUserInfo(request, null).getCode());

        SysUser validButFail = new SysUser();
        validButFail.setEmail("ops@example.com");
        assertEquals(500, controller.updateUserInfo(request, validButFail).getCode());

        assertEquals(500, controller.changePassword(request, null).getCode());
        assertEquals(500, controller.changePassword(request, Map.of(
                "oldPassword", "old",
                "newPassword", "new-password",
                "confirmPassword", "mismatch")).getCode());
        assertEquals(500, controller.changePassword(request, Map.of(
                "oldPassword", "old",
                "newPassword", "old",
                "confirmPassword", "old")).getCode());
        assertEquals(400, controller.changePassword(request, Map.of(
                "oldPassword", "old",
                "newPassword", "new-password",
                "confirmPassword", "new-password")).getCode());
    }

    private static SysUser user(int id, String username) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUsername(username);
        user.setPassword("secret");
        user.setRole(1);
        return user;
    }
}
