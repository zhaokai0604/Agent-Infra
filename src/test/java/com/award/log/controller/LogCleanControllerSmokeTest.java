package com.award.log.controller;

import com.award.log.analyzer.LogCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LogCleanControllerSmokeTest {

    @Mock
    private LogCleaner logCleaner;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LogCleanController controller = new LogCleanController();
        ReflectionTestUtils.setField(controller, "logCleaner", logCleaner);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getCleanRulesReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/log/clean/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void cleanLogReturnsCleanedContent() throws Exception {
        when(logCleaner.cleanLog(any(), anyList())).thenReturn("cleaned");

        mockMvc.perform(post("/api/log/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logContent\":\"raw line\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("cleaned"));
    }
}
