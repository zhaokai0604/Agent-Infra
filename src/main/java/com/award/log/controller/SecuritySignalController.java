package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.signal.SecuritySignal;
import com.award.log.security.signal.SecuritySignalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "安全信号接入", description = "非默认交付面：安全信号采集面（ingest 可用）/ 无默认 UI，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/security/signals")
public class SecuritySignalController {

    private final SecuritySignalService securitySignalService;

    public SecuritySignalController(SecuritySignalService securitySignalService) {
        this.securitySignalService = securitySignalService;
    }

    @Operation(summary = "接收单条 IDS/PIDS/HIDS 安全信号")
    @PostMapping("/ingest")
    public Result<Map<String, Object>> ingest(@RequestBody Map<String, Object> body) {
        String sourceHint = stringValue(body.get("sourceHint"));
        Object payload = body.containsKey("payload") ? body.get("payload") : body;
        SecuritySignal signal = securitySignalService.ingest(sourceHint, payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("signal", signal.toMap());
        out.put("summary", securitySignalService.summary());
        return Result.success(out);
    }

    @Operation(summary = "批量接收 IDS/PIDS/HIDS 安全信号")
    @PostMapping("/ingest-batch")
    public Result<Map<String, Object>> ingestBatch(@RequestBody Map<String, Object> body) {
        String sourceHint = stringValue(body.get("sourceHint"));
        List<?> payloads = body.get("payloads") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> signals = securitySignalService.ingestBatch(sourceHint, payloads)
                .stream()
                .map(SecuritySignal::toMap)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", signals.size());
        out.put("signals", signals);
        out.put("summary", securitySignalService.summary());
        return Result.success(out);
    }

    @Operation(summary = "查看最近接收的安全信号")
    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> recent(@RequestParam(name = "n", defaultValue = "20") int limit) {
        return Result.success(securitySignalService.recentAsMaps(limit));
    }

    @Operation(summary = "查看当前安全信号摘要")
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.success(securitySignalService.summary());
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
