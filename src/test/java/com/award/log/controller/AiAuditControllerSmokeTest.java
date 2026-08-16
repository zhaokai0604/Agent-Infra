package com.award.log.controller;

import com.award.log.service.impl.AiAuditLogService;
import com.award.log.security.RequestUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AiAuditControllerSmokeTest {

    @Mock
    private AiAuditLogService aiAuditLogService;
    @Mock
    private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiAuditController controller = new AiAuditController(aiAuditLogService, requestUserResolver, true);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void recentReturnsAuditEntries() throws Exception {
        when(aiAuditLogService.listRecent(100)).thenReturn(List.of(Map.of("traceId", "t1")));

        mockMvc.perform(get("/admin/audit/ai/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].traceId").value("t1"));
    }
}
