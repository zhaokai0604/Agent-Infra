package com.award.log.mcp;

import com.award.log.config.SystemBootstrapSupport;
import com.award.log.util.OsRuntime;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers MCP tools and exposes API metadata.
 */
@Slf4j
@Component
public class McpToolCatalog {

    private static final String TOOLS_PACKAGE = "com.award.log.mcp.tools";

    private final ApplicationContext applicationContext;

    @Getter
    private Map<String, McpToolDescriptor> descriptors = Map.of();

    @Getter
    private Set<String> httpAllowedToolNames = Set.of();

    @Getter
    private Set<String> readOnlyObservationToolNames = Set.of();

    private final boolean httpAllowAllRegistered;

    public McpToolCatalog(
            ApplicationContext applicationContext,
            @org.springframework.beans.factory.annotation.Value("${agent.security.mcp-http-allow-all-registered:false}")
            boolean httpAllowAllRegistered) {
        this.applicationContext = applicationContext;
        this.httpAllowAllRegistered = httpAllowAllRegistered;
    }

    @PostConstruct
    void discover() {
        Map<String, McpToolDescriptor> map = new LinkedHashMap<>();
        registerAlias(map, "DiskInsightTool", null, true, 1.0, true);

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception e) {
                continue;
            }
            Class<?> type = ClassUtils.getUserClass(bean);
            if (!type.getPackageName().startsWith(TOOLS_PACKAGE)) {
                continue;
            }
            String simpleName = type.getSimpleName();
            if (!simpleName.endsWith("Tool")) {
                continue;
            }
            McpTool ann = AnnotationUtils.findAnnotation(type, McpTool.class);
            String toolName = ann != null && ann.value() != null && !ann.value().isBlank()
                    ? ann.value().trim()
                    : type.getSimpleName();
            boolean httpAllowed = ann != null ? ann.httpAllowed() : isHttpAllowedByConvention(toolName);
            double risk = ann != null ? ann.defaultRiskScore() : defaultRiskFor(toolName);
            boolean readOnly = ann != null ? ann.readOnlyObservation() : isReadOnlyByConvention(toolName);
            if ("FirstMcpTools".equals(toolName)) {
                httpAllowed = false;
            }
            map.putIfAbsent(toolName, new McpToolDescriptor(toolName, type.getName(), httpAllowed, risk, readOnly));
        }

        ensureDispatchableToolsRegistered(map);
        applyBuiltinRiskDefaults(map);

        descriptors = Collections.unmodifiableMap(map);
        Set<String> http = new LinkedHashSet<>();
        Set<String> readOnly = new LinkedHashSet<>();
        for (McpToolDescriptor descriptor : map.values()) {
            if (descriptor.httpAllowed()) {
                http.add(descriptor.toolName());
            }
            if (descriptor.readOnlyObservation()) {
                readOnly.add(descriptor.toolName());
            }
        }
        httpAllowedToolNames = Collections.unmodifiableSet(http);
        readOnlyObservationToolNames = Collections.unmodifiableSet(readOnly);
        log.info("McpToolCatalog loaded {} tools, {} HTTP-executable", map.size(), http.size());
    }

    private static void registerAlias(
            Map<String, McpToolDescriptor> map,
            String toolName,
            String beanClassName,
            boolean httpAllowed,
            double risk,
            boolean readOnly) {
        map.put(toolName, new McpToolDescriptor(toolName, beanClassName, httpAllowed, risk, readOnly));
    }

    private static void applyBuiltinRiskDefaults(Map<String, McpToolDescriptor> map) {
        for (var entry : map.entrySet()) {
            McpToolDescriptor descriptor = entry.getValue();
            if (descriptor.defaultRiskScore() >= 0) {
                continue;
            }
            double risk = defaultRiskFor(descriptor.toolName());
            map.put(entry.getKey(), new McpToolDescriptor(
                    descriptor.toolName(),
                    descriptor.beanClassName(),
                    descriptor.httpAllowed(),
                    risk,
                    descriptor.readOnlyObservation()));
        }
    }

    private static double defaultRiskFor(String toolName) {
        return switch (toolName) {
            case "DiskTool", "ProcessTool", "SystemLoadTool", "LogAnalysisTool", "NetworkTool" -> 0.5;
            case "ConfigCheckTool", "DiskAnalyzeTool", "DiskInsightTool", "PortHealthTool", "SslCertTool",
                    "ConfigDriftTool" -> 1.0;
            case "PrivilegeTool", "OsInsightTool", "DockerTool", "CronJobTool", "FirewallTool", "SystemdTool" -> 2.0;
            case "AutonomousOpsTool" -> 5.0;
            case "CleanTempTool", "DiskOpsTool", "LogCleanupTool", "LogOpsTool" -> 7.0;
            case "ServiceRestartTool", "ServiceOpsTool" -> 7.0;
            case "ContainerOpsTool" -> 6.0;
            case "ProcessOpsTool" -> 6.5;
            default -> 2.5;
        };
    }

    private static boolean isReadOnlyByConvention(String toolName) {
        return switch (toolName) {
            case "DiskTool", "DiskAnalyzeTool", "DiskInsightTool", "ProcessTool", "SystemLoadTool",
                    "LogAnalysisTool", "NetworkTool", "ConfigCheckTool", "PrivilegeTool", "OsInsightTool",
                    "PortHealthTool", "DockerTool", "CronJobTool", "FirewallTool", "SslCertTool",
                    "SystemdTool", "ConfigDriftTool" -> true;
            default -> false;
        };
    }

    private static boolean isHttpAllowedWriteTool(String toolName) {
        return switch (toolName) {
            case "CleanTempTool", "LogCleanupTool", "ServiceRestartTool", "AutonomousOpsTool",
                    "DiskOpsTool", "LogOpsTool", "ServiceOpsTool", "ContainerOpsTool", "ProcessOpsTool" -> true;
            default -> false;
        };
    }

    private static boolean isHttpAllowedByConvention(String toolName) {
        return isReadOnlyByConvention(toolName) || isHttpAllowedWriteTool(toolName);
    }

    public boolean isRegistered(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String key = toolName.trim();
        return descriptors.containsKey(key) || McpKnownTools.isDispatchable(key);
    }

    public boolean isHttpAllowed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String key = toolName.trim();
        if (httpAllowAllRegistered && McpKnownTools.isDispatchable(key)) {
            return true;
        }
        McpToolDescriptor descriptor = descriptors.get(key);
        return descriptor != null && descriptor.httpAllowed();
    }

    private static void ensureDispatchableToolsRegistered(Map<String, McpToolDescriptor> map) {
        for (String name : McpKnownTools.DISPATCHABLE) {
            map.compute(name, (key, existing) -> {
                if (existing != null) {
                    return existing;
                }
                boolean readOnly = isReadOnlyByConvention(key);
                return new McpToolDescriptor(
                        key,
                        null,
                        isHttpAllowedByConvention(key),
                        defaultRiskFor(key),
                        readOnly);
            });
        }
    }

    public boolean isReadOnlyObservation(String toolName) {
        return readOnlyObservationToolNames.contains(toolName);
    }

    public double defaultRiskScore(String toolName) {
        McpToolDescriptor descriptor = descriptors.get(toolName);
        if (descriptor == null) {
            return defaultRiskFor(toolName);
        }
        return descriptor.resolvedRiskScore(defaultRiskFor(toolName));
    }

    public List<Map<String, Object>> listToolsForApi() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpToolDescriptor descriptor : descriptors.values()) {
            if (!descriptor.httpAllowed()) {
                continue;
            }
            ToolUiMeta meta = TOOL_UI_META.getOrDefault(descriptor.toolName(), ToolUiMeta.generic(descriptor.toolName()));
            Map<String, Object> platformSupport = describePlatformSupport(descriptor.toolName());
            boolean available = Boolean.TRUE.equals(platformSupport.get("available"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", descriptor.toolName());
            row.put("displayName", meta.title());
            row.put("description", meta.title());
            row.put("summary", meta.summary());
            row.put("group", toolGroup(descriptor.toolName()));
            row.put("groupLabel", groupLabel(toolGroup(descriptor.toolName())));
            row.put("endpoint", "/mcp/execute");
            row.put("commandHint", meta.commandHint());
            row.put("readOnly", descriptor.readOnlyObservation());
            row.put("writeAction", !descriptor.readOnlyObservation());
            row.put("defaultRiskScore", descriptor.resolvedRiskScore(defaultRiskFor(descriptor.toolName())));
            row.put("operations", operationsFor(descriptor.toolName()));
            row.put("supportedPlatforms", supportedPlatformsFor(descriptor.toolName()));
            row.put("confirmStrategy", descriptor.readOnlyObservation() ? "none" : "risk-gated");
            row.put("dependencyChecks", dependencyChecksFor(descriptor.toolName(), OsRuntime.isWindows()));
            row.put("defaultParameters", defaultParametersFor(descriptor.toolName()));
            row.put("platformSupport", platformSupport);
            row.put("status", available ? "online" : "unavailable");
            row.put("unavailableReason", available ? "" : String.valueOf(platformSupport.getOrDefault("reason", "")));
            row.put("warnings", available
                    ? List.of()
                    : List.of(String.valueOf(platformSupport.getOrDefault("reason", "当前平台不可用"))));
            row.put("contractVersion", 2);
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> describePlatformSupport(String toolName) {
        LinkedHashMap<String, Object> support = new LinkedHashMap<>();
        Map<String, Object> capabilities = SystemBootstrapSupport.buildCapabilitySnapshot();
        boolean windows = Boolean.TRUE.equals(capabilities.get("windows"));
        String currentPlatform = windows ? "windows" : "linux";
        List<String> supportedPlatforms = supportedPlatformsFor(toolName);
        List<String> dependencyChecks = dependencyChecksFor(toolName, windows);
        boolean platformMatched = supportedPlatforms.contains(currentPlatform);
        boolean dependencyPassed = true;
        for (String check : dependencyChecks) {
            if (!Boolean.TRUE.equals(capabilities.get(check))) {
                dependencyPassed = false;
                break;
            }
        }
        boolean available = platformMatched && dependencyPassed;
        support.put("available", available);
        support.put("currentPlatform", currentPlatform);
        support.put("supportedPlatforms", supportedPlatforms);
        support.put("requiredCapabilities", dependencyChecks);
        support.put("capabilities", capabilities);
        support.put("reason", buildUnavailableReason(toolName, currentPlatform, supportedPlatforms, dependencyChecks, capabilities));
        return support;
    }

    /** 供 Tool Agent 系统提示词注入：简要工具目录 */
    public String summarizeToolsForPrompt(int maxEntries) {
        if (descriptors.isEmpty() || maxEntries <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (McpToolDescriptor descriptor : descriptors.values()) {
            if (count >= maxEntries) {
                break;
            }
            ToolUiMeta meta = TOOL_UI_META.getOrDefault(descriptor.toolName(), ToolUiMeta.generic(descriptor.toolName()));
            sb.append("- **").append(descriptor.toolName()).append("**：")
                    .append(meta.summary())
                    .append(descriptor.readOnlyObservation() ? "（只读）" : "")
                    .append("\n");
            count++;
        }
        return sb.toString().trim();
    }

    private static String toolGroup(String toolName) {
        return switch (toolName) {
            case "DiskTool", "DiskInsightTool", "ProcessTool", "SystemLoadTool", "PortHealthTool",
                    "DockerTool", "CronJobTool", "FirewallTool", "SslCertTool", "SystemdTool",
                    "OsInsightTool", "NetworkTool" -> "observe";
            case "DiskAnalyzeTool", "LogAnalysisTool", "ConfigCheckTool", "ConfigDriftTool",
                    "PrivilegeTool" -> "diagnose";
            case "CleanTempTool", "LogCleanupTool", "ServiceRestartTool", "DiskOpsTool", "LogOpsTool",
                    "ServiceOpsTool", "ContainerOpsTool", "ProcessOpsTool" -> "execute";
            case "AutonomousOpsTool" -> "automation";
            default -> "tool";
        };
    }

    private static String groupLabel(String group) {
        return switch (group) {
            case "observe" -> "观测";
            case "diagnose" -> "诊断";
            case "execute" -> "执行";
            case "automation" -> "自动化";
            default -> "工具";
        };
    }

    private static List<String> operationsFor(String toolName) {
        return switch (toolName) {
            case "DiskOpsTool" -> List.of("df", "hotspots", "analyze", "clean-temp");
            case "LogOpsTool" -> List.of("analyze", "cleanup");
            case "ServiceOpsTool", "SystemdTool" -> List.of("failed", "status", "restart");
            case "ContainerOpsTool", "DockerTool" -> List.of("list", "inspect", "restart", "stop");
            case "ProcessOpsTool", "ProcessTool" -> List.of("list", "kill");
            case "OsInsightTool" -> List.of("journal", "ss", "lsof");
            case "NetworkTool" -> List.of("ping", "traceroute");
            case "CronJobTool" -> List.of("user", "system");
            default -> List.of();
        };
    }

    private static List<String> supportedPlatformsFor(String toolName) {
        return switch (toolName) {
            case "SystemdTool", "ServiceRestartTool", "ServiceOpsTool",
                    "CronJobTool", "FirewallTool", "OsInsightTool", "DockerTool", "ContainerOpsTool" ->
                    List.of("windows", "linux");
            default -> List.of("windows", "linux");
        };
    }

    private static List<String> dependencyChecksFor(String toolName, boolean windows) {
        return switch (toolName) {
            case "DockerTool", "ContainerOpsTool" -> List.of("docker");
            case "CronJobTool" -> windows ? List.of("schtasks") : List.of();
            case "FirewallTool" -> windows ? List.of("netsh") : List.of();
            case "SystemdTool", "ServiceRestartTool", "ServiceOpsTool" ->
                    windows ? List.of("powershell", "getService") : List.of("systemctl");
            case "OsInsightTool" -> windows ? List.of("powershell") : List.of();
            default -> List.of();
        };
    }

    private static Map<String, Object> defaultParametersFor(String toolName) {
        boolean windows = OsRuntime.isWindows();
        LinkedHashMap<String, Object> defaults = new LinkedHashMap<>();
        switch (toolName) {
            case "DiskInsightTool", "DiskAnalyzeTool", "DiskOpsTool" -> defaults.put("rootPath", windows ? "C:/Users/Administrator/AppData/Local/Temp" : "/tmp");
            case "LogAnalysisTool", "LogCleanupTool", "LogOpsTool" -> defaults.put("logPath", windows ? "logs" : "/var/log");
            case "CleanTempTool" -> defaults.put("path", windows ? "C:/Users/Administrator/AppData/Local/Temp" : "/tmp");
            case "ServiceRestartTool", "ServiceOpsTool", "SystemdTool" -> defaults.put("serviceName", windows ? "W32Time" : "nginx");
            case "NetworkTool" -> defaults.put("target", "127.0.0.1");
            case "PortHealthTool" -> {
                defaults.put("host", "127.0.0.1");
                defaults.put("port", 8088);
            }
            case "SslCertTool" -> {
                defaults.put("host", "www.baidu.com");
                defaults.put("port", 443);
            }
            default -> {
                return Map.of();
            }
        }
        return defaults;
    }

    private static String buildUnavailableReason(String toolName,
                                                 String currentPlatform,
                                                 List<String> supportedPlatforms,
                                                 List<String> dependencyChecks,
                                                 Map<String, Object> capabilities) {
        if (!supportedPlatforms.contains(currentPlatform)) {
            return "当前平台 " + currentPlatform + " 不在支持范围内";
        }
        for (String capability : dependencyChecks) {
            if (!Boolean.TRUE.equals(capabilities.get(capability))) {
                return switch (capability) {
                    case "docker" -> "当前环境未检测到 Docker 命令";
                    case "schtasks" -> "当前环境未检测到 schtasks 能力";
                    case "netsh" -> "当前环境未检测到 netsh 能力";
                    case "systemctl" -> "当前环境未检测到 systemctl 能力";
                    case "powershell" -> "当前环境未检测到 PowerShell 能力";
                    case "getService" -> "当前环境未检测到 Windows 服务管理能力";
                    default -> "当前环境缺少前置能力: " + capability;
                };
            }
        }
        return "";
    }

    private record ToolUiMeta(String title, String summary, String commandHint) {
        static ToolUiMeta generic(String name) {
            return new ToolUiMeta(name, "MCP ops tool", "system command");
        }
    }

    private static final Map<String, ToolUiMeta> TOOL_UI_META = buildUiMeta();

    private static Map<String, ToolUiMeta> buildUiMeta() {
        Map<String, ToolUiMeta> map = new LinkedHashMap<>();
        map.put("DiskTool", new ToolUiMeta("Disk usage", "Inspect filesystem usage", "df -h"));
        map.put("DiskInsightTool", new ToolUiMeta("Disk hotspots", "Find large directories under allowed roots", "du -xk --max-depth=N"));
        map.put("ProcessTool", new ToolUiMeta("Processes", "Inspect running processes", "ps aux --sort=-%cpu"));
        map.put("SystemLoadTool", new ToolUiMeta("System load", "Inspect CPU and memory pressure", "top -bn1"));
        map.put("LogAnalysisTool", new ToolUiMeta("Log analysis", "Analyze abnormal logs", "log sample + Drain"));
        map.put("CleanTempTool", new ToolUiMeta("Temp cleanup", "Clean temp files under allowlisted paths", "find + rm"));
        map.put("ConfigCheckTool", new ToolUiMeta("Config check", "Validate config syntax or structure", "config probe"));
        map.put("NetworkTool", new ToolUiMeta("Network diagnostics", "Check connectivity and packet loss", "ping / traceroute"));
        map.put("PrivilegeTool", new ToolUiMeta("Privilege check", "Inspect permission boundaries", "permission probe"));
        map.put("OsInsightTool", new ToolUiMeta("OS insight", "Inspect host signals", "journalctl | ss | lsof"));
        map.put("LogCleanupTool", new ToolUiMeta("Log cleanup", "Delete old logs in allowed directories", "find + rm"));
        map.put("ServiceRestartTool", new ToolUiMeta("Service restart", "Restart allowlisted services", "systemctl restart"));
        map.put("DiskAnalyzeTool", new ToolUiMeta("Disk analysis", "Combine disk usage and hotspot analysis", "df + du"));
        map.put("PortHealthTool", new ToolUiMeta("Port health", "Check TCP port reachability", "TCP socket"));
        map.put("DockerTool", new ToolUiMeta("Docker inspect", "Inspect containers and health", "docker ps / inspect"));
        map.put("CronJobTool", new ToolUiMeta("Scheduled jobs", "List cron or scheduled tasks", "crontab -l / schtasks"));
        map.put("FirewallTool", new ToolUiMeta("Firewall status", "Inspect host firewall status", "ufw / firewalld / netsh"));
        map.put("SslCertTool", new ToolUiMeta("SSL certificate", "Read certificate validity and subject", "TLS handshake"));
        map.put("SystemdTool", new ToolUiMeta(
                "Service status",
                OsRuntime.isWindows() ? "Inspect Windows auto-start services" : "Inspect failed systemd units",
                OsRuntime.isWindows() ? "Get-Service" : "systemctl --failed"));
        map.put("AutonomousOpsTool", new ToolUiMeta("Patrol automation", "Run patrol and auto-remediation single track", "patrol orchestration"));
        map.put("ConfigDriftTool", new ToolUiMeta("Config drift", "Compare config file snapshots", "checksum diff"));
        map.put("DiskOpsTool", new ToolUiMeta("Disk ops", "Unified disk read/write gateway", "gateway"));
        map.put("LogOpsTool", new ToolUiMeta("Log ops", "Unified log read/write gateway", "gateway"));
        map.put("ServiceOpsTool", new ToolUiMeta("Service ops", "Unified service read/write gateway", "gateway"));
        map.put("ContainerOpsTool", new ToolUiMeta("Container ops", "Unified container gateway", "gateway"));
        map.put("ProcessOpsTool", new ToolUiMeta("Process ops", "Unified process gateway", "gateway"));
        return Collections.unmodifiableMap(map);
    }
}
