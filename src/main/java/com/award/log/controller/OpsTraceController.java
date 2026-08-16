package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.OpsAuditTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维推理链路审计查询。
 */
@RestController
@RequestMapping("/api/ops-trace")
@RequiredArgsConstructor
public class OpsTraceController {

    private final OpsAuditTraceService opsAuditTraceService;

    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(opsAuditTraceService.listRecent(limit));
    }

    /** 按 traceId 拉取完整记录（含思维链 steps_json） */
    @GetMapping("/detail")
    public Result<Map<String, Object>> detail(@RequestParam String traceId) {
        Map<String, Object> row = opsAuditTraceService.findByTraceId(traceId);
        if (row == null || row.isEmpty()) {
            return Result.error(404, "未找到该 traceId 的审计记录");
        }
        return Result.success(row);
    }
}
