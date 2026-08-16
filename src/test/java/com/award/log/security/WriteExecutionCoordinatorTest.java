package com.award.log.security;

import com.award.log.mcp.WriteToolResultSupport;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteExecutionCoordinatorTest {

    @Test
    void attachWriteMismatchWhenDryRunDespiteConfirmedParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("dryRun", false);
        params.put("confirmDelete", true);

        Map<String, Object> response = new HashMap<>();
        String toolJson = """
                {"success":true,"data":"{\\"mode\\":\\"DRY-RUN\\",\\"path\\":\\"/tmp/x\\"}"}
                """;

        WriteExecutionCoordinator.attachWriteMismatchIfNeeded(
                "CleanTempTool", params, toolJson, response);

        assertTrue((Boolean) response.get("writeMismatch"));
        assertEquals(
                WriteToolResultSupport.mismatchWarning("CleanTempTool", "DRY-RUN"),
                response.get("writeMismatchMessage"));
    }
}
