package com.award.log.controller;

import com.award.log.service.AiAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LlmChatControllerSmokeTest {

    @Mock private AiAnalysisService aiAnalysisService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LlmChatController controller = new LlmChatController(aiAnalysisService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void chatSyncReturnsAnswer() throws Exception {
        when(aiAnalysisService.analyzeLog(anyString())).thenReturn("analysis result");

        mockMvc.perform(post("/api/v1/llm/chat-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"check logs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("analysis result"));
    }

    @Test
    void chatSyncNormalizesNullAnswer() throws Exception {
        when(aiAnalysisService.analyzeLog(anyString())).thenReturn(null);

        mockMvc.perform(post("/api/v1/llm/chat-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"check logs\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value(""))
                .andExpect(jsonPath("$.data.empty").value(true));
    }

    @Test
    void chatStreamReturnsEmitter() throws Exception {
        mockMvc.perform(post("/api/v1/llm/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"hello\"}"))
                .andExpect(status().isOk());
    }
}
