package com.award.log.controller;

import com.award.log.security.RequestUserResolver;
import com.award.log.service.AgentPathPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentPathPolicyControllerSmokeTest {

    @Mock AgentPathPolicyService agentPathPolicyService;
    @Mock RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AgentPathPolicyController(agentPathPolicyService, requestUserResolver)).build();
    }

    @Test
    void adminCanReadPolicy() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(agentPathPolicyService.getEffectivePolicyView()).thenReturn(Map.of("version", "v1"));

        mockMvc.perform(get("/api/agent/path-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("v1"));
    }

    @Test
    void nonAdminDenied() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(false);

        mockMvc.perform(get("/api/agent/path-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanSavePolicy() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(agentPathPolicyService.saveEditablePolicy(any()))
                .thenReturn(new LinkedHashMap<>(Map.of("saved", true)));

        mockMvc.perform(put("/api/agent/path-policy")
                        .contentType("application/json")
                        .content("{\"readPrefixes\":[\"/tmp\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.saved").value(true));
    }
}
