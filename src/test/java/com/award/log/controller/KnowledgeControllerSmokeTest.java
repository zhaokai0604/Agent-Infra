package com.award.log.controller;

import com.award.log.service.KnowledgeBaseService;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class KnowledgeControllerSmokeTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        KnowledgeController controller = new KnowledgeController(knowledgeBaseService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusReturnsKnowledgeState() throws Exception {
        when(knowledgeBaseService.getStatus()).thenReturn(Map.of("enabled", true));

        mockMvc.perform(get("/api/v1/knowledge/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void searchReturnsResults() throws Exception {
        when(knowledgeBaseService.search(anyString(), anyInt()))
                .thenReturn(List.of(Map.of("title", "doc1")));

        mockMvc.perform(get("/api/v1/knowledge/search")
                        .param("query", "disk")
                        .param("topK", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("doc1"));
    }

    @Test
    void uploadAcceptsDocument() throws Exception {
        when(knowledgeBaseService.upload(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("documentId", "d1"));

        mockMvc.perform(post("/api/v1/knowledge/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"hello\",\"category\":\"general\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").value("d1"));
    }
}
