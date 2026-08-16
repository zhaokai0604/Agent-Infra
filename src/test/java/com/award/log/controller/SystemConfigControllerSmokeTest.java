package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.SystemBootstrapService;
import com.award.log.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SystemConfigControllerSmokeTest {

    @Mock private SystemConfigService systemConfigService;
    @Mock private SystemBootstrapService systemBootstrapService;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SystemConfigController controller = new SystemConfigController(
                systemConfigService,
                systemBootstrapService,
                requestUserResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void effectiveReturnsConfigView() throws Exception {
        when(systemConfigService.getEffectiveConfig()).thenReturn(Map.of("pingTarget", "127.0.0.1"));
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(requestUserResolver.currentUserRole(any())).thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(requestUserResolver.authMode(any())).thenReturn("session");

        mockMvc.perform(get("/api/system-config/effective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pingTarget").value("127.0.0.1"))
                .andExpect(jsonPath("$.data.viewer.editable").value(true));
    }

    @Test
    void reconcileBootstrapReturnsBootstrapStatusForAdmin() throws Exception {
        when(systemBootstrapService.reconcileNow()).thenReturn(Map.of("corrected", true));
        when(systemConfigService.getEffectiveConfig()).thenReturn(Map.of("groups", Map.of()));
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(requestUserResolver.currentUserRole(any())).thenReturn(1);
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(requestUserResolver.authMode(any())).thenReturn("session");

        mockMvc.perform(post("/api/system-config/bootstrap/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bootstrap.corrected").value(true))
                .andExpect(jsonPath("$.data.viewer.editable").value(true));
    }
}
