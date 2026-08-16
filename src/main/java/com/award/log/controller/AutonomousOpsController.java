package com.award.log.controller;

import com.award.log.agent.OpsIntentRouter;
import com.award.log.agent.OpsRunResult;
import com.award.log.agent.OpsRuntimeService;
import com.award.log.common.Result;
import com.award.log.security.McpToolSurface;
import com.award.log.security.RequestUserResolver;
import com.award.log.security.RiskLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兼容旧“自主运维”入口，现统一收口到巡检自动修复单轨。
 */
@Tag(name = "巡检自动修复", description = "巡检内部/可选：见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/ops/autonomous")
@RequiredArgsConstructor
public class AutonomousOpsController {

    private final OpsRuntimeService opsRuntimeService;
    private final OpsIntentRouter opsIntentRouter;
    private final RequestUserResolver requestUserResolver;

    @Operation(summary = "触发一次巡检自动修复（兼容旧 autonomous run 入口）")
    @PostMapping("/run")
    public Result<Map<String, Object>> run(
            @RequestParam(defaultValue = "false") boolean forceRemediate,
            @RequestParam(defaultValue = "false") boolean readOnly,
            HttpServletRequest request) {
        if (requestUserResolver.currentUserId(request) == null) {
            return Result.error(401, "请先登录");
        }
        McpToolSurface surface = readOnly ? McpToolSurface.READ_ONLY : McpToolSurface.FULL;
        OpsRunResult result = opsRuntimeService.runScheduledAutonomous(surface, forceRemediate);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", result.traceId());
        body.put("markdown", result.markdown());
        body.put("report", result.report());
        body.put("mode", "PATROL_AUTOMATION");
        return Result.success(body);
    }

    @Operation(summary = "是否支持该自然语言意图走巡检自动修复单轨")
    @GetMapping("/supports")
    public Result<Map<String, Object>> supports(@RequestParam String message) {
        boolean ok = opsIntentRouter.resolve(message) == OpsIntentRouter.Playbook.PATROL_AUTOMATION;
        return Result.success(Map.of("supports", ok, "mode", "PATROL_AUTOMATION"));
    }
}
