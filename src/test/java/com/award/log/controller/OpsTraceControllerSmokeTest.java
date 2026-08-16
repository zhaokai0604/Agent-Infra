package com.award.log.controller;

import com.award.log.service.OpsAuditTraceService;
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
class OpsTraceControllerSmokeTest {

    @Mock private OpsAuditTraceService opsAuditTraceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        OpsTraceController controller = new OpsTraceController(opsAuditTraceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void recentReturnsTraceList() throws Exception {
        when(opsAuditTraceService.listRecent(100)).thenReturn(List.of(Map.of("traceId", "t1")));

        mockMvc.perform(get("/api/ops-trace/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].traceId").value("t1"));
    }

    @Test
    void detailReturnsTraceRow() throws Exception {
        when(opsAuditTraceService.findByTraceId("t1")).thenReturn(Map.of("traceId", "t1"));

        mockMvc.perform(get("/api/ops-trace/detail").param("traceId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.traceId").value("t1"));
    }
}
