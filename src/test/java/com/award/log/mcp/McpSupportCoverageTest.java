package com.award.log.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpSupportCoverageTest {

    @Test
    void writeToolResultSupportExtractsModes() {
        assertEquals("", WriteToolResultSupport.extractMode(null));
        assertEquals("ERROR", WriteToolResultSupport.extractMode("{\"success\":false}"));
        assertEquals("PREVIEW", WriteToolResultSupport.extractMode(
                "{\"success\":true,\"data\":{\"mode\":\"PREVIEW\"}}"));
        assertEquals("DELETE", WriteToolResultSupport.extractMode(
                "{\"success\":true,\"data\":\"{\\\"mode\\\":\\\"DELETE\\\"}\"}"));
        assertEquals("EXECUTED", WriteToolResultSupport.extractMode(
                "{\"success\":true,\"data\":{\"mode\":\"EXECUTED\"}}"));
    }

    @Test
    void writeToolResultSupportDetectsRealWriteIntent() {
        assertFalse(WriteToolResultSupport.isRealWriteMode("PREVIEW"));
        assertTrue(WriteToolResultSupport.isRealWriteMode("DELETE"));
        assertTrue(WriteToolResultSupport.isRealWriteMode("executed"));

        assertTrue(WriteToolResultSupport.requestedRealWrite(Map.of("dryRun", false)));
        assertTrue(WriteToolResultSupport.requestedRealWrite(Map.of("confirmDelete", true)));
        assertFalse(WriteToolResultSupport.requestedRealWrite(Map.of("dryRun", true)));

        assertTrue(WriteToolResultSupport.mismatchWarning("CleanTempTool", "PREVIEW")
                .contains("CleanTempTool"));
    }
}
