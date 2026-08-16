package com.award.log.controller;

import com.award.log.service.impl.UnifiedAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UnifiedAssistantControllerSmokeTest {

    @Mock
    private UnifiedAssistantService unifiedAssistantService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskExecutor sseExecutor = new ThreadPoolTaskExecutor();
        sseExecutor.setCorePoolSize(1);
        sseExecutor.setMaxPoolSize(1);
        sseExecutor.initialize();

        UnifiedAssistantController controller = new UnifiedAssistantController();
        ReflectionTestUtils.setField(controller, "unifiedAssistantService", unifiedAssistantService);
        ReflectionTestUtils.setField(controller, "sseExecutor", sseExecutor);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void contextReturnsAssistantSnapshot() throws Exception {
        when(unifiedAssistantService.getAssistantContext()).thenReturn(Map.of("mode", "ops"));

        mockMvc.perform(get("/api/assistant/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("ops"));
    }

    @Test
    void statePreviewReturnsAgentState() throws Exception {
        when(unifiedAssistantService.previewAgentState(anyString(), anyList(), anyBoolean(), anyBoolean()))
                .thenReturn(Map.of("phase", "plan"));

        mockMvc.perform(post("/api/assistant/state/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"check disk\",\"history\":[{\"role\":\"user\",\"content\":\"hello\"}],\"useToolAgent\":true,\"confirmRemediation\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phase").value("plan"));
    }

    @Test
    void chatStreamAcceptsMessageWithHistory() throws Exception {
        lenient().when(unifiedAssistantService.chatStream(anyString(), anyList(), anyBoolean(), anyBoolean()))
                .thenReturn(Flux.just("ok"));

        mockMvc.perform(post("/api/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"check disk\",\"history\":[{\"role\":\"user\",\"content\":\"hello\"}],\"useToolAgent\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void chatStreamRejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void chatStreamRejectsWhitespaceMessage() throws Exception {
        mockMvc.perform(post("/api/assistant/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isOk());
    }
}
