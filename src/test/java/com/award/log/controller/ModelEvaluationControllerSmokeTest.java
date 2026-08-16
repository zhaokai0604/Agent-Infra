package com.award.log.controller;

import com.award.log.service.ModelEvaluationService;
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

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ModelEvaluationControllerSmokeTest {

    @Mock private ModelEvaluationService modelEvaluationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ModelEvaluationController controller = new ModelEvaluationController(modelEvaluationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void evaluateRejectsEmptyDataset() throws Exception {
        mockMvc.perform(post("/api/v1/model/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelVersion\":\"rf-onnx-v2\",\"dataset\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void evaluateReturnsMetrics() throws Exception {
        when(modelEvaluationService.evaluate(anyString(), anyList()))
                .thenReturn(Map.of("accuracy", 0.95));

        mockMvc.perform(post("/api/v1/model/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelVersion\":\"rf-onnx-v2\",\"dataset\":[{\"features\":[1],\"label\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accuracy").value(0.95));
    }
}
