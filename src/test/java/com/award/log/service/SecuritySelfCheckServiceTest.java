package com.award.log.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全栈自检（需 MariaDB：设置环境变量 {@code DB_PASSWORD} 后运行）。
 * 日常 CI 以 {@link com.award.log.security.McpInvocationSecurityGateProbeTest} 为准。
 */
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-security-self-check-only",
        "spring.datasource.password=${DB_PASSWORD:}"
})
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class SecuritySelfCheckServiceTest {

    @Autowired
    private SecuritySelfCheckService securitySelfCheckService;

    /** 避免 @PostConstruct 建表探针在无 DB 密码/无 MariaDB 时拖垮整包 SpringBootTest */
    @MockBean
    private OpsAuditTraceService opsAuditTraceService;

    @Test
    void allProbesShouldPass() {
        Map<String, Object> report = securitySelfCheckService.run();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        int failed = ((Number) summary.get("failed")).intValue();
        if (failed > 0) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> probes = (List<Map<String, Object>>) report.get("probes");
            StringBuilder sb = new StringBuilder("failed probes:\n");
            for (Map<String, Object> p : probes) {
                if (!Boolean.TRUE.equals(p.get("passed"))) {
                    sb.append(p.get("id")).append(" expect=").append(p.get("expect"))
                            .append(" actual=").append(p.get("actual")).append('\n');
                }
            }
            throw new AssertionError(sb.toString());
        }
        assertEquals("PASS", report.get("overallStatus"));
        assertTrue(((Number) summary.get("total")).intValue() >= 8);
    }
}
