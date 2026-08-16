package com.award.log.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class OpsGovernanceService {

    private final OpsGovernanceProperties properties;

    public OpsGovernanceService(OpsGovernanceProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public Map<String, Object> summaryForPlatform() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("deliveryProfile", properties.getDeliveryProfile());
        out.put("defaultPathTier", properties.getDefaultPathTier().name());
        out.put("defaultServiceTier", properties.getDefaultServiceTier().name());
        out.put("serviceTierCount", properties.getServiceTiers().size());
        out.put("pathTierCount", properties.getPathTiers().size());
        out.put("actionMatrix", actionMatrixSummary());
        out.put("note", "NON_CORE 临时/日志清理须确认后执行；服务重启默认须确认；FORBIDDEN_AUTO 不纳入方案。");
        return out;
    }

    public List<Map<String, Object>> filterPlanSteps(List<Map<String, Object>> steps) {
        if (!properties.isEnabled() || steps == null || steps.isEmpty()) {
            return steps == null ? List.of() : steps;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            GovernanceEvaluation eval = evaluateStep(step);
            if (eval.verdict() == GovernanceAdmissionVerdict.FORBIDDEN) {
                log.info("[治理] 剔除步骤 kind={} target={} : {}", step.get("kind"), eval.target(), eval.reason());
                continue;
            }
            Map<String, Object> tagged = new LinkedHashMap<>(step);
            tagged.put("governanceVerdict", eval.verdict().name());
            tagged.put("governanceTier", eval.assetTier().name());
            tagged.put("governanceReason", eval.reason());
            out.add(tagged);
        }
        return out;
    }

    /**
     * 将 MCP 工具调用映射为治理步骤并裁决。非治理写动作返回 ALLOW_AUTO（不抬升、不旁路）。
     */
    public GovernanceEvaluation evaluateToolCall(String toolName, Map<String, Object> parameters) {
        Map<String, Object> step = toGovernanceStep(toolName, parameters);
        if (step == null) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.ALLOW_AUTO,
                    AssetTier.NON_CORE,
                    "",
                    "非治理写动作，跳过资产准入矩阵");
        }
        return evaluateStep(step);
    }

    /**
     * @return 可被 {@link #evaluateStep} 识别的步骤；无法映射时返回 null
     */
    public Map<String, Object> toGovernanceStep(String toolName, Map<String, Object> parameters) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String tool = toolName.trim();
        String path = firstParam(parameters, "path", "rootPath", "logPath", "targetPath",
                "target", "dir", "directory", "folder", "cleanPath");
        if (path.isEmpty()) {
            // LLM 偶发把路径塞进 paths 数组 / 逗号串
            path = firstParam(parameters, "paths");
            if (path.contains(",")) {
                path = path.split(",")[0].trim();
            }
        }
        String service = firstParam(parameters, "serviceName", "service", "unit");
        String operation = firstParam(parameters, "operation", "op", "action").toLowerCase(Locale.ROOT);

        return switch (tool) {
            case "CleanTempTool" -> stepOf("CLEAN_TEMP", path, service);
            case "LogCleanupTool" -> stepOf("CLEAN_LOG", path, service);
            case "ServiceRestartTool" -> stepOf("RESTART_SERVICE", path, service);
            case "DiskOpsTool" -> {
                if (operation.contains("clean") || operation.contains("delete") || operation.contains("清理")) {
                    yield stepOf("CLEAN_TEMP", path, service);
                }
                yield null;
            }
            case "LogOpsTool" -> {
                if (operation.contains("clean") || operation.contains("truncat") || operation.contains("清理")
                        || operation.contains("裁剪")) {
                    yield stepOf("CLEAN_LOG", path, service);
                }
                yield null;
            }
            case "ServiceOpsTool", "SystemdTool" -> {
                if (operation.contains("restart") || operation.contains("重启")) {
                    yield stepOf("RESTART_SERVICE", path, service);
                }
                yield null;
            }
            default -> null;
        };
    }

    private static Map<String, Object> stepOf(String kind, String path, String service) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("kind", kind);
        step.put("path", path == null ? "" : path);
        step.put("serviceName", service == null ? "" : service);
        return step;
    }

    private static String firstParam(Map<String, Object> parameters, String... keys) {
        if (parameters == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object v = parameters.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return "";
    }

    public GovernanceEvaluation evaluateStep(Map<String, Object> step) {
        OpsActionType action = OpsActionType.fromStepKind(String.valueOf(step.getOrDefault("kind", "")));
        if (action == null) {
            return new GovernanceEvaluation(GovernanceAdmissionVerdict.FORBIDDEN, AssetTier.FORBIDDEN_AUTO, "",
                    "未知步骤类型");
        }
        if (!properties.isEnabled()) {
            return new GovernanceEvaluation(GovernanceAdmissionVerdict.ALLOW_AUTO, AssetTier.NON_CORE, "",
                    "治理未启用");
        }

        AssetTier tier;
        String target;
        if (action == OpsActionType.SERVICE_RESTART) {
            target = normalizeServiceName(String.valueOf(step.getOrDefault("serviceName", "")));
            tier = resolveServiceTier(target);
        } else {
            target = String.valueOf(step.getOrDefault("path", ""));
            tier = resolvePathTier(target);
        }

        // 硬规则：文件系统根路径禁止自动清理（不可把 "/" 放进 pathTiers，否则会前缀匹配所有 Unix 路径）
        if (action != OpsActionType.SERVICE_RESTART && isFilesystemRoot(target)) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.FORBIDDEN,
                    AssetTier.FORBIDDEN_AUTO,
                    target,
                    "硬规则：文件系统根路径禁止清理/删除类自动方案");
        }

        // 硬规则：Windows 系统目录 / Program Files → 必须人工确认，禁止 ALLOW_AUTO
        if (action != OpsActionType.SERVICE_RESTART && isWindowsProtectedPath(target)) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.CONFIRM_ONLY,
                    AssetTier.CORE_STATELESS,
                    target,
                    "硬规则：路径命中 C:\\Windows 或 C:\\Program Files，pathSensitivity=10，必须人工确认");
        }

        if (tier == AssetTier.FORBIDDEN_AUTO) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.FORBIDDEN,
                    tier,
                    target,
                    "资产分级 FORBIDDEN_AUTO，禁止自动/待确认方案");
        }

        OpsGovernanceProperties.ActionAdmissionRule rule = ruleFor(action);
        if (rule == null || rule.getAllowedTiers() == null || !rule.getAllowedTiers().contains(tier)) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.FORBIDDEN,
                    tier,
                    target,
                    "动作 " + action.name() + " 不允许作用于分级 " + tier.name());
        }

        if (!rule.isAutoAllowed()) {
            return new GovernanceEvaluation(
                    GovernanceAdmissionVerdict.CONFIRM_ONLY,
                    tier,
                    target,
                    "动作 " + action.name() + " 默认须确认后执行");
        }

        return new GovernanceEvaluation(
                GovernanceAdmissionVerdict.ALLOW_AUTO,
                tier,
                target,
                "准入通过：低风险可进入 HYBRID 自动车道");
    }

    public AssetTier resolveServiceTier(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return properties.getDefaultServiceTier();
        }
        String key = normalizeServiceName(serviceName);
        AssetTier tier = properties.getServiceTiers().get(key);
        return tier != null ? tier : properties.getDefaultServiceTier();
    }

    public AssetTier resolvePathTier(String path) {
        if (path == null || path.isBlank()) {
            return properties.getDefaultPathTier();
        }
        String normalized = normalizePath(path);
        AssetTier best = null;
        int bestLen = -1;
        for (Map.Entry<String, AssetTier> e : properties.getPathTiers().entrySet()) {
            String prefix = normalizePath(e.getKey());
            if (normalized.equals(prefix) || normalized.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")
                    || (prefix.length() > 1 && normalized.startsWith(prefix))) {
                if (prefix.length() > bestLen) {
                    bestLen = prefix.length();
                    best = e.getValue();
                }
            }
        }
        return best != null ? best : properties.getDefaultPathTier();
    }

    private OpsGovernanceProperties.ActionAdmissionRule ruleFor(OpsActionType action) {
        String key = switch (action) {
            case TEMP_CLEANUP -> "temp-cleanup";
            case LOG_CLEANUP -> "log-cleanup";
            case SERVICE_RESTART -> "service-restart";
        };
        return properties.getActions().get(key);
    }

    private List<Map<String, Object>> actionMatrixSummary() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, OpsGovernanceProperties.ActionAdmissionRule> e : properties.getActions().entrySet()) {
            OpsGovernanceProperties.ActionAdmissionRule rule = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", e.getKey());
            row.put("allowedTiers", rule.getAllowedTiers().stream().map(Enum::name).toList());
            row.put("autoAllowed", rule.isAutoAllowed());
            row.put("verifyHint", rule.getVerifyHint());
            row.put("rollbackHint", rule.getRollbackHint());
            rows.add(row);
        }
        return rows;
    }

    private static String normalizeServiceName(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(".service", "");
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String p = raw.trim().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Unix `/` 或 Windows 盘符根（如 `C:` / `C:/`）视为文件系统根，禁止清理类自动执行。 */
    static boolean isFilesystemRoot(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String n = normalizePath(path);
        if ("/".equals(n)) {
            return true;
        }
        String lower = n.toLowerCase(Locale.ROOT);
        return lower.matches("^[a-z]:$") || lower.matches("^[a-z]:/$");
    }

    /** C:\\Windows 或 C:\\Program Files（含子路径）视为系统敏感目录。 */
    static boolean isWindowsProtectedPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String n = normalizePath(path).toLowerCase(Locale.ROOT);
        return n.equals("c:/windows")
                || n.startsWith("c:/windows/")
                || n.equals("c:/program files")
                || n.startsWith("c:/program files/")
                || n.equals("c:/programfiles")
                || n.startsWith("c:/programfiles/");
    }

    public record GovernanceEvaluation(
            GovernanceAdmissionVerdict verdict,
            AssetTier assetTier,
            String target,
            String reason
    ) {
    }
}
