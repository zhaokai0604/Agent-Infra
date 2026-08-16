package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.config.SystemBootstrapSupport;
import com.award.log.decision.RandomForestDecisionEngine;
import com.award.log.governance.OpsGovernanceService;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.award.log.platform.KylinCommandProbe;
import javax.sql.DataSource;
import java.sql.Connection;
import com.award.log.mcp.McpToolCatalog;
import com.award.log.security.effect.PolicyReplayService;
import com.award.log.util.OsRuntime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台与运行环境快照（不含密钥明文）。
 */
@Tag(name = "平台信息")
@RestController
@RequestMapping("/api/platform")
public class PlatformInfoController {

    private final Environment environment;
    private final RandomForestDecisionEngine randomForestDecisionEngine;
    private final MinPrivilegeExecutor minPrivilegeExecutor;
    private final OpsGovernanceService opsGovernanceService;
    private final OpsDryRunProperties opsDryRunProperties;
    private final DataSource dataSource;
    private final McpToolCatalog mcpToolCatalog;

    @Autowired(required = false)
    private PolicyReplayService policyReplayService;

    @Autowired(required = false)
    private ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String aiApiKey;

    @Autowired(required = false)
    private KylinCommandProbe kylinCommandProbe;

    @Value("${knowledge.enabled:true}")
    private boolean knowledgeEnabled;

    @Value("${award.middleware.redis:false}")
    private boolean redisEnabled;

    @Value("${award.middleware.kafka:false}")
    private boolean kafkaEnabled;

    @Value("${spring.elasticsearch.enabled:false}")
    private boolean elasticsearchEnabled;

    @Value("${platform.kylin.expected-arch:loongarch64}")
    private String expectedArch;

    public PlatformInfoController(
            Environment environment,
            RandomForestDecisionEngine randomForestDecisionEngine,
            MinPrivilegeExecutor minPrivilegeExecutor,
            OpsGovernanceService opsGovernanceService,
            OpsDryRunProperties opsDryRunProperties,
            DataSource dataSource,
            McpToolCatalog mcpToolCatalog) {
        this.environment = environment;
        this.randomForestDecisionEngine = randomForestDecisionEngine;
        this.minPrivilegeExecutor = minPrivilegeExecutor;
        this.opsGovernanceService = opsGovernanceService;
        this.opsDryRunProperties = opsDryRunProperties;
        this.dataSource = dataSource;
        this.mcpToolCatalog = mcpToolCatalog;
    }

    @Operation(summary = "平台与生产就绪快照（需登录；含治理与最小权限详情）")
    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> body = buildAcceptanceBody();

        String[] active = environment.getActiveProfiles();
        body.put("activeProfiles", active.length == 0 ? new String[]{"default"} : active);
        body.put("prodProfileActive", Arrays.stream(active).anyMatch(p -> p.contains("prod")));
        body.put("kylinProfileActive", Arrays.stream(active).anyMatch(p -> "kylin".equals(p)));

        body.put("randomForest", randomForestDecisionEngine.healthSnapshot());

        if (kylinCommandProbe != null) {
            body.put("kylinCommandProbe", kylinCommandProbe.getLastProbeResult());
        }

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("minPrivilegeEnabled", minPrivilegeExecutor.isMinPrivilegeEnabled());
        security.put("runAsUser", minPrivilegeExecutor.getRunAsUser());
        security.put("aiAuditRelaxedRead", environment.getProperty("app.security.ai-audit-relaxed-read"));
        security.put("autoRemediationEnabled", environment.getProperty("ops.auto-remediation.enabled"));
        security.put("autonomousOpsEnabled", environment.getProperty("agent.autonomous.enabled"));
        security.put("globalDryRun", opsDryRunProperties.isGlobalDryRun());
        security.put("aiConfigured", chatClient != null && aiApiKey != null && !aiApiKey.isBlank());
        security.put("effectEngine", "ToolEffect+CapabilityToken+Evidence+Budget+PlanGate+PolicyReplay");
        security.put("governanceHardCover", true);
        security.put("policyReplayApi", "/api/security/policy-replay");
        security.put("securityCockpitTab", "security-cockpit");
        if (policyReplayService != null) {
            security.put("policySnapshot", policyReplayService.currentPolicySnapshot());
        }
        body.put("security", security);

