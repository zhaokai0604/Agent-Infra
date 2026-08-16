package com.award.log.agent.awm;

import com.award.log.service.OpsAuditTraceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpsExperienceLoader {

    private final OpsAuditTraceService opsAuditTraceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpsExperience loadByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> row = opsAuditTraceService.findByTraceId(traceId);
            if (row == null || row.isEmpty()) {
                return null;
            }
            return fromRow(row);
        } catch (Exception e) {
            log.debug("加载 trace 经验失败 traceId={}: {}", traceId, e.getMessage());
            return null;
        }
    }

    public List<OpsExperience> loadRecentSuccessful(int limit) {
        List<OpsExperience> out = new ArrayList<>();
        try {
            for (Map<String, Object> row : opsAuditTraceService.listRecentWithSteps(Math.max(1, limit * 3))) {
                OpsExperience exp = fromRow(row);
                if (exp != null && exp.executionOk()) {
                    out.add(exp);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("加载近期审计经验失败: {}", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    OpsExperience fromRow(Map<String, Object> row) {
        boolean ok = row.get("executionOk") instanceof Boolean b ? b
                : row.get("executionOk") instanceof Number n && n.intValue() == 1;
        List<Map<String, Object>> steps = row.containsKey("steps") && row.get("steps") instanceof List<?>
                ? (List<Map<String, Object>>) row.get("steps")
                : parseSteps(row.get("stepsJsonRaw"));
        return new OpsExperience(
                str(row.get("traceId")),
                str(row.get("userInput")),
                str(row.get("channel")),
                str(row.get("toolName")),
                str(row.get("securityOutcome")),
                ok,
                str(row.get("resultSummary")),
                steps,
                row.get("durationMs") instanceof Number n ? n.longValue() : 0L,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
