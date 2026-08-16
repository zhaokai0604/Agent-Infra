package com.award.log.controller;

import com.award.log.service.SecuritySelfCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SecuritySelfCheckControllerSmokeTest {

    @Mock private SecuritySelfCheckService securitySelfCheckService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecuritySelfCheckController controller = new SecuritySelfCheckController(securitySelfCheckService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void selfCheckReturnsReport() throws Exception {
        when(securitySelfCheckService.run()).thenReturn(Map.of("passed", true));

        mockMvc.perform(get("/api/security/self-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.passed").value(true));
    }
}
