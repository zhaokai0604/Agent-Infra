package com.award.log.controller;

import com.award.log.model.LogDocument;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.ElasticsearchService;
import com.award.log.task.AnalysisTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ElasticsearchControllerSmokeTest {

    @Mock private ElasticsearchService elasticsearchService;
    @Mock private RequestUserResolver requestUserResolver;
    @Mock private AnalysisTaskManager analysisTaskManager;

    private ElasticsearchController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ElasticsearchController(
                requestUserResolver, analysisTaskManager);
        ReflectionTestUtils.setField(controller, "elasticsearchService", elasticsearchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void searchRequiresAdmin() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(false);

        mockMvc.perform(get("/api/elasticsearch/search").param("query", "error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void indexLogReturnsDocumentForAdmin() throws Exception {
        when(requestUserResolver.isAdmin(any())).thenReturn(true);
        LogDocument doc = new LogDocument();
        doc.setId("log-1");
        when(elasticsearchService.indexLog(any(LogDocument.class))).thenReturn(doc);

        mockMvc.perform(post("/api/elasticsearch/index")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"error line\",\"severity\":\"ERROR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("log-1"));
    }

    @Test
    void directEndpointsHandleAccessControlAndErrors() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        LogDocument document = new LogDocument();
        document.setId("doc-1");
        document.setTaskId("task-1");

        when(elasticsearchService.getLogById("doc-1")).thenReturn(document);
        when(requestUserResolver.isAdmin(request)).thenReturn(false);
        when(requestUserResolver.currentUserId(request)).thenReturn(9);
        when(analysisTaskManager.canAccessTask("task-1", 9, false)).thenReturn(false);

        assertEquals(404, controller.getLogById(request, "doc-1").getCode());

        when(analysisTaskManager.canAccessTask("task-1", 9, false)).thenReturn(true);
        assertEquals(200, controller.getLogById(request, "doc-1").getCode());
        assertEquals(200, controller.getLogByIdLegacyPath(request, "doc-1").getCode());
    }

    @Test
    void searchAndDeleteEndpointsCoverSuccessUnavailableAndValidationBranches() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(requestUserResolver.isAdmin(request)).thenReturn(true);
        when(requestUserResolver.currentUserId(request)).thenReturn(1);
        when(analysisTaskManager.canAccessTask("task-2", 1, true)).thenReturn(true);
        when(elasticsearchService.searchLogs(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(elasticsearchService.searchLogs(any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(elasticsearchService.getLogsByTimeRange(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(elasticsearchService.getLogsBySeverity(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(elasticsearchService.getLogsByTaskId(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(elasticsearchService.getAnomalyLogs(any())).thenReturn(new PageImpl<>(List.of()));
        when(elasticsearchService.deleteLogsBefore(any(LocalDateTime.class))).thenReturn(3L);
        when(elasticsearchService.deleteLogsByTaskId("task-2")).thenReturn(2L);

        assertEquals(200, controller.getLogs(request, 0, 0).getCode());
        assertEquals(200, controller.searchLogs(request, "error", "ERROR", Boolean.TRUE,
                "2026-07-04T04:00:00", "2026-07-04T05:00:00", 1, 10).getCode());
        assertEquals(500, controller.searchLogs(request, "error", "ERROR", Boolean.TRUE,
                "bad-date", "2026-07-04T05:00:00", 1, 10).getCode());
        assertEquals(200, controller.getLogsByTimeRange(request,
                "2026-07-04T04:00:00", "2026-07-04T05:00:00", 0, 10).getCode());
        assertEquals(500, controller.getLogsByTimeRange(request, "oops", "2026-07-04T05:00:00", 0, 10).getCode());
        assertEquals(200, controller.getLogsBySeverity(request, "WARN", 0, 10).getCode());
        assertEquals(200, controller.getLogsByTaskId(request, "task-2", 0, 10).getCode());
        assertEquals(200, controller.getAnomalyLogs(request, 0, 10).getCode());
        assertEquals(200, controller.deleteLogsBefore(request, "2026-07-04T05:00:00").getCode());
        assertEquals(500, controller.deleteLogsBefore(request, "bad-date").getCode());
        assertEquals(200, controller.deleteLogsByTaskId(request, "task-2").getCode());

        ReflectionTestUtils.setField(controller, "elasticsearchService", null);
        assertEquals(503, controller.getLogs(request, 1, 10).getCode());
        ReflectionTestUtils.setField(controller, "elasticsearchService", elasticsearchService);

        when(requestUserResolver.isAdmin(request)).thenReturn(false);
        assertEquals(403, controller.getLogs(request, 1, 10).getCode());
    }
}
