package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.OpsEffectDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "运维效果评分")
@RestController
@RequestMapping("/api/ops/effect")
public class OpsEffectDashboardController {

    private final OpsEffectDashboardService opsEffectDashboardService;

    public OpsEffectDashboardController(OpsEffectDashboardService opsEffectDashboardService) {
        this.opsEffectDashboardService = opsEffectDashboardService;
    }

    @Operation(summary = "运维效果评分仪表板（审计/巡检/自愈聚合）")
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return Result.success(opsEffectDashboardService.buildDashboard(days));
    }
}
