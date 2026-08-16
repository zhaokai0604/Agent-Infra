package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.mapper.DecisionLogMapper;
import com.award.log.mapper.EngineOfflineMetricMapper;
import com.award.log.model.DecisionLog;
import com.award.log.model.EngineOfflineMetric;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Decision", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/v1/decision")
public class DecisionController {

    private final DecisionLogMapper decisionLogMapper;
    private final EngineOfflineMetricMapper engineOfflineMetricMapper;

    public DecisionController(DecisionLogMapper decisionLogMapper,
                              EngineOfflineMetricMapper engineOfflineMetricMapper) {
        this.decisionLogMapper = decisionLogMapper;
        this.engineOfflineMetricMapper = engineOfflineMetricMapper;
    }

    @Operation(summary = "决策解释")
    @GetMapping("/explain/{id}")
    public Result<DecisionLog> explain(@PathVariable String id) {
        return Result.success(decisionLogMapper.selectByDecisionId(id));
    }

    @Operation(summary = "决策统计")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        List<DecisionLog> latest = decisionLogMapper.selectByFilter(null, null, null, null, 0, 200);
        List<EngineOfflineMetric> metric = engineOfflineMetricMapper.selectLatest(10);
        double avgLatency = latest.stream().mapToLong(v -> v.getLatencyMs() == null ? 0 : v.getLatencyMs()).average().orElse(0);
        long alerts = latest.stream().filter(v -> v.getShouldAlert() != null && v.getShouldAlert() == 1).count();
        return Result.success(Map.of(
                "count", latest.size(),
                "avgLatencyMs", avgLatency,
                "alertRate", alerts / (double) Math.max(1, latest.size()),
                "offlineMetrics", metric
        ));
    }

    @Operation(summary = "决策追溯")
    @GetMapping("/trace/{decisionId}")
    public Result<DecisionLog> trace(@PathVariable String decisionId) {
        return Result.success(decisionLogMapper.selectByDecisionId(decisionId));
    }

    @Operation(summary = "查询历史决策")
    @GetMapping("/history")
    public Result<List<DecisionLog>> history(@RequestParam(required = false) String engineType,
                                             @RequestParam(required = false) String startTime,
                                             @RequestParam(required = false) String endTime,
                                             @RequestParam(required = false) Integer result,
                                             @RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(decisionLogMapper.selectByFilter(engineType, startTime, endTime, result, (pageNum - 1) * pageSize, pageSize));
    }
}
