package com.award.log.controller;

import com.award.log.collector.CollectorDispatchScheduler;
import com.award.log.common.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "CollectorStatus", description = "非默认交付面：无挂载 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/api/collector")
public class CollectorStatusController {

    private final CollectorDispatchScheduler collectorDispatchScheduler;

    public CollectorStatusController(CollectorDispatchScheduler collectorDispatchScheduler) {
        this.collectorDispatchScheduler = collectorDispatchScheduler;
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(collectorDispatchScheduler.snapshot(), "获取采集器状态成功");
    }
}
