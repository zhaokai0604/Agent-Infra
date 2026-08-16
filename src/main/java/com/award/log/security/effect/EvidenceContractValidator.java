package com.award.log.security.effect;

import com.award.log.mcp.WriteToolResultSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 写操作证据契约：真实写必须返回可机读证据字段，否则标记证据不完整。
 */
public final class EvidenceContractValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceContractValidator() {
    }

    public record ValidationResult(boolean complete, String contractId, List<String> missingFields, String message) {
        public static ValidationResult ok(String contractId) {
            return new ValidationResult(true, contractId, List.of(), "");
        }

        public static ValidationResult incomplete(String contractId, List<String> missing, String message) {
            return new ValidationResult(false, contractId, List.copyOf(missing), message);
        }
    }

    public static ValidationResult validate(ToolEffect effect, String toolResultJson) {
        if (effect == null || !effect.writeEffect()) {
            return ValidationResult.ok("");
        }
        // 非真实写模式（DRY-RUN）不强制证据
        String mode = WriteToolResultSupport.extractMode(toolResultJson);
        if (mode != null) {
            String m = mode.trim().toUpperCase(Locale.ROOT);
            if ("DRY-RUN".equals(m) || "PREVIEW".equals(m) || "SCAN".equals(m)) {
                return ValidationResult.ok(effect.evidenceContractId());
            }
        }
        if (!WriteToolResultSupport.isToolSuccess(toolResultJson)) {
            return ValidationResult.ok(effect.evidenceContractId());
        }

        String contractId = effect.evidenceContractId();
        if (contractId == null || contractId.isBlank()) {
            // 无契约时退回通用写证据
            if (WriteToolResultSupport.isConfirmedRealWrite(toolResultJson)) {
                return ValidationResult.ok("");
            }
            return ValidationResult.incomplete("", List.of("writeEvidence"),
                    "真实写缺少可机读执行证据");
        }

        JsonNode data = extractData(toolResultJson);
        List<String> missing = new ArrayList<>();
        switch (contractId) {
            case "DELETE_PATH" -> {
                if (!hasPositiveNumber(data, "filesDeleted")
                        && !hasPositiveNumber(data, "bytesFreed")
                        && !hasTrue(data, "removed")) {
                    missing.add("filesDeleted|bytesFreed|removed");
                }
            }
            case "RESTART_SERVICE" -> {
                if (!hasText(data, "service") && !hasText(data, "unit") && !hasText(data, "serviceName")) {
                    missing.add("service");
                }
                if (!hasText(data, "beforeState") && !hasText(data, "afterState")
                        && !hasText(data, "status") && !hasTrue(data, "success")
                        && !WriteToolResultSupport.isRealWriteMode(mode)) {
                    missing.add("beforeState|afterState|status");
                }
            }
            case "KILL_PROCESS" -> {
                if (!hasText(data, "pid") && !hasText(data, "containerName") && !hasText(data, "name")) {
                    missing.add("pid|containerName");
                }
            }
            case "MUTATE_CONFIG" -> {
                if (!hasText(data, "path") && !hasText(data, "target") && !hasTrue(data, "changed")) {
                    missing.add("path|changed");
                }
            }
            default -> {
                if (!WriteToolResultSupport.isConfirmedRealWrite(toolResultJson)) {
                    missing.add("writeEvidence");
                }
            }
        }
        if (missing.isEmpty()) {
            return ValidationResult.ok(contractId);
        }
        return ValidationResult.incomplete(contractId, missing,
                "证据契约 " + contractId + " 未满足，缺少: " + String.join(", ", missing));
    }

    /** 兼容：当调用方已知请求了真实写时使用。 */
    public static ValidationResult validateRequestedWrite(
            ToolEffect effect, boolean requestedRealWrite, String toolResultJson) {
        if (!requestedRealWrite) {
            return ValidationResult.ok(effect == null ? "" : effect.evidenceContractId());
        }
        return validate(effect, toolResultJson);
    }

    private static JsonNode extractData(String toolResultJson) {
        if (toolResultJson == null || toolResultJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolResultJson);
            if (root == null) {
                return null;
            }
            JsonNode data = root.get("data");
            if (data != null && data.isTextual()) {
                String raw = data.asText();
                if (raw != null && raw.trim().startsWith("{")) {
                    return MAPPER.readTree(raw);
                }
            }
            if (data != null && data.isObject()) {
                return data;
            }
            return root.isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasPositiveNumber(JsonNode data, String field) {
        if (data == null || !data.has(field)) {
            return false;
        }
        return data.path(field).asLong(0L) > 0L;
    }

    private static boolean hasText(JsonNode data, String field) {
        if (data == null || !data.has(field)) {
            return false;
        }
        return !data.path(field).asText("").isBlank();
    }

    private static boolean hasTrue(JsonNode data, String field) {
        return data != null && data.path(field).asBoolean(false);
    }
}
