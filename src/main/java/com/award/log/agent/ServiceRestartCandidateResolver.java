package com.award.log.agent;

import com.award.log.config.AgentOpsProperties;
import com.award.log.governance.GovernanceAdmissionVerdict;
import com.award.log.governance.OpsGovernanceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从服务感知结果与白名单/治理策略中选取可重启的服务名（Linux systemd / Windows Service）。
 */
@Component
public class ServiceRestartCandidateResolver {

    private static final Pattern SERVICE_TOKEN = Pattern.compile("^[a-zA-Z0-9@._\\-]{1,128}$");

    private final AgentOpsProperties agentOpsProperties;
    private final OpsGovernanceService opsGovernanceService;
    private final ObjectMapper objectMapper;

    public ServiceRestartCandidateResolver(
            AgentOpsProperties agentOpsProperties,
            OpsGovernanceService opsGovernanceService,
            ObjectMapper objectMapper) {
        this.agentOpsProperties = agentOpsProperties;
        this.opsGovernanceService = opsGovernanceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 优先从感知到的 failed unit 中选取：须在白名单内且治理未 FORBIDDEN。
     */
    public String pickFromFailedUnits(List<String> failedUnitNames) {
        if (failedUnitNames == null || failedUnitNames.isEmpty()) {
            return null;
        }
        Set<String> allowlist = normalizedAllowlist();
        for (String raw : failedUnitNames) {
            String svc = normalizeServiceName(raw);
            if (svc.isEmpty() || !allowlist.contains(svc)) {
                continue;
            }
            if (governanceAllowsRestart(svc)) {
                return svc;
            }
        }
        return null;
    }

    /**
     * CPU 压力等无明确 failed unit 时：白名单中首个治理允许重启的服务。
     */
    public String pickDefaultFromAllowlist() {
        for (String svc : normalizedAllowlist()) {
            if (governanceAllowsRestart(svc)) {
                return svc;
            }
        }
        return null;
    }

    public String pickFromUserMessage(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        String lower = userMessage.toLowerCase(Locale.ROOT);
        for (String svc : normalizedAllowlist()) {
            if (lower.contains(svc) && governanceAllowsRestart(svc)) {
                return svc;
            }
        }
        return null;
    }

    public static List<String> parseFailedUnitsFromToolJson(String toolJson, ObjectMapper mapper) {
        if (toolJson == null || toolJson.isBlank() || mapper == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode root = mapper.readTree(toolJson);
            if (!root.path("success").asBoolean(false)) {
                return List.of();
            }
            JsonNode data = root.get("data");
            if (data != null && data.isTextual()) {
                root = mapper.readTree(data.asText());
                data = root.get("data");
            }
            String output = null;
            if (data != null && data.isObject()) {
                output = data.path("output").asText(null);
            }
            if (output == null || output.isBlank()) {
                output = root.path("output").asText("");
            }
            parseFailedUnitsFromOutput(output, names);
        } catch (Exception ignored) {
            parseFailedUnitsFromOutput(toolJson, names);
        }
        return List.copyOf(names);
    }

    private static final Set<String> OUTPUT_HEADER_TOKENS = Set.of(
            "name", "status", "starttype", "unit", "load", "active", "sub", "description");

    private static void parseFailedUnitsFromOutput(String output, Set<String> names) {
        if (output == null || output.isBlank()) {
            return;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("UNIT ") || trimmed.startsWith("●") && trimmed.length() < 3) {
                continue;
            }
            if (trimmed.matches("-{2,}.*") || trimmed.matches("={2,}.*")) {
                continue;
            }
            if (trimmed.startsWith("●")) {
                trimmed = trimmed.substring(1).trim();
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 0) {
                continue;
            }
            String token = parts[0];
            if (token.endsWith(".service")) {
                token = token.substring(0, token.length() - ".service".length());
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (OUTPUT_HEADER_TOKENS.contains(lower)) {
                continue;
            }
            if (SERVICE_TOKEN.matcher(token).matches()
                    && !"0".equals(token)
                    && !"loaded".equalsIgnoreCase(token)) {
                names.add(lower);
            }
        }
    }

    private Set<String> normalizedAllowlist() {
        Set<String> out = new LinkedHashSet<>();
        List<String> list = agentOpsProperties.getServiceRestart().getAllowlist();
        if (list == null) {
            return out;
        }
        for (String raw : list) {
            String svc = normalizeServiceName(raw);
            if (!svc.isEmpty()) {
                out.add(svc);
            }
        }
        return out;
    }

    private boolean governanceAllowsRestart(String serviceName) {
        OpsGovernanceService.GovernanceEvaluation eval = opsGovernanceService.evaluateStep(
                java.util.Map.of("kind", "RESTART_SERVICE", "serviceName", serviceName));
        return eval.verdict() != GovernanceAdmissionVerdict.FORBIDDEN;
    }

    private static String normalizeServiceName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(".service")) {
            s = s.substring(0, s.length() - ".service".length());
        }
        return s;
    }
}
