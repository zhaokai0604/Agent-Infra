package com.award.log.metrics;

import com.award.log.service.OpsPatrolService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 将巡检关联快照中的关键指标暴露给 Prometheus（配合 alerts.yml）。
 */
@Component
public class PatrolMetricsExporter {

    private final MeterRegistry meterRegistry;
    private final OpsPatrolService opsPatrolService;

    public PatrolMetricsExporter(MeterRegistry meterRegistry, OpsPatrolService opsPatrolService) {
        this.meterRegistry = meterRegistry;
        this.opsPatrolService = opsPatrolService;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("threshcore_patrol_zombie_processes", this, PatrolMetricsExporter::zombieCount)
                .description("最近一次巡检快照中的僵尸进程数")
                .register(meterRegistry);
        Gauge.builder("threshcore_disk_usage_percent", this, PatrolMetricsExporter::diskPct)
                .description("最近一次巡检快照中的磁盘使用率")
                .register(meterRegistry);
        Gauge.builder("threshcore_cpu_usage_percent", this, PatrolMetricsExporter::cpuPct)
                .description("最近一次巡检快照中的 CPU 使用率")
                .register(meterRegistry);
    }

    private double zombieCount() {
        return num(opsPatrolService.getLastCorrelationSnapshot().get("zombieProcesses"));
    }

    private double diskPct() {
        return num(opsPatrolService.getLastCorrelationSnapshot().get("diskUsagePct"));
    }

    private double cpuPct() {
        return num(opsPatrolService.getLastCorrelationSnapshot().get("cpuUsagePct"));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
