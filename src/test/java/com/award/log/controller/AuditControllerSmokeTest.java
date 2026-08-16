package com.award.log.controller;

import com.award.log.service.UnifiedAuditService;
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
class AuditControllerSmokeTest {

    @Mock private UnifiedAuditService unifiedAuditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditController(unifiedAuditService)).build();
    }

    @Test
    void feedReturnsUnifiedAuditRows() throws Exception {
        when(unifiedAuditService.feed(50, "tool")).thenReturn(List.of(Map.of(
                "entryId", "trace:t1",
                "auditKind", "tool")));

        mockMvc.perform(get("/api/audit/feed").param("limit", "50").param("kind", "tool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].entryId").value("trace:t1"));
    }

    @Test
    void detailReturns404WhenMissing() throws Exception {
        when(unifiedAuditService.detail(null, "missing")).thenReturn(Map.of());

        mockMvc.perform(get("/api/audit/detail").param("traceId", "missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
