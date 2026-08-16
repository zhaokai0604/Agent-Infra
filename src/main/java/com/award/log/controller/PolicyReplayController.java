package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.effect.PolicyReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略回放与计划效果图裁决 API（安全终态能力）。
 */
@Tag(name = "安全策略回放")
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
public class PolicyReplayController {

    private final PolicyReplayService policyReplayService;

    @Operation(summary = "当前安全策略快照")
    @GetMapping("/policy-snapshot")
    public Result<Map<String, Object>> policySnapshot() {
        return Result.success(policyReplayService.currentPolicySnapshot());
    }

    @Operation(summary = "单工具策略回放")
    @PostMapping("/policy-replay")
    public Result<Map<String, Object>> replayTool(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return Result.error("请求体不能为空");
        }
        String toolName = stringVal(body.get("toolName"));
        if (toolName.isBlank()) {
            return Result.error("toolName 不能为空");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = body.get("parameters") instanceof Map<?, ?> m
                ? new HashMap<>((Map<String, Object>) m)
                : new HashMap<>();
        String userMessage = stringVal(body.get("userMessage"));
        String profile = stringVal(body.get("profile"));
        return Result.success(policyReplayService.replayTool(toolName, parameters, userMessage, profile));
    }

    @Operation(summary = "多步计划效果图裁决")
    @PostMapping("/policy-replay/plan")
    public Result<Map<String, Object>> replayPlan(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return Result.error("请求体不能为空");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = body.get("steps") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        return Result.success(policyReplayService.replayPlan(steps));
    }

    @Operation(summary = "治理覆盖前后对比")
    @PostMapping("/policy-replay/compare")
    public Result<Map<String, Object>> compare(@RequestBody Map<String, Object> body) {
        if (body == null) {
            return Result.error("请求体不能为空");
        }
        String toolName = stringVal(body.get("toolName"));
        if (toolName.isBlank()) {
            return Result.error("toolName 不能为空");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = body.get("parameters") instanceof Map<?, ?> m
                ? new HashMap<>((Map<String, Object>) m)
                : new HashMap<>();
        return Result.success(policyReplayService.compareTool(
                toolName,
                parameters,
                stringVal(body.get("userMessage")),
                stringVal(body.get("profile"))));
    }

    @Operation(summary = "按审计 traceId 回放")
    @GetMapping("/policy-replay/audit/{traceId}")
    public Result<Map<String, Object>> replayAudit(@PathVariable("traceId") String traceId) {
        Map<String, Object> out = policyReplayService.replayAudit(traceId);
        if (Boolean.FALSE.equals(out.get("success"))) {
            return Result.error(String.valueOf(out.getOrDefault("error", "回放失败")));
        }
        return Result.success(out);
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
