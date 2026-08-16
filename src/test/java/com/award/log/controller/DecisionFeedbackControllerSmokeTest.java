package com.award.log.controller;

import com.award.log.mapper.DecisionFeedbackMapper;
import com.award.log.mapper.DecisionLogMapper;
import com.award.log.model.DecisionFeedback;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.impl.ContinuousLearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DecisionFeedbackControllerSmokeTest {

    @Mock private DecisionFeedbackMapper decisionFeedbackMapper;
    @Mock private DecisionLogMapper decisionLogMapper;
    @Mock private ContinuousLearningService continuousLearningService;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DecisionFeedbackController controller = new DecisionFeedbackController(
                decisionFeedbackMapper,
                decisionLogMapper,
                continuousLearningService,
                requestUserResolver,
                new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void submitFeedbackRequiresLogin() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(null);

        mockMvc.perform(post("/api/decision-feedback/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionId\":\"d1\",\"actualAlert\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void untrainedCountReturnsNumber() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        when(decisionFeedbackMapper.countUntrained()).thenReturn(3);

        mockMvc.perform(get("/api/decision-feedback/untrained/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    void submitFeedbackSucceedsWhenLoggedIn() throws Exception {
        when(requestUserResolver.currentUserId(any())).thenReturn(1);
        when(decisionFeedbackMapper.upsert(any(DecisionFeedback.class))).thenReturn(1);

        mockMvc.perform(post("/api/decision-feedback/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisionId\":\"d1\",\"actualAlert\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
