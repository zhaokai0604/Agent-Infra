package com.award.log.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.award.log.security.OpsPathPolicy;
import com.award.log.config.AgentOpsProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanTempToolBehaviorTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void deleteShouldReachDeepTempFiles(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        ObjectMapper mapper = McpToolsTestFixtures.objectMapper();

        Path deep = tempDir.resolve("a/b/c/d/e");
        Files.createDirectories(deep);
        Path file = deep.resolve("old-cache.bin");
        Files.writeString(file, "cleanup-me");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        String raw = tool.cleanTempFiles(tempDir.toString(), 0, false, true, false);
        JsonNode root = mapper.readTree(raw);
        JsonNode data = mapper.readTree(root.path("data").asText());

        assertTrue(root.path("success").asBoolean(false));
        assertEquals("SUCCESS", root.path("status").asText());
        assertEquals("DELETE", data.path("mode").asText());
        assertEquals(1, data.path("filesDeleted").asInt());
        assertTrue(data.path("bytesFreed").asLong(0) > 0);
        assertEquals("EFFECTIVE", data.path("businessEffect").asText());
        assertFalse(Files.exists(file));
    }

    @Test
    void realDeleteZeroShouldBeWarn(@TempDir Path tempDir) throws Exception {
        CleanTempTool tool = toolForRoot(tempDir);
        ObjectMapper mapper = McpToolsTestFixtures.objectMapper();

        String raw = tool.cleanTempFiles(tempDir.toString(), 0, false, true, false);
        JsonNode root = mapper.readTree(raw);
        JsonNode data = mapper.readTree(root.path("data").asText());

        assertTrue(root.path("success").asBoolean(false));
        assertEquals("WARN", root.path("status").asText());
        assertEquals(0, data.path("filesDeleted").asInt());
        assertEquals("NO_EFFECT", data.path("businessEffect").asText());
    }

    private CleanTempTool toolForRoot(Path root) {
        OpsPathPolicy policy = McpToolsTestFixtures.opsPathPolicy();
        String rootPath = root.toString();
        if (com.award.log.util.OsRuntime.isWindows()) {
            policy.updateEditablePathLists(true, java.util.List.of(rootPath), java.util.List.of(rootPath), java.util.List.of());
        } else {
            policy.updateEditablePathLists(false, java.util.List.of(rootPath), java.util.List.of(rootPath), java.util.List.of());
        }
        ReflectionTestUtils.setField(policy, "policyVersion", "test-clean-root");
        return new CleanTempTool(executor, McpToolsTestFixtures.objectMapper(),
                McpToolsTestFixtures.minPrivilegeExecutor(executor), policy, new com.award.log.config.OpsRemediationProperties());
    }
}
