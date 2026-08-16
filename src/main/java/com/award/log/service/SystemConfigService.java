package com.award.log.service;

import com.award.log.agent.AutonomousOpsOrchestrator;
import com.award.log.config.AgentOpsProperties;
import com.award.log.config.SystemBootstrapSupport;
import com.award.log.config.OpsDryRunProperties;
import com.award.log.config.SystemConfigFileSupport;
import com.award.log.config.SystemConfigRuntimeState;
import com.award.log.service.impl.AiAuditLogService;
import com.award.log.util.OsRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 统一系统配置服务：聚合默认配置、旧白名单覆盖、新覆盖文件和 secrets 文件。
 */
@Service
public class SystemConfigService {

    private final Environment environment;
    private final SystemConfigRuntimeState runtimeState;
    private final AgentPathPolicyService agentPathPolicyService;
    private final AgentOpsProperties agentOpsProperties;

    @Autowired(required = false)
    private OpsPatrolService opsPatrolService;

    @Autowired(required = false)
    private OpsAutoRemediationService opsAutoRemediationService;

    @Autowired(required = false)
    private AutonomousOpsOrchestrator autonomousOpsOrchestrator;

    @Autowired(required = false)
    private OpsDryRunProperties opsDryRunProperties;

    @Autowired(required = false)
    private AiAuditLogService aiAuditLogService;

    public SystemConfigService(Environment environment,
                               SystemConfigRuntimeState runtimeState,
                               AgentPathPolicyService agentPathPolicyService,
                               AgentOpsProperties agentOpsProperties) {
        this.environment = environment;
        this.runtimeState = runtimeState;
        this.agentPathPolicyService = agentPathPolicyService;
        this.agentOpsProperties = agentOpsProperties;
    }

