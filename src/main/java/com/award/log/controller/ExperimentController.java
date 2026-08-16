package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.ExperimentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Experiment", description = "非默认交付面：实验能力未完成落库/采样，接口诚实返回不可用")
@RestController
@RequestMapping("/api/v1/experiment")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @Operation(summary = "启动实验（当前未实现真实采样）")
    @PostMapping("/start")
    public Result<Boolean> start(@RequestBody(required = false) Map<String, String> body) {
        return Result.error(501, "A/B 实验采样未实现，请勿当作可用能力");
    }

    @Operation(summary = "停止实验（当前未实现真实采样）")
    @PostMapping("/stop")
    public Result<Boolean> stop() {
        return Result.error(501, "A/B 实验采样未实现，请勿当作可用能力");
    }

    @Operation(summary = "实验报告（当前未实现真实采样）")
    @GetMapping("/report")
    public Result<Map<String, Object>> report() {
        Map<String, Object> stub = experimentService.report();
        return Result.error(501, "A/B 实验报告始终无真实 sample/diff，当前状态: " + stub);
    }
}
