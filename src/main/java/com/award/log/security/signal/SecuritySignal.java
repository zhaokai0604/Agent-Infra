package com.award.log.security.signal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SecuritySignal(
        String signalId,
        String sourceType,
        String eventType,
        String title,
        String severity,
        int severityScore,
        double confidence,
        String sensorId,
        String host,
        String srcIp,
        String dstIp,
        String serviceName,
        String processName,
        String filePath,
        long eventTimeMs,
        long ingestTimeMs,
        boolean blocked,
        List<String> tags,
        String detail,
        String rawPayload) {

    public boolean isCritical() {
        return severityScore >= 90 || "CRITICAL".equalsIgnoreCase(severity);
    }

    public boolean isHighOrAbove() {
        return severityScore >= 70
                || "HIGH".equalsIgnoreCase(severity)
                || "CRITICAL".equalsIgnoreCase(severity);
    }

    public long effectiveTimeMs() {
        return eventTimeMs > 0 ? eventTimeMs : ingestTimeMs;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signalId", signalId);
        out.put("sourceType", sourceType);
        out.put("eventType", eventType);
        out.put("title", title);
        out.put("severity", severity);
        out.put("severityScore", severityScore);
        out.put("confidence", confidence);
        out.put("sensorId", sensorId);
        out.put("host", host);
        out.put("srcIp", srcIp);
        out.put("dstIp", dstIp);
        out.put("serviceName", serviceName);
        out.put("processName", processName);
        out.put("filePath", filePath);
        out.put("eventTimeMs", eventTimeMs);
        out.put("ingestTimeMs", ingestTimeMs);
        out.put("blocked", blocked);
        out.put("tags", tags);
        out.put("detail", detail);
        out.put("rawPayload", rawPayload);
        return out;
    }
}
