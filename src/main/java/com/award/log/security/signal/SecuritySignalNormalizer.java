package com.award.log.security.signal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class SecuritySignalNormalizer {

    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXX"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private final ObjectMapper objectMapper;

    public SecuritySignalNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SecuritySignal normalize(String sourceHint, Object payload, long ingestTimeMs) {
        JsonNode node = toJsonNode(payload);
        String rawPayload = toRawPayload(payload);
        String normalizedHint = sourceHint == null ? "" : sourceHint.trim();

        if (isSuricata(normalizedHint, node)) {
            return normalizeSuricata(normalizedHint, node, rawPayload, ingestTimeMs);
        }
        if (isWazuhOrSysmon(normalizedHint, node)) {
            return normalizeWazuhOrSysmon(normalizedHint, node, rawPayload, ingestTimeMs);
        }
        return normalizeGeneric(normalizedHint, node, rawPayload, ingestTimeMs);
    }

    private SecuritySignal normalizeSuricata(String sourceHint, JsonNode node, String rawPayload, long ingestTimeMs) {
        int suricataSeverity = intValue(node, "alert.severity", "severity");
        int severityScore = switch (suricataSeverity) {
            case 1 -> 90;
            case 2 -> 72;
            case 3 -> 45;
            default -> 30;
        };
        String severity = severityLabel(severityScore);
        String action = firstNonBlank(text(node, "alert.action"), text(node, "verdict.action"), text(node, "action"));
        boolean blocked = containsOneOf(action, "block", "drop", "reject", "deny");
        String category = text(node, "alert.category");
        String signature = firstNonBlank(text(node, "alert.signature"), text(node, "message"), "Suricata alert");
        List<String> tags = distinctStrings(
                category,
                text(node, "app_proto"),
                text(node, "proto"),
                text(node, "alert.signature_id"));

        return new SecuritySignal(
                firstNonBlank(text(node, "event_id"), text(node, "flow_id"), buildFallbackId("suricata", rawPayload, ingestTimeMs)),
                "NIDS",
                firstNonBlank(text(node, "event_type"), "alert"),
                signature,
                severity,
                severityScore,
                0.9d,
                firstNonBlank(text(node, "host"), text(node, "in_iface"), sourceHint),
                firstNonBlank(text(node, "host"), text(node, "sensor"), text(node, "observer.hostname")),
                text(node, "src_ip"),
                firstNonBlank(text(node, "dest_ip"), text(node, "dst_ip")),
                firstNonBlank(text(node, "app_proto"), text(node, "proto")),
                null,
                null,
                parseEventTime(node),
                ingestTimeMs,
                blocked,
                tags,
                buildDetail(List.of(
                        named("category", category),
                        named("action", action),
                        named("flow", firstNonBlank(text(node, "flow_id"), text(node, "flow.id"))))),
                rawPayload);
    }

    private SecuritySignal normalizeWazuhOrSysmon(String sourceHint, JsonNode node, String rawPayload, long ingestTimeMs) {
        int level = intValue(node, "rule.level", "data.rule.level", "severity");
        int severityScore = wazuhSeverityScore(level);
        String severity = severityLabel(severityScore);
        String eventId = firstNonBlank(
                text(node, "data.win.system.eventID"),
                text(node, "winlog.event_id"),
                text(node, "event.code"),
                text(node, "rule.id"),
                buildFallbackId("wazuh", rawPayload, ingestTimeMs));
        String title = firstNonBlank(
                text(node, "rule.description"),
                text(node, "data.win.system.message"),
                text(node, "message"),
                "Host security alert");
        String image = firstNonBlank(
                text(node, "data.win.eventdata.Image"),
                text(node, "syscheck.path"),
                text(node, "process.name"));
        String commandLine = firstNonBlank(
                text(node, "data.win.eventdata.CommandLine"),
                text(node, "process.command_line"));
        String host = firstNonBlank(
                text(node, "agent.name"),
                text(node, "host.name"),
                text(node, "hostname"),
                text(node, "computer_name"));
        String sourceType = isProcessLike(node, sourceHint) ? "PIDS" : "HIDS";
        List<String> tags = distinctStrings(
                listValue(node, "rule.groups"),
                listValue(node, "rule.mitre.id"),
                text(node, "decoder.name"),
                text(node, "event.module"));

        return new SecuritySignal(
                eventId,
                sourceType,
                firstNonBlank(
                        text(node, "decoder.name"),
                        text(node, "event.code"),
                        text(node, "data.win.system.eventID"),
                        "host-alert"),
                title,
                severity,
                severityScore,
                0.82d,
                firstNonBlank(text(node, "agent.id"), text(node, "manager.name"), sourceHint),
                host,
                firstNonBlank(text(node, "data.srcip"), text(node, "srcip"), text(node, "source.ip")),
                firstNonBlank(text(node, "data.dstip"), text(node, "dstip"), text(node, "destination.ip")),
                firstNonBlank(text(node, "service.name"), text(node, "data.service")),
                image,
                firstNonBlank(text(node, "syscheck.path"), text(node, "file.path")),
                parseEventTime(node),
                ingestTimeMs,
                containsOneOf(firstNonBlank(text(node, "rule.description"), text(node, "message")), "blocked", "quarantine", "terminated"),
                tags,
                buildDetail(List.of(
                        named("command", commandLine),
                        named("ruleGroups", joinList(listValue(node, "rule.groups"))),
                        named("mitre", joinList(listValue(node, "rule.mitre.id"))))),
                rawPayload);
    }

    private SecuritySignal normalizeGeneric(String sourceHint, JsonNode node, String rawPayload, long ingestTimeMs) {
        int severityScore = genericSeverityScore(
                firstNonBlank(text(node, "severity"), text(node, "level"), text(node, "status")));
        String severity = severityLabel(severityScore);
        String sourceType = normalizeSourceType(sourceHint);
        return new SecuritySignal(
                firstNonBlank(text(node, "id"), text(node, "eventId"), buildFallbackId(sourceType, rawPayload, ingestTimeMs)),
                sourceType,
                firstNonBlank(text(node, "eventType"), text(node, "event_type"), "generic-alert"),
                firstNonBlank(text(node, "title"), text(node, "message"), "Security signal"),
                severity,
                severityScore,
                0.6d,
                firstNonBlank(text(node, "sensorId"), text(node, "sensor"), sourceHint),
                firstNonBlank(text(node, "host"), text(node, "hostname"), text(node, "host.name")),
                firstNonBlank(text(node, "srcIp"), text(node, "src_ip"), text(node, "source.ip")),
                firstNonBlank(text(node, "dstIp"), text(node, "dest_ip"), text(node, "destination.ip")),
                firstNonBlank(text(node, "serviceName"), text(node, "service")),
                firstNonBlank(text(node, "processName"), text(node, "process"), text(node, "process.name")),
                firstNonBlank(text(node, "filePath"), text(node, "path"), text(node, "file.path")),
                parseEventTime(node),
                ingestTimeMs,
                containsOneOf(firstNonBlank(text(node, "action"), text(node, "status")), "block", "deny", "drop"),
                distinctStrings(listValue(node, "tags"), sourceType),
                firstNonBlank(text(node, "detail"), text(node, "description"), text(node, "message")),
                rawPayload);
    }

    private JsonNode toJsonNode(Object payload) {
        if (payload == null) {
            return objectMapper.createObjectNode();
        }
        if (payload instanceof JsonNode node) {
            return node;
        }
        if (payload instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return objectMapper.readTree(trimmed);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Unsupported JSON payload", e);
                }
            }
            return objectMapper.valueToTree(Map.of("message", text));
        }
        return objectMapper.valueToTree(payload);
    }

    private String toRawPayload(Object payload) {
        if (payload == null) {
            return "{}";
        }
        if (payload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException("Unable to serialize security signal payload", e));
        }
    }

    private static boolean isSuricata(String sourceHint, JsonNode node) {
        String hint = sourceHint == null ? "" : sourceHint.toLowerCase(Locale.ROOT);
        return hint.contains("suricata")
                || hint.contains("eve")
                || node.has("src_ip") && (node.has("dest_ip") || node.has("dst_ip")) && node.has("alert");
    }

    private static boolean isWazuhOrSysmon(String sourceHint, JsonNode node) {
        String hint = sourceHint == null ? "" : sourceHint.toLowerCase(Locale.ROOT);
        return hint.contains("wazuh")
                || hint.contains("sysmon")
                || !path(node, "rule").isMissingNode()
                || !path(node, "data.win").isMissingNode()
                || !path(node, "winlog").isMissingNode();
    }

    private static boolean isProcessLike(JsonNode node, String sourceHint) {
        String hint = sourceHint == null ? "" : sourceHint.toLowerCase(Locale.ROOT);
        if (hint.contains("syscheck") || hint.contains("fim") || hint.contains("integrity")) {
            return false;
        }
        // 文件完整性：有 syscheck/file path 应走 HIDS，勿误判 PIDS
        if (!text(node, "syscheck.path").isBlank() || !text(node, "file.path").isBlank()) {
            return false;
        }
        if (hint.contains("sysmon") || hint.contains("pids") || hint.contains("process")) {
            return true;
        }
        return !text(node, "data.win.eventdata.Image").isBlank()
                || !text(node, "process.name").isBlank();
    }

    private static String normalizeSourceType(String sourceHint) {
        String hint = sourceHint == null ? "" : sourceHint.trim().toUpperCase(Locale.ROOT);
        if (hint.contains("PIDS") || hint.contains("SYSMON")) {
            return "PIDS";
        }
        if (hint.contains("HIDS") || hint.contains("WAZUH")) {
            return "HIDS";
        }
        if (hint.contains("NIDS") || hint.contains("IDS") || hint.contains("SURICATA") || hint.contains("NETFLOW")) {
            return "NIDS";
        }
        return hint.isBlank() ? "GENERIC" : hint;
    }

    private static int wazuhSeverityScore(int level) {
        if (level >= 14) {
            return 95;
        }
        if (level >= 12) {
            return 84;
        }
        if (level >= 9) {
            return 72;
        }
        if (level >= 6) {
            return 56;
        }
        if (level >= 3) {
            return 38;
        }
        return 20;
    }

    private static int genericSeverityScore(String severity) {
        String value = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CRITICAL", "FATAL", "SEV1" -> 95;
            case "HIGH", "ERROR", "SEV2" -> 80;
            case "MEDIUM", "WARN", "WARNING", "SEV3" -> 60;
            case "LOW", "INFO", "SEV4" -> 35;
            default -> 25;
        };
    }

    private static String severityLabel(int severityScore) {
        if (severityScore >= 90) {
            return "CRITICAL";
        }
        if (severityScore >= 75) {
            return "HIGH";
        }
        if (severityScore >= 50) {
            return "MEDIUM";
        }
        if (severityScore > 0) {
            return "LOW";
        }
        return "INFO";
    }

    private static String buildFallbackId(String prefix, String rawPayload, long ingestTimeMs) {
        return prefix + "-" + Integer.toHexString(Objects.hash(rawPayload, ingestTimeMs));
    }

    private static String buildDetail(List<String> parts) {
        List<String> filtered = parts.stream().filter(s -> s != null && !s.isBlank()).toList();
        return String.join("; ", filtered);
    }

    private static String named(String key, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return key + "=" + value;
    }

    private static String joinList(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SafeVarargs
    private static List<String> distinctStrings(Object... values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null) {
            return List.of();
        }
        for (Object value : values) {
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    String text = item == null ? "" : String.valueOf(item).trim();
                    if (!text.isBlank()) {
                        out.add(text);
                    }
                }
                continue;
            }
            String text = value == null ? "" : String.valueOf(value).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsOneOf(String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (text.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int intValue(JsonNode node, String... paths) {
        for (String path : paths) {
            String text = text(node, path);
            if (text.isBlank()) {
                continue;
            }
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                // continue
            }
        }
        return 0;
    }

    private static String text(JsonNode node, String path) {
        JsonNode current = path(node, path);
        if (current.isMissingNode() || current.isNull()) {
            return "";
        }
        if (current.isTextual()) {
            return current.asText();
        }
        if (current.isNumber() || current.isBoolean()) {
            return current.asText();
        }
        return "";
    }

    private static JsonNode path(JsonNode node, String dottedPath) {
        JsonNode current = node;
        for (String segment : dottedPath.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
            current = current.path(segment);
        }
        return current;
    }

    private static List<String> listValue(JsonNode node, String path) {
        JsonNode current = path(node, path);
        if (current.isArray()) {
            List<String> out = new ArrayList<>();
            current.forEach(item -> {
                if (!item.isNull()) {
                    String text = item.asText();
                    if (!text.isBlank()) {
                        out.add(text);
                    }
                }
            });
            return out;
        }
        String text = current.isMissingNode() ? "" : current.asText("");
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("\\s*,\\s*"));
    }

    private static long parseEventTime(JsonNode node) {
        for (String path : List.of("timestamp", "@timestamp", "event.created", "data.timestamp", "eventTimeMs")) {
            String text = text(node, path);
            if (text.isBlank()) {
                continue;
            }
            Long parsed = parseTimeValue(text);
            if (parsed != null) {
                return parsed;
            }
        }
        return 0L;
    }

    private static Long parseTimeValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            long numeric = Long.parseLong(text);
            return numeric > 10_000_000_000L ? numeric : numeric * 1000L;
        } catch (NumberFormatException ignored) {
            // continue
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // continue
        }
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                if (formatter == DateTimeFormatter.ISO_OFFSET_DATE_TIME || formatter.toString().contains("Offset")) {
                    return OffsetDateTime.parse(text, formatter).toInstant().toEpochMilli();
                }
                return LocalDateTime.parse(text, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (Exception ignored) {
                // continue
            }
        }
        return null;
    }
}