        body.put("governance", opsGovernanceService.summaryForPlatform());
        body.put("runtime", buildRuntimeSnapshot());
        body.put("components", buildComponentSnapshot());
        body.put("bootstrap", SystemBootstrapSupport.readBootstrapStatus());
        body.put("capabilities", SystemBootstrapSupport.buildCapabilitySnapshot());
        body.put("platformSupport", buildPlatformSupportSummary());

        return Result.success(body);
    }

    @Operation(summary = "麒麟 V11 + LoongArch 公开验收快照（无需登录，不含运行用户与自动化开关）")
    @GetMapping("/acceptance")
    public Result<Map<String, Object>> acceptance() {
        return Result.success(buildAcceptanceBody());
    }

    private Map<String, Object> buildAcceptanceBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", OsRuntime.platformSummary());

        Map<String, Object> acceptance = new LinkedHashMap<>();
        acceptance.put("expectedArch", expectedArch);
        acceptance.put("archMatch", OsRuntime.osArch().toLowerCase().contains(expectedArch.toLowerCase())
                || (OsRuntime.isLoongArch() && expectedArch.toLowerCase().contains("loong")));
        acceptance.put("kylinDetected", OsRuntime.isKylin());
        acceptance.put("loongArchDetected", OsRuntime.isLoongArch());
        acceptance.put("deliveryTargetMatch", OsRuntime.isKylin() && OsRuntime.isLoongArch());
        body.put("acceptance", acceptance);
        return body;
    }

    private Map<String, Object> buildRuntimeSnapshot() {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("dbReachable", probeDatabase());
        runtime.put("mcpRegisteredCount", mcpToolCatalog.getDescriptors().size());
        runtime.put("mcpHttpAllowedCount", mcpToolCatalog.getHttpAllowedToolNames().size());
        runtime.put("dataSource", "live");
        return runtime;
    }

    private boolean probeDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> buildComponentSnapshot() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("ai", Map.of(
                "enabled", chatClient != null && aiApiKey != null && !aiApiKey.isBlank(),
                "hint", chatClient != null && aiApiKey != null && !aiApiKey.isBlank()
                        ? "LLM 对话可用" : "未配置 AI_API_KEY，编排 MCP 仍可用"));
        components.put("elasticsearch", Map.of(
                "enabled", elasticsearchEnabled,
                "hint", elasticsearchEnabled ? "已启用" : "默认关闭，Chat 工具回退 MySQL 明细"));
        components.put("redis", Map.of(
                "enabled", redisEnabled,
                "hint", redisEnabled ? "已启用" : "默认关闭，Statistics 使用进程内缓存"));
        components.put("kafka", Map.of(
                "enabled", kafkaEnabled,
                "hint", kafkaEnabled ? "已启用" : "默认关闭"));
        components.put("knowledge", Map.of(
                "enabled", knowledgeEnabled,
                "hint", knowledgeEnabled ? "需 Qdrant 才能语义检索" : "已关闭"));
        return components;
    }

    private Map<String, Object> buildPlatformSupportSummary() {
        Map<String, Object> support = new LinkedHashMap<>();
        boolean windows = OsRuntime.isWindows();
        Map<String, Object> capabilities = SystemBootstrapSupport.buildCapabilitySnapshot();
        support.put("platformKey", windows ? "windows" : "linux");
        support.put("currentPlatform", System.getProperty("os.name"));
        support.put("capabilities", capabilities);
        support.put("commandMismatchRisk",
                Boolean.TRUE.equals(capabilities.get("windows"))
                        ? Boolean.FALSE.equals(capabilities.get("powershell"))
                        : Boolean.FALSE.equals(capabilities.get("systemctl")));
        return support;
    }
}
