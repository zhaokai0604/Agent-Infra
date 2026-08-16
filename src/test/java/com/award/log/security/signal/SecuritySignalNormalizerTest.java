package com.award.log.security.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecuritySignalNormalizerTest {

    private final SecuritySignalNormalizer normalizer = new SecuritySignalNormalizer(new ObjectMapper());

    @Test
    void normalizeSuricataAlert() {
        String payload = """
                {
                  "timestamp":"2026-06-17T10:15:30+08:00",
                  "event_type":"alert",
                  "src_ip":"10.0.0.2",
                  "dest_ip":"10.0.0.10",
                  "app_proto":"http",
                  "alert":{
                    "signature":"ET WEB_SERVER Suspicious request",
                    "category":"Web Attack",
                    "severity":1,
                    "action":"blocked"
                  }
                }
                """;

        SecuritySignal signal = normalizer.normalize("suricata", payload, 1_000L);

        assertEquals("NIDS", signal.sourceType());
        assertEquals("CRITICAL", signal.severity());
        assertTrue(signal.blocked());
        assertEquals("10.0.0.2", signal.srcIp());
        assertTrue(signal.tags().contains("Web Attack"));
    }

    @Test
    void normalizeWazuhSysmonPayload() {
        Map<String, Object> payload = Map.of(
                "agent", Map.of("id", "001", "name", "host-a"),
                "rule", Map.of(
                        "id", "100002",
                        "level", 12,
                        "description", "Sysmon process creation",
                        "groups", java.util.List.of("sysmon", "process_creation")),
                "data", Map.of(
                        "win", Map.of(
                                "system", Map.of("eventID", "1"),
                                "eventdata", Map.of(
                                        "Image", "C:\\\\Windows\\\\System32\\\\cmd.exe",
                                        "CommandLine", "cmd.exe /c whoami"))));

        SecuritySignal signal = normalizer.normalize("sysmon", payload, 2_000L);

        assertEquals("PIDS", signal.sourceType());
        assertEquals("HIGH", signal.severity());
        assertEquals("host-a", signal.host());
        assertTrue(signal.processName().contains("cmd.exe"));
        assertTrue(signal.tags().contains("sysmon"));
    }

    @Test
    void normalizeGenericPayloadFallsBackGracefully() {
        SecuritySignal signal = normalizer.normalize("ids", Map.of(
                "title", "Unknown network anomaly",
                "severity", "warning",
                "srcIp", "192.168.0.2"), 3_000L);

        assertEquals("NIDS", signal.sourceType());
        assertEquals("MEDIUM", signal.severity());
        assertEquals("Unknown network anomaly", signal.title());
    }
}
