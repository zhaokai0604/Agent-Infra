package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.signal.SecuritySignal;
import com.award.log.security.signal.SecuritySignalService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class SecuritySignalControllerTest {

    @Test
    void summaryAndRecentDelegatesToService() {
        SecuritySignalService service = Mockito.mock(SecuritySignalService.class);
        SecuritySignalController controller = new SecuritySignalController(service);
        when(service.summary()).thenReturn(Map.of("hasThreat", true));
        when(service.recentAsMaps(5)).thenReturn(List.of(Map.of("title", "a")));

        Result<Map<String, Object>> summary = controller.summary();
        Result<List<Map<String, Object>>> recent = controller.recent(5);

        assertEquals(true, summary.getData().get("hasThreat"));
        assertEquals(1, recent.getData().size());
    }

    @Test
    void ingestAndBatchReturnAcceptedSignals() {
        SecuritySignalService service = Mockito.mock(SecuritySignalService.class);
        SecuritySignalController controller = new SecuritySignalController(service);
        SecuritySignal signal = new SecuritySignal(
                "id-1", "NIDS", "alert", "title", "HIGH", 80, 0.9,
                "sensor-a", "host-a", "1.1.1.1", "2.2.2.2",
                "http", null, null, 1L, 2L, true, List.of("web"), "detail", "{}");
        when(service.ingest("suricata", Map.of("event_type", "alert"))).thenReturn(signal);
        when(service.ingestBatch("suricata", List.of(Map.of("event_type", "alert")))).thenReturn(List.of(signal));
        when(service.summary()).thenReturn(Map.of("hasThreat", true));

        Result<Map<String, Object>> single = controller.ingest(Map.of(
                "sourceHint", "suricata",
                "payload", Map.of("event_type", "alert")));
        Result<Map<String, Object>> batch = controller.ingestBatch(Map.of(
                "sourceHint", "suricata",
                "payloads", List.of(Map.of("event_type", "alert"))));

        assertEquals(true, single.getData().get("accepted"));
        assertEquals(1, batch.getData().get("accepted"));
    }
}
