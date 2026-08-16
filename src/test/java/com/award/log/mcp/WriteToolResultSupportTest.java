package com.award.log.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteToolResultSupportTest {

    @Test
    void extractModeFromNestedToolResult() {
        String json = """
                {"success":true,"data":"{\\"mode\\":\\"DELETE\\",\\"path\\":\\"/tmp/x\\"}"}
                """;
        assertEquals("DELETE", WriteToolResultSupport.extractMode(json));
    }

    @Test
    void isRealWriteModeRecognizesDeleteAndExecuted() {
        assertTrue(WriteToolResultSupport.isRealWriteMode("DELETE"));
        assertTrue(WriteToolResultSupport.isRealWriteMode("EXECUTED"));
        assertFalse(WriteToolResultSupport.isRealWriteMode("DRY-RUN"));
    }

    @Test
    void requestedRealWriteFromDryRunFalse() {
        assertTrue(WriteToolResultSupport.requestedRealWrite(Map.of("dryRun", false)));
    }

    @Test
    void confirmedRealWriteAcceptsEvidenceBasedShapes() {
        assertTrue(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"mode":"DELETE","filesDeleted":2}}
                """));
        assertTrue(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"mode":"EXECUTED","service":"nginx","success":true}}
                """));
        assertTrue(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"filesDeleted":2}}
                """));
        assertTrue(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"service":"nginx","success":true}}
                """));
        // 空成功信封 / 无证据 → 不认
        assertFalse(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true}
                """));
        assertFalse(WriteToolResultSupport.isConfirmedRealWrite("""
                {"clean":true}
                """));
        assertFalse(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"mode":"DELETE","filesDeleted":0}}
                """));
    }

    @Test
    void confirmedRealWriteRejectsPreviewLikePayloadWithoutMode() {
        assertFalse(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"filesFound":3}}
                """));
        assertFalse(WriteToolResultSupport.isConfirmedRealWrite("""
                {"success":true,"data":{"plan":"would restart nginx","service":"nginx"}}
                """));
    }
}
