package com.award.log.mcp.dispatch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolParamReaderTest {

    @Test
    void readersHandleNullAndTypedValues() {
        assertNull(McpToolParamReader.getString(null, "k"));
        assertNull(McpToolParamReader.getInteger(null, "k"));
        assertNull(McpToolParamReader.getDouble(null, "k"));
        assertNull(McpToolParamReader.getLong(null, "k"));
        assertNull(McpToolParamReader.getBoolean(null, "k"));

        Map<String, Object> params = Map.of(
                "s", "text",
                "i", 3,
                "d", 2.5,
                "l", 9L,
                "b", true,
                "badInt", "x",
                "badDouble", "y",
                "badLong", "z");
        assertEquals("text", McpToolParamReader.getString(params, "s"));
        assertEquals(3, McpToolParamReader.getInteger(params, "i"));
        assertEquals(2.5, McpToolParamReader.getDouble(params, "d"));
        assertEquals(9L, McpToolParamReader.getLong(params, "l"));
        assertTrue(McpToolParamReader.getBoolean(params, "b"));
        assertNull(McpToolParamReader.getInteger(params, "badInt"));
        assertNull(McpToolParamReader.getDouble(params, "badDouble"));
        assertNull(McpToolParamReader.getLong(params, "badLong"));
    }

    @Test
    void forceConfirmedWriteToolParamsForCleanTemp() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/tmp/demo");
        McpToolParamReader.forceConfirmedWriteToolParams("CleanTempTool", params);
        assertEquals(false, params.get("dryRun"));
        assertEquals(true, params.get("confirmDelete"));
        assertEquals(true, params.get("removeDirectory"));
        assertEquals(0, params.get("days"));
    }

    @Test
    void applyConfirmedWriteRespectsUserMessage() {
        Map<String, Object> params = new HashMap<>();
        params.put("dryRun", true);
        McpToolParamReader.applyConfirmedWriteToolParams(
                "ServiceRestartTool", params, "确认执行重启 nginx");
        assertEquals(false, params.get("dryRun"));
        assertEquals(true, params.get("confirmRestart"));
    }

    @Test
    void shouldEscalateToRealWriteFromConfirmFlags() {
        assertTrue(McpToolParamReader.shouldEscalateToRealWrite(
                Map.of("confirmDelete", true), null));
        assertTrue(McpToolParamReader.shouldEscalateToRealWrite(
                Map.of("dryRun", false), null));
        assertFalse(McpToolParamReader.shouldEscalateToRealWrite(
                Map.of("dryRun", true), "just preview"));
    }

    @Test
    void containerAndDiskOpsWriteConfirm() {
        Map<String, Object> docker = new HashMap<>(Map.of("operation", "restart"));
        McpToolParamReader.forceConfirmedWriteToolParams("DockerTool", docker);
        assertEquals(false, docker.get("dryRun"));
        assertEquals(true, docker.get("confirmRestart"));

        Map<String, Object> diskOps = new HashMap<>(Map.of("operation", "clean-temp", "path", "/tmp"));
        McpToolParamReader.forceConfirmedWriteToolParams("DiskOpsTool", diskOps);
        assertEquals(false, diskOps.get("dryRun"));
        assertEquals(true, diskOps.get("confirmDelete"));
    }
}