    public Map<String, Object> getEffectiveConfig() {
        Map<String, Object> overrideRoot = SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile());
        Map<String, Object> secretRoot = SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.secretFile());

        Map<String, Object> runtimeSaved = SystemConfigFileSupport.nestedMap(overrideRoot.get("runtime"));
        Map<String, Object> collectorSaved = SystemConfigFileSupport.nestedMap(overrideRoot.get("collector"));
        Map<String, Object> aiSaved = SystemConfigFileSupport.nestedMap(overrideRoot.get("ai"));

        List<Map<String, Object>> restartFlags = new ArrayList<>();
        LinkedHashMap<String, Object> groups = new LinkedHashMap<>();
        groups.put("runtime", group("runtime", "运行策略", buildRuntimeItems()));
        groups.put("collector", group("collector", "日志采集", buildCollectorItems(collectorSaved, restartFlags)));
        groups.put("ai", group("ai", "AI 接入", buildAiItems(aiSaved, secretRoot, restartFlags)));
        groups.put("pathPolicy", group("pathPolicy", "安全白名单", buildPathPolicyItems()));

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", buildPlatform());
        payload.put("groups", groups);
        payload.put("files", buildFiles());
        payload.put("bootstrap", SystemBootstrapSupport.readBootstrapStatus());
        payload.put("needsRestart", restartFlags);
        payload.put("savedAt", SystemConfigFileSupport.latestSavedAt(
                SystemConfigFileSupport.overrideFile(),
                SystemConfigFileSupport.secretFile(),
                SystemConfigFileSupport.pathPolicyFile()));
        payload.put("messages", buildMessages(runtimeSaved, collectorSaved, aiSaved, secretRoot, restartFlags));
        return payload;
    }

    public Map<String, Object> saveEffectiveConfig(Map<String, Object> body) {
        Map<String, Object> overrideRoot = new LinkedHashMap<>(SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile()));
        Map<String, Object> secretRoot = new LinkedHashMap<>(SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.secretFile()));
        String savedAt = Instant.now().toString();

        saveRuntimeGroup(overrideRoot, SystemConfigFileSupport.nestedMap(body.get("runtime")));
        saveCollectorGroup(overrideRoot, SystemConfigFileSupport.nestedMap(body.get("collector")));
        saveAiGroup(overrideRoot, SystemConfigFileSupport.nestedMap(body.get("ai")));
        savePathPolicyGroup(overrideRoot, SystemConfigFileSupport.nestedMap(body.get("pathPolicy")));
        saveSecretOpsGroup(secretRoot, SystemConfigFileSupport.nestedMap(body.get("secretOps")));

        overrideRoot.put("savedAt", savedAt);
        secretRoot.put("savedAt", savedAt);
        SystemConfigFileSupport.writeJsonFile(SystemConfigFileSupport.overrideFile(), overrideRoot);
        if (!SystemConfigFileSupport.nestedMap(secretRoot.get("secretOps")).isEmpty()) {
            SystemConfigFileSupport.writeJsonFile(SystemConfigFileSupport.secretFile(), secretRoot);
        }
        Map<String, Object> result = getEffectiveConfig();
        @SuppressWarnings("unchecked")
        List<String> messages = new ArrayList<>((List<String>) result.getOrDefault("messages", List.of()));
        messages.add(0, "系统配置已保存，热生效项已即时更新，重启项已写入本地覆盖文件。");
        result.put("messages", messages);
        result.put("savedAt", savedAt);
        return result;
    }

    public void reapplySavedRuntimeConfig() {
        Map<String, Object> overrideRoot = SystemConfigFileSupport.readJsonFile(SystemConfigFileSupport.overrideFile());
        Map<String, Object> runtimeBody = SystemConfigFileSupport.nestedMap(overrideRoot.get("runtime"));
        if (runtimeBody.isEmpty()) {
            return;
        }
        saveRuntimeGroup(overrideRoot, runtimeBody);
    }

    private List<Map<String, Object>> buildRuntimeItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("patrolInspectRoots", "巡检根目录", "HOT", runtimeState.getPatrolInspectRoots(), "list", null, false, null));
        items.add(item("healthCheckPorts", "健康检查端口", "HOT", runtimeState.getHealthCheckPorts(), "list", null, false, null));
        items.add(item("pingTarget", "网络诊断默认目标", "HOT", runtimeState.getPingTarget(), "text", null, false, null));
        items.add(item("autoRemediationEnabled", "自动修复开关", "HOT", runtimeState.isAutoRemediationEnabled(), "boolean", null, false, null));
        items.add(item("autoRemediationMode", "自动修复模式", "HOT", runtimeState.getAutoRemediationMode(), "select", null, false,
                List.of("HYBRID", "CONFIRM_FIRST", "IMMEDIATE")));
        items.add(item("dryRunGlobal", "全局 Dry Run", "HOT", runtimeState.isDryRunGlobal(), "boolean", null, false, null));
        items.add(item("patrolDiskWarnPercent", "巡检磁盘阈值", "HOT", runtimeState.getPatrolDiskWarnPercent(), "number", null, false, null));
        items.add(item("patrolCpuWarnPercent", "巡检 CPU 阈值", "HOT", runtimeState.getPatrolCpuWarnPercent(), "number", null, false, null));
        items.add(item("anomalySpikeFactor", "异常突增倍率", "HOT", runtimeState.getAnomalySpikeFactor(), "number", null, false, null));
        items.add(item("errorAlarmMin", "自动修复告警触发阈值", "HOT", runtimeState.getErrorAlarmMin(), "number", null, false, null));
        items.add(item("autoRiskPatrolAutoMax", "自动修复风险阈值", "HOT", runtimeState.getAutoRiskPatrolAutoMax(), "number", null, false, null));
        items.add(item("autoProposeTempCleanDiskMin", "临时目录建议阈值", "HOT", runtimeState.getAutoProposeTempCleanDiskMin(), "number", null, false, null));
        items.add(item("autoProposeLogCleanDiskMin", "日志清理建议阈值", "HOT", runtimeState.getAutoProposeLogCleanDiskMin(), "number", null, false, null));
        return items;
    }

    private List<Map<String, Object>> buildCollectorItems(Map<String, Object> collectorSaved,
                                                          List<Map<String, Object>> restartFlags) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(restartItem("fileRoot", "采集根目录", "text", "log.collector.file.path", collectorSaved.get("fileRoot"), restartFlags));
        items.add(restartItem("includeExtensions", "采集扩展名", "text", "log.collector.file.include-extensions", collectorSaved.get("includeExtensions"), restartFlags));
        items.add(restartItem("excludeDirectories", "排除目录", "text", "log.collector.file.exclude-directories", collectorSaved.get("excludeDirectories"), restartFlags));
        items.add(restartItem("networkEnabled", "网络采集开关", "boolean", "log.collector.network.enabled", collectorSaved.get("networkEnabled"), restartFlags));
        items.add(restartItem("networkPort", "网络采集端口", "number", "log.collector.network.port", collectorSaved.get("networkPort"), restartFlags));
        items.add(restartItem("networkProtocol", "网络采集协议", "select", "log.collector.network.protocol", collectorSaved.get("networkProtocol"), restartFlags,
                List.of("UDP", "TCP")));
        items.add(restartItem("dbEnabled", "数据库采集开关", "boolean", "log.collector.db.enabled", collectorSaved.get("dbEnabled"), restartFlags));
        items.add(restartItem("dbQuery", "数据库采集 SQL", "textarea", "log.collector.db.query", collectorSaved.get("dbQuery"), restartFlags));
        return items;
    }

    private List<Map<String, Object>> buildAiItems(Map<String, Object> aiSaved,
                                                   Map<String, Object> secretRoot,
                                                   List<Map<String, Object>> restartFlags) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(restartItem("baseUrl", "AI Base URL", "text", "spring.ai.openai.base-url", aiSaved.get("baseUrl"), restartFlags));
        items.add(restartItem("chatModel", "聊天模型", "text", "spring.ai.openai.chat.options.model", aiSaved.get("chatModel"), restartFlags));
        items.add(restartItem("embeddingModel", "Embedding 模型", "text", "spring.ai.openai.embedding.options.model", aiSaved.get("embeddingModel"), restartFlags));

        String appSecret = environment.getProperty("APP_CONFIG_SECRET", "");
        String currentApiKey = environment.getProperty("spring.ai.openai.api-key", "");
        Map<String, Object> secretOps = SystemConfigFileSupport.nestedMap(secretRoot.get("secretOps"));
        String encrypted = secretOps.get("aiApiKey") instanceof String value ? value : "";
        boolean configured = currentApiKey != null && !currentApiKey.isBlank();
        boolean savedConfigured = encrypted != null && !encrypted.isBlank();
        boolean pendingRestart = false;
        if (savedConfigured && !appSecret.isBlank()) {
            try {
                String savedPlain = SystemConfigFileSupport.decryptSecret(encrypted, appSecret);
                pendingRestart = !Objects.equals(savedPlain, currentApiKey);
            } catch (Exception ignored) {
                pendingRestart = !configured;
            }
        }
        Map<String, Object> apiKeyItem = item(
                "aiApiKey",
                "AI API Key",
                "RESTART",
                configured ? "已配置" : "未配置",
                "secret",
                null,
                true,
                null);
        apiKeyItem.put("configured", configured);
        apiKeyItem.put("pendingConfigured", savedConfigured);
        apiKeyItem.put("pendingRestart", pendingRestart);
        if (pendingRestart) {
            restartFlags.add(restartFlag("ai", "aiApiKey", "AI API Key", "已保存新密钥，需重启后生效"));
        }
        items.add(apiKeyItem);
        return items;
    }

    private List<Map<String, Object>> buildPathPolicyItems() {
        Map<String, Object> effective = agentPathPolicyService.getEffectivePolicyView();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("readPrefixes", "可读/扫描白名单", "HOT", effective.get("readPrefixes"), "list", null, false, null));
        items.add(item("cleanRoots", "临时目录清理白名单", "HOT", effective.get("cleanRoots"), "list", null, false, null));
        items.add(item("logCleanupRoots", "日志清理白名单", "HOT", effective.get("logCleanupRoots"), "list", null, false, null));
        items.add(item("serviceRestartAllowlist", "服务重启白名单", "HOT", effective.get("serviceRestartAllowlist"), "list", null, false, null));
        items.add(item("deniedSubstrings", "禁止路径片段", "HOT", effective.get("deniedSubstrings"), "readonly-list", null, false, null));
        return items;
    }

    private Map<String, Object> buildPlatform() {
        LinkedHashMap<String, Object> platform = new LinkedHashMap<>();
        platform.put("summary", OsRuntime.platformSummary());
        platform.put("osName", System.getProperty("os.name"));
        platform.put("osArch", System.getProperty("os.arch"));
        platform.put("activeProfiles", environment.getActiveProfiles());
        platform.put("roleHint", "role == 1 为管理员");
        return platform;
    }

    private List<Map<String, Object>> buildFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        files.add(fileInfo("override", SystemConfigFileSupport.overrideFile()));
        files.add(fileInfo("secret", SystemConfigFileSupport.secretFile()));
        files.add(fileInfo("pathPolicyCompat", SystemConfigFileSupport.pathPolicyFile()));
        return files;
    }

    private List<String> buildMessages(Map<String, Object> runtimeSaved,
                                       Map<String, Object> collectorSaved,
                                       Map<String, Object> aiSaved,
                                       Map<String, Object> secretRoot,
                                       List<Map<String, Object>> restartFlags) {
        List<String> messages = new ArrayList<>();
        messages.add("系统配置页所有登录用户可查看，只有管理员可以保存。");
        messages.add("运行期可切换项保存后立即热生效；日志采集和 AI 接入项会保存到本地覆盖文件，需重启后生效。");
        if (environment.getProperty("APP_CONFIG_SECRET", "").isBlank()) {
            messages.add("未设置 APP_CONFIG_SECRET，当前只能查看 AI Key 状态，无法通过前端保存新的敏感配置。");
        }
        if (restartFlags.isEmpty() && (!collectorSaved.isEmpty() || !aiSaved.isEmpty() || !SystemConfigFileSupport.nestedMap(secretRoot.get("secretOps")).isEmpty())) {
            messages.add("重启项已与当前运行配置一致。");
        }
        if (!runtimeSaved.isEmpty()) {
            messages.add("运行策略已有本地覆盖配置，应用重启后仍会保持当前保存值。");
        }
        return messages;
    }

    private void saveRuntimeGroup(Map<String, Object> overrideRoot, Map<String, Object> runtimeBody) {
        if (runtimeBody.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> runtime = new LinkedHashMap<>(SystemConfigFileSupport.nestedMap(overrideRoot.get("runtime")));

        List<String> patrolInspectRoots = normalizeStringList(
                runtimeBody.containsKey("patrolInspectRoots") ? runtimeBody.get("patrolInspectRoots") : runtimeState.getPatrolInspectRoots(),
                true,
                "巡检根目录");
        List<Integer> healthCheckPorts = normalizePortList(
                runtimeBody.containsKey("healthCheckPorts") ? runtimeBody.get("healthCheckPorts") : runtimeState.getHealthCheckPorts());
        String pingTarget = normalizeHost(runtimeBody.containsKey("pingTarget") ? runtimeBody.get("pingTarget") : runtimeState.getPingTarget(), "网络诊断目标");
        boolean autoRemediationEnabled = normalizeBoolean(runtimeBody.containsKey("autoRemediationEnabled") ? runtimeBody.get("autoRemediationEnabled") : runtimeState.isAutoRemediationEnabled());
        String autoRemediationMode = normalizeMode(runtimeBody.containsKey("autoRemediationMode") ? runtimeBody.get("autoRemediationMode") : runtimeState.getAutoRemediationMode());
        boolean dryRunGlobal = normalizeBoolean(runtimeBody.containsKey("dryRunGlobal") ? runtimeBody.get("dryRunGlobal") : runtimeState.isDryRunGlobal());
        double patrolDiskWarnPercent = normalizePercent(runtimeBody.containsKey("patrolDiskWarnPercent") ? runtimeBody.get("patrolDiskWarnPercent") : runtimeState.getPatrolDiskWarnPercent(), "巡检磁盘阈值");
        double patrolCpuWarnPercent = normalizePercent(runtimeBody.containsKey("patrolCpuWarnPercent") ? runtimeBody.get("patrolCpuWarnPercent") : runtimeState.getPatrolCpuWarnPercent(), "巡检 CPU 阈值");
        double anomalySpikeFactor = normalizePositiveDouble(runtimeBody.containsKey("anomalySpikeFactor") ? runtimeBody.get("anomalySpikeFactor") : runtimeState.getAnomalySpikeFactor(), "异常突增倍率");
        int errorAlarmMin = normalizeIntRange(runtimeBody.containsKey("errorAlarmMin") ? runtimeBody.get("errorAlarmMin") : runtimeState.getErrorAlarmMin(), 1, 999, "自动修复告警阈值");
        double autoRiskPatrolAutoMax = normalizePositiveDouble(runtimeBody.containsKey("autoRiskPatrolAutoMax") ? runtimeBody.get("autoRiskPatrolAutoMax") : runtimeState.getAutoRiskPatrolAutoMax(), "自动修复风险阈值");
        double autoProposeTempCleanDiskMin = normalizePercent(runtimeBody.containsKey("autoProposeTempCleanDiskMin") ? runtimeBody.get("autoProposeTempCleanDiskMin") : runtimeState.getAutoProposeTempCleanDiskMin(), "临时目录建议阈值");
        double autoProposeLogCleanDiskMin = normalizePercent(runtimeBody.containsKey("autoProposeLogCleanDiskMin") ? runtimeBody.get("autoProposeLogCleanDiskMin") : runtimeState.getAutoProposeLogCleanDiskMin(), "日志清理建议阈值");

        runtimeState.setPatrolInspectRoots(patrolInspectRoots);
        runtimeState.setHealthCheckPorts(healthCheckPorts);
        runtimeState.setPingTarget(pingTarget);
        runtimeState.setAutoRemediationEnabled(autoRemediationEnabled);
        runtimeState.setAutoRemediationMode(autoRemediationMode);
        runtimeState.setDryRunGlobal(dryRunGlobal);
        runtimeState.setPatrolDiskWarnPercent(patrolDiskWarnPercent);
        runtimeState.setPatrolCpuWarnPercent(patrolCpuWarnPercent);
        runtimeState.setAnomalySpikeFactor(anomalySpikeFactor);
        runtimeState.setErrorAlarmMin(errorAlarmMin);
        runtimeState.setAutoRiskPatrolAutoMax(autoRiskPatrolAutoMax);
        runtimeState.setAutoProposeTempCleanDiskMin(autoProposeTempCleanDiskMin);
        runtimeState.setAutoProposeLogCleanDiskMin(autoProposeLogCleanDiskMin);
        applyRuntimeStateToTargets();

        runtime.put("patrolInspectRoots", patrolInspectRoots);
        runtime.put("healthCheckPorts", healthCheckPorts);
        runtime.put("pingTarget", pingTarget);
        runtime.put("autoRemediationEnabled", autoRemediationEnabled);
        runtime.put("autoRemediationMode", autoRemediationMode);
        runtime.put("dryRunGlobal", dryRunGlobal);
        runtime.put("patrolDiskWarnPercent", patrolDiskWarnPercent);
        runtime.put("patrolCpuWarnPercent", patrolCpuWarnPercent);
        runtime.put("anomalySpikeFactor", anomalySpikeFactor);
        runtime.put("errorAlarmMin", errorAlarmMin);
        runtime.put("autoRiskPatrolAutoMax", autoRiskPatrolAutoMax);
        runtime.put("autoProposeTempCleanDiskMin", autoProposeTempCleanDiskMin);
        runtime.put("autoProposeLogCleanDiskMin", autoProposeLogCleanDiskMin);
        overrideRoot.put("runtime", runtime);
    }

    private void saveCollectorGroup(Map<String, Object> overrideRoot, Map<String, Object> collectorBody) {
        if (collectorBody.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> collector = new LinkedHashMap<>(SystemConfigFileSupport.nestedMap(overrideRoot.get("collector")));
        collector.put("fileRoot", normalizeText(collectorBody.containsKey("fileRoot") ? collectorBody.get("fileRoot") : collector.getOrDefault("fileRoot", environment.getProperty("log.collector.file.path", "")), true, "采集根目录"));
        collector.put("includeExtensions", normalizeText(collectorBody.containsKey("includeExtensions") ? collectorBody.get("includeExtensions") : collector.getOrDefault("includeExtensions", environment.getProperty("log.collector.file.include-extensions", "")), true, "采集扩展名"));
        collector.put("excludeDirectories", normalizeText(collectorBody.containsKey("excludeDirectories") ? collectorBody.get("excludeDirectories") : collector.getOrDefault("excludeDirectories", environment.getProperty("log.collector.file.exclude-directories", "")), true, "排除目录"));
        collector.put("networkEnabled", normalizeBoolean(collectorBody.containsKey("networkEnabled") ? collectorBody.get("networkEnabled") : collector.getOrDefault("networkEnabled", environment.getProperty("log.collector.network.enabled", "false"))));
        collector.put("networkPort", normalizeIntRange(collectorBody.containsKey("networkPort") ? collectorBody.get("networkPort") : collector.getOrDefault("networkPort", environment.getProperty("log.collector.network.port", "514")), 1, 65535, "网络采集端口"));
        collector.put("networkProtocol", normalizeEnum(collectorBody.containsKey("networkProtocol") ? collectorBody.get("networkProtocol") : collector.getOrDefault("networkProtocol", environment.getProperty("log.collector.network.protocol", "UDP")), List.of("UDP", "TCP"), "网络采集协议"));
        collector.put("dbEnabled", normalizeBoolean(collectorBody.containsKey("dbEnabled") ? collectorBody.get("dbEnabled") : collector.getOrDefault("dbEnabled", environment.getProperty("log.collector.db.enabled", "false"))));
        collector.put("dbQuery", normalizeText(collectorBody.containsKey("dbQuery") ? collectorBody.get("dbQuery") : collector.getOrDefault("dbQuery", environment.getProperty("log.collector.db.query", "")), true, "数据库采集 SQL"));
        overrideRoot.put("collector", collector);
    }

    private void saveAiGroup(Map<String, Object> overrideRoot, Map<String, Object> aiBody) {
        if (aiBody.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> ai = new LinkedHashMap<>(SystemConfigFileSupport.nestedMap(overrideRoot.get("ai")));
        ai.put("baseUrl", normalizeUrl(aiBody.containsKey("baseUrl") ? aiBody.get("baseUrl") : ai.getOrDefault("baseUrl", environment.getProperty("spring.ai.openai.base-url", "")), "AI Base URL"));
        ai.put("chatModel", normalizeText(aiBody.containsKey("chatModel") ? aiBody.get("chatModel") : ai.getOrDefault("chatModel", environment.getProperty("spring.ai.openai.chat.options.model", "")), true, "聊天模型"));
        ai.put("embeddingModel", normalizeText(aiBody.containsKey("embeddingModel") ? aiBody.get("embeddingModel") : ai.getOrDefault("embeddingModel", environment.getProperty("spring.ai.openai.embedding.options.model", "")), true, "Embedding 模型"));
        overrideRoot.put("ai", ai);
    }

    private void savePathPolicyGroup(Map<String, Object> overrideRoot, Map<String, Object> pathPolicyBody) {
        if (pathPolicyBody.isEmpty()) {
            return;
        }
        Map<String, Object> current = agentPathPolicyService.getEffectivePolicyView();
        LinkedHashMap<String, Object> compatBody = new LinkedHashMap<>();
        compatBody.put("readPrefixes", normalizeStringList(pathPolicyBody.containsKey("readPrefixes") ? pathPolicyBody.get("readPrefixes") : current.get("readPrefixes"), true, "可读白名单"));
        compatBody.put("cleanRoots", normalizeStringList(pathPolicyBody.containsKey("cleanRoots") ? pathPolicyBody.get("cleanRoots") : current.get("cleanRoots"), true, "临时目录清理白名单"));
        compatBody.put("logCleanupRoots", normalizeStringList(pathPolicyBody.containsKey("logCleanupRoots") ? pathPolicyBody.get("logCleanupRoots") : current.get("logCleanupRoots"), true, "日志清理白名单"));
        compatBody.put("serviceRestartAllowlist", normalizeStringList(pathPolicyBody.containsKey("serviceRestartAllowlist") ? pathPolicyBody.get("serviceRestartAllowlist") : current.get("serviceRestartAllowlist"), true, "服务重启白名单"));
        agentPathPolicyService.saveEditablePolicy(compatBody);
        overrideRoot.put("pathPolicy", compatBody);
    }

    private void saveSecretOpsGroup(Map<String, Object> secretRoot, Map<String, Object> secretOpsBody) {
        if (secretOpsBody.isEmpty()) {
            return;
        }
        LinkedHashMap<String, Object> secretOps = new LinkedHashMap<>(SystemConfigFileSupport.nestedMap(secretRoot.get("secretOps")));
        String aiApiKey = normalizeText(secretOpsBody.get("aiApiKey"), false, "AI API Key");
        if (aiApiKey != null && !aiApiKey.isBlank()) {
            secretOps.put("aiApiKey", SystemConfigFileSupport.encryptSecret(aiApiKey, environment.getProperty("APP_CONFIG_SECRET", "")));
            secretRoot.put("secretOps", secretOps);
        }
    }

    private void applyRuntimeStateToTargets() {
        if (opsPatrolService != null) {
            opsPatrolService.applyHotConfig(
                    runtimeState.getPatrolInspectRoots(),
                    runtimeState.getPatrolDiskWarnPercent(),
                    runtimeState.getPatrolCpuWarnPercent(),
                    runtimeState.getAnomalySpikeFactor(),
                    runtimeState.getErrorAlarmMin());
        }
        if (opsAutoRemediationService != null) {
            opsAutoRemediationService.applyHotConfig(
                    runtimeState.isAutoRemediationEnabled(),
                    runtimeState.getAutoRemediationMode(),
                    runtimeState.getAutoRiskPatrolAutoMax(),
                    runtimeState.getAutoProposeTempCleanDiskMin(),
                    runtimeState.getAutoProposeLogCleanDiskMin());
        }
        if (autonomousOpsOrchestrator != null) {
            autonomousOpsOrchestrator.applyHotConfig(
                    runtimeState.healthCheckPortsCsv(),
                    runtimeState.getPingTarget());
        }
        if (opsDryRunProperties != null) {
            opsDryRunProperties.setGlobal(runtimeState.isDryRunGlobal());
        }
    }

    private Map<String, Object> group(String key, String title, List<Map<String, Object>> items) {
        LinkedHashMap<String, Object> group = new LinkedHashMap<>();
        group.put("key", key);
        group.put("title", title);
        group.put("items", items);
        return group;
    }

    private Map<String, Object> item(String key,
                                     String label,
                                     String applyMode,
                                     Object value,
                                     String inputType,
                                     Object pendingValue,
                                     boolean sensitive,
                                     List<String> options) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("applyMode", applyMode);
        item.put("value", value);
        item.put("inputType", inputType);
        item.put("pendingValue", pendingValue);
        item.put("pendingRestart", pendingValue != null);
        item.put("sensitive", sensitive);
        if (options != null && !options.isEmpty()) {
            item.put("options", options);
        }
        return item;
    }

    private Map<String, Object> restartItem(String key,
                                            String label,
                                            String inputType,
                                            String propertyKey,
                                            Object savedValue,
                                            List<Map<String, Object>> restartFlags) {
        return restartItem(key, label, inputType, propertyKey, savedValue, restartFlags, null);
    }

    private Map<String, Object> restartItem(String key,
                                            String label,
                                            String inputType,
                                            String propertyKey,
                                            Object savedValue,
                                            List<Map<String, Object>> restartFlags,
                                            List<String> options) {
        String liveValue = environment.getProperty(propertyKey);
        Object normalizedLive = normalizeLiveValue(inputType, liveValue);
        Object normalizedSaved = normalizeSavedValue(inputType, savedValue);
        Object pendingValue = valuesEqual(normalizedLive, normalizedSaved) ? null : normalizedSaved;
        if (pendingValue != null) {
            restartFlags.add(restartFlag(propertyGroup(propertyKey), key, label, pendingValue));
        }
        return item(key, label, "RESTART", normalizedLive, inputType, pendingValue, false, options);
    }

    private Map<String, Object> restartFlag(String group, String key, String label, Object pendingValue) {
        LinkedHashMap<String, Object> flag = new LinkedHashMap<>();
        flag.put("group", group);
        flag.put("key", key);
        flag.put("label", label);
        flag.put("pendingValue", pendingValue);
        return flag;
    }

    private Map<String, Object> fileInfo(String key, Path path) {
        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("key", key);
        info.put("path", path.toAbsolutePath().toString());
        info.put("exists", Files.exists(path));
        info.put("lastModified", SystemConfigFileSupport.latestSavedAt(path));
        return info;
    }

    private Object normalizeLiveValue(String inputType, String liveValue) {
        if (liveValue == null) {
            return null;
        }
        return switch (inputType) {
            case "boolean" -> Boolean.parseBoolean(liveValue);
            case "number" -> parseNumber(liveValue);
            default -> liveValue;
        };
    }

    private Object normalizeSavedValue(String inputType, Object savedValue) {
        if (savedValue == null) {
            return null;
        }
        return switch (inputType) {
            case "boolean" -> normalizeBoolean(savedValue);
            case "number" -> parseNumber(String.valueOf(savedValue));
            default -> savedValue;
        };
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number ln && right instanceof Number rn) {
            return Double.compare(ln.doubleValue(), rn.doubleValue()) == 0;
        }
        return Objects.equals(left, right);
    }

    private String propertyGroup(String propertyKey) {
        if (propertyKey.startsWith("log.collector")) {
            return "collector";
        }
        if (propertyKey.startsWith("spring.ai")) {
            return "ai";
        }
        return "runtime";
    }

    private List<String> normalizeStringList(Object raw, boolean requireNonEmpty, String fieldName) {
        List<String> values = SystemConfigFileSupport.stringList(raw);
        if (requireNonEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return values;
    }

    private List<Integer> normalizePortList(Object raw) {
        List<Integer> ports;
        try {
            ports = SystemConfigFileSupport.integerList(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("健康检查端口必须为整数");
        }
        if (ports.isEmpty()) {
            throw new IllegalArgumentException("健康检查端口不能为空");
        }
        for (Integer port : ports) {
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("健康检查端口必须位于 1-65535 之间");
            }
        }
        return ports;
    }

    private String normalizeHost(Object raw, String fieldName) {
        String value = normalizeText(raw, true, fieldName);
        if (value.contains(" ")) {
            throw new IllegalArgumentException(fieldName + "不能包含空格");
        }
        return value;
    }

    private boolean normalizeBoolean(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private String normalizeMode(Object raw) {
        return normalizeEnum(raw, List.of("HYBRID", "CONFIRM_FIRST", "IMMEDIATE"), "自动修复模式");
    }

    private String normalizeEnum(Object raw, List<String> allowed, String fieldName) {
        String value = normalizeText(raw, true, fieldName).toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(fieldName + "不合法");
        }
        return value;
    }

    private double normalizePercent(Object raw, String fieldName) {
        double value = normalizePositiveDouble(raw, fieldName);
        if (value <= 0 || value > 100) {
            throw new IllegalArgumentException(fieldName + "必须位于 0-100 之间");
        }
        return value;
    }

    private double normalizePositiveDouble(Object raw, String fieldName) {
        try {
            double value = Double.parseDouble(String.valueOf(raw));
            if (value <= 0) {
                throw new IllegalArgumentException(fieldName + "必须大于 0");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + "格式不正确");
        }
    }

    private int normalizeIntRange(Object raw, int min, int max, String fieldName) {
        try {
            int value = Integer.parseInt(String.valueOf(raw));
            if (value < min || value > max) {
                throw new IllegalArgumentException(fieldName + "必须位于 " + min + "-" + max + " 之间");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + "格式不正确");
        }
    }

    private String normalizeUrl(Object raw, String fieldName) {
        String value = normalizeText(raw, true, fieldName);
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException(fieldName + "必须为 http 或 https URL");
            }
            return value;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + "格式不正确");
        }
    }

    private String normalizeText(Object raw, boolean required, String fieldName) {
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException(fieldName + "不能为空");
            }
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (required && value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value;
    }

    private Number parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.contains(".")) {
            return Double.parseDouble(raw);
        }
        return Integer.parseInt(raw);
    }
}
