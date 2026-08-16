package com.award.log;

import com.award.log.analyzer.RealTimeLogAnalyzer;
import com.award.log.collector.LogCollectorManager;
import com.award.log.common.GlobalExceptionHandler;
import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.handler.LogWebSocketHandler;
import com.award.log.handler.PerformanceWebSocketHandler;
import com.award.log.handler.WebSocketMessageBuffer;
import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.mapper.LogAnalysisTaskMapper;
import com.award.log.model.LogAnalysisTask;
import com.award.log.mcp.dispatch.McpToolDispatchResult;
import com.award.log.mcp.dispatch.McpToolDispatcher;
import com.award.log.platform.KylinCommandProbe;
import com.award.log.security.McpInvocationSecurityGate;
import com.award.log.security.McpInvocationSecurityGate.GateDecision;
import com.award.log.security.RiskLevel;
import com.award.log.service.PerformanceAnalysisService;
import com.award.log.service.StatisticsService;
import com.award.log.service.mcp.McpExecutionService;
import com.award.log.service.mcp.McpSecurityService;
import com.award.log.service.StorageStats;
import com.award.log.support.PojoExerciseSupport;
import com.award.log.task.AnalysisTaskManager;
import com.award.log.task.TaskInfo;
import com.award.log.websocket.LogStreamWebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LowCoveragePackagesBulkTest {

    @Mock
    private LogAnalysisTaskMapper taskMapper;
    @Mock
    private LogAnalysisDetailMapper detailMapper;
    @Mock
    private McpToolDispatcher mcpToolDispatcher;
    @Mock
    private McpInvocationSecurityGate securityGate;
    @Mock
    private LogCollectorManager logCollectorManager;
    @Mock
    private RealTimeLogAnalyzer realTimeLogAnalyzer;
    @Mock
    private PerformanceAnalysisService performanceAnalysisService;
    @Mock
    private StatisticsService statisticsService;
    @Mock
    private WebSocketSession webSocketSession;

    @AfterEach
    void resetLogStreamStaticFlag() {
        ReflectionTestUtils.setField(LogStreamWebSocketHandler.class, "realTimeAnalysisStarted", false);
    }

    @Test
    void result_successAndErrorFactories() {
        Result<String> ok = Result.success("data");
        assertEquals(200, ok.getCode());
        assertEquals("data", ok.getData());

        Result<Void> bare = Result.success();
        assertEquals(200, bare.getCode());

        Result<String> withMsg = Result.success("x", "done");
        assertEquals("done", withMsg.getMessage());

        Result<String> err = Result.error("fail");
        assertEquals(500, err.getCode());

        Result<String> errCode = Result.error(403, "denied");
        assertEquals(403, errCode.getCode());
    }

    @Test
    void globalExceptionHandler_mapsExceptions() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<String> runtime = handler.handleRuntimeException(new RuntimeException("boom"));
        assertEquals(500, runtime.getCode());

        Result<String> generic = handler.handleException(new Exception("oops"));
        assertEquals(500, generic.getCode());
    }

    @Test
    void analysisTaskManager_initAndQuery() {
        AnalysisTaskManager manager = new AnalysisTaskManager();
        ReflectionTestUtils.setField(manager, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(manager, "detailMapper", detailMapper);

        when(taskMapper.insert(any(LogAnalysisTask.class))).thenReturn(1);
        TaskInfo created = manager.initTask("t-1", 1, "sample.log");
        assertEquals("t-1", created.getTaskId());
        assertEquals("PENDING", created.getStatus());

        when(taskMapper.countWithFilter(isNull(), isNull(), isNull(), isNull())).thenReturn(1L);
        when(taskMapper.selectPageWithFilter(anyInt(), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(buildTaskEntity("t-1")));
        PageResult<TaskInfo> page = manager.getTasksPage(1, 10);
        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getList().size());

        when(taskMapper.selectById("t-1")).thenReturn(buildTaskEntity("t-1"));
        assertTrue(manager.canAccessTask("t-1", null, true));
        assertNotNull(manager.getTask("t-1"));
    }

    @Test
    void taskInfo_summaryDefaults() {
        TaskInfo info = new TaskInfo();
        TaskInfo.TaskSummary summary = new TaskInfo.TaskSummary();
        summary.setTotalLogs(10);
        summary.setAnomalyCount(2);
        info.setSummary(summary);
        assertEquals(10, info.getSummary().getTotalLogs());
        PojoExerciseSupport.exerciseAll(TaskInfo.class, TaskInfo.TaskSummary.class, StorageStats.class);
    }

    @Test
    void webSocketMessageBuffer_recordsAndSnapshots() {
        WebSocketMessageBuffer buffer = new WebSocketMessageBuffer(10);
        buffer.record(Map.of("type", "perf", "cpu", 12));
        List<String> snap = buffer.snapshotJsonMessages();
        assertEquals(1, snap.size());
        assertTrue(snap.get(0).contains("perf"));
    }

    @Test
    void logWebSocketHandler_refreshMessage() throws Exception {
        LogWebSocketHandler handler = new LogWebSocketHandler();
        ReflectionTestUtils.setField(handler, "logCollectorManager", logCollectorManager);
        when(logCollectorManager.takeFanOut()).thenReturn(List.of());
        when(webSocketSession.getId()).thenReturn("ws-log-1");

        handler.afterConnectionEstablished(webSocketSession);
        ReflectionTestUtils.invokeMethod(handler, "handleTextMessage", webSocketSession, new TextMessage("refresh"));
        handler.afterConnectionClosed(webSocketSession, CloseStatus.NORMAL);
    }

    @Test
    void performanceWebSocketHandler_syncReplay() throws Exception {
        WebSocketMessageBuffer buffer = new WebSocketMessageBuffer(20);
        buffer.record(Map.of("type", "metrics", "load", 1.2));
        PerformanceWebSocketHandler handler = new PerformanceWebSocketHandler(buffer);
        ReflectionTestUtils.setField(handler, "performanceAnalysisService", performanceAnalysisService);
        ReflectionTestUtils.setField(handler, "statisticsService", statisticsService);
        lenient().when(statisticsService.getSystemPerformance(isNull())).thenReturn(Map.of("cpu", 10.0));
        when(webSocketSession.getId()).thenReturn("ws-perf-1");
        when(webSocketSession.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(webSocketSession);
        verify(webSocketSession, atLeastOnce()).sendMessage(any(TextMessage.class));
        ReflectionTestUtils.invokeMethod(handler, "handleTextMessage", webSocketSession, new TextMessage("sync"));
        handler.afterConnectionClosed(webSocketSession, CloseStatus.NORMAL);
        handler.destroy();
    }

    @Test
    void logStreamWebSocketHandler_lifecycleAndBroadcast() throws Exception {
        LogStreamWebSocketHandler handler = new LogStreamWebSocketHandler();
        ReflectionTestUtils.setField(handler, "logCollectorManager", logCollectorManager);
        ReflectionTestUtils.setField(handler, "realTimeLogAnalyzer", realTimeLogAnalyzer);
        doNothing().when(realTimeLogAnalyzer).registerWebSocketHandler(any());
        doNothing().when(realTimeLogAnalyzer).startRealTimeAnalysis();

        handler.init();
        assertEquals(0, handler.getActiveSessionCount());

        when(webSocketSession.getId()).thenReturn("stream-1");
        doNothing().when(webSocketSession).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(webSocketSession);
        assertEquals(1, handler.getActiveSessionCount());

        handler.broadcastLogData("{\"line\":\"test\"}");
        ReflectionTestUtils.invokeMethod(handler, "handleTextMessage", webSocketSession, new TextMessage("ping"));
        handler.afterConnectionClosed(webSocketSession, CloseStatus.NORMAL);
        handler.destroy();
    }

    @Test
    void kylinCommandProbe_disabledSkipsRun() {
        KylinCommandProbe probe = new KylinCommandProbe();
        ReflectionTestUtils.setField(probe, "probeEnabled", false);
        probe.run(mock(ApplicationArguments.class));
        assertTrue(probe.getLastProbeResult().isEmpty());
    }

    @Test
    void mcpExecutionService_dispatchesTool() {
        when(mcpToolDispatcher.dispatch(eq("DiskTool"), anyMap()))
                .thenReturn(new McpToolDispatchResult(true, "{\"ok\":true}", null));
        McpExecutionService service = new McpExecutionService(mcpToolDispatcher, new com.fasterxml.jackson.databind.ObjectMapper());

        Map<String, Object> params = new HashMap<>();
        params.put("dryRun", true);
        Map<String, Object> response = service.execute("DiskTool", params, System.currentTimeMillis(),
                "trace-99", "check disk");

        assertEquals(true, response.get("success"));
        assertNotNull(response.get("data"));
    }

    @Test
    void mcpSecurityService_buildsResponses() {
        McpSecurityService service = new McpSecurityService(securityGate);
        GateDecision block = GateDecision.block("INJECTION", "blocked");
        Map<String, Object> blockResp = service.buildBlockResponse("t1", System.currentTimeMillis(), block);
        assertEquals(false, blockResp.get("success"));

        GateDecision confirm = GateDecision.needConfirm(RiskLevel.MEDIUM, "confirm?", 3.5, Map.of("x", 1.0), "risk");
        Map<String, Object> confirmResp = service.buildNeedConfirmResponse(
                "t2", System.currentTimeMillis(), confirm, "ServiceRestartTool", Map.of("dryRun", true));
        assertEquals(true, confirmResp.get("needConfirm"));

        assertEquals("REJECT_INJECTION", McpSecurityService.mapGateCodeToOutcome("INJECTION"));
        assertTrue(McpSecurityService.formatRiskScoreLine(confirm).contains("3.5"));
    }

    private static LogAnalysisTask buildTaskEntity(String taskId) {
        LogAnalysisTask task = new LogAnalysisTask();
        task.setTaskId(taskId);
        task.setFileName("sample.log");
        task.setStatus("PENDING");
        task.setProgress(0);
        return task;
    }
}
