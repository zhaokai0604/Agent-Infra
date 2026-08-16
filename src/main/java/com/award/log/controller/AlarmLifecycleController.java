package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.service.AlarmLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "Alarm Lifecycle", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/v1/alarm/lifecycle")
public class AlarmLifecycleController {

    private final AlarmLifecycleService alarmLifecycleService;

    public AlarmLifecycleController(AlarmLifecycleService alarmLifecycleService) {
        this.alarmLifecycleService = alarmLifecycleService;
    }

    @Operation(summary = "确认告警")
    @PostMapping("/{alarmId}/ack")
    public Result<Boolean> ack(@PathVariable String alarmId,
                               @RequestBody Map<String, String> body) {
        return Result.success(alarmLifecycleService.acknowledge(alarmId, body.getOrDefault("operator", "system")));
    }

    @Operation(summary = "处理告警")
    @PostMapping("/{alarmId}/handle")
    public Result<Boolean> handle(@PathVariable String alarmId,
                                  @RequestBody Map<String, String> body) {
        return Result.success(alarmLifecycleService.handle(alarmId, body.getOrDefault("operator", "system")));
    }

    @Operation(summary = "关闭告警")
    @PostMapping("/{alarmId}/close")
    public Result<Boolean> close(@PathVariable String alarmId,
                                 @RequestBody Map<String, String> body) {
        return Result.success(alarmLifecycleService.close(alarmId, body.getOrDefault("operator", "system")));
    }

    @Operation(summary = "设置静默窗口")
    @PostMapping("/silence")
    public Result<Map<String, Object>> silence(@RequestBody Map<String, String> body) {
        return Result.success(alarmLifecycleService.silenceWindow(body.get("startTime"), body.get("endTime")));
    }
}
