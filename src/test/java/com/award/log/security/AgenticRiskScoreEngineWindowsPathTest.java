package com.award.log.security;

import com.award.log.mcp.McpToolCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgenticRiskScoreEngineWindowsPathTest {

    private AgenticRiskScoreEngine engine;

    @BeforeEach
    void setUp() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeanDefinitionNames()).thenReturn(new String[0]);
        McpToolCatalog catalog = new McpToolCatalog(ctx, false);
        ReflectionTestUtils.invokeMethod(catalog, "discover");
        engine = new AgenticRiskScoreEngine(catalog, 5, 9.5);
    }

    @Test
    void windowsTempAndLogsShouldNotHitHardBlockScore() {
        double temp = engine.score("CleanTempTool",
                Map.of("path", "C:/Windows/Temp", "dryRun", true),
                "预览清理临时文件").total();
        double logs = engine.score("LogCleanupTool",
                Map.of("path", "C:/Windows/Logs", "dryRun", true),
                "预览清理旧日志").total();
        double userTemp = engine.score("CleanTempTool",
                Map.of("path", "C:/Users/Administrator/AppData/Local/Temp", "dryRun", true),
                "预览清理临时文件").total();

        assertTrue(temp <= 9.5, "Windows/Temp score=" + temp);
        assertTrue(logs <= 9.5, "Windows/Logs score=" + logs);
        assertTrue(userTemp <= 9.5, "User Temp score=" + userTemp);
    }

    @Test
    void windowsSystem32ConfigStillHardBlocked() {
        double score = engine.score("CleanTempTool",
                Map.of("path", "C:/Windows/System32/config", "dryRun", false),
                "清理注册表配置").total();
        assertTrue(score > 9.5, "System32/config score=" + score);
    }
}
