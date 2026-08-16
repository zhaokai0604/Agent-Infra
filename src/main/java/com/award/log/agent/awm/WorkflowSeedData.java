package com.award.log.agent.awm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内置 seed workflow（答辩/demo 冷启动，对齐 AWM offline）。
 */
public final class WorkflowSeedData {

    private WorkflowSeedData() {
    }

    public static List<OpsWorkflow> allSeeds() {
        List<OpsWorkflow> all = new ArrayList<>();
        all.addAll(diskWorkflows());
        all.addAll(cpuWorkflows());
        all.addAll(serviceWorkflows());
        return all;
    }

    public static List<OpsWorkflow> serviceWorkflows() {
        OpsWorkflow diagnose = new OpsWorkflow(
                "failed-service-diagnose",
                "service",
                List.of("FAILED_SERVICE"),
                "失败 systemd 单元诊断",
                "存在 failed unit 时，先用 SystemdTool 列出失败服务，确认是否在重启白名单内。",
                List.of(
                        OpsWorkflowStep.of(
                                "systemd 存在 failed unit",
                                "只读列出失败单元与状态",
                                "SystemdTool"),
                        OpsWorkflowStep.of(
                                "需确认是否可重启",
                                "对照 agent.service-restart.allowlist",
                                "ServiceRestartTool",
                                Map.of("serviceName", "{service-name}", "dryRun", "true"))
                ),
                "seed",
                null,
                0,
                true
        );

        OpsWorkflow remediate = new OpsWorkflow(
                "failed-service-whitelist-restart",
                "service",
                List.of("FAILED_SERVICE"),
                "白名单内失败服务重启",
                "OpsTrustPolicy 允许且服务在白名单：ServiceRestartTool 先 dryRun 预览，确认后执行并复采 systemd。",
                List.of(
                        OpsWorkflowStep.of(
                                "failed unit 且 service 在白名单",
                                "预览重启影响",
                                "ServiceRestartTool",
                                Map.of("serviceName", "{service-name}", "dryRun", "true")),
                        OpsWorkflowStep.of(
                                "用户已确认或策略 AUTO/NOTIFY",
                                "执行重启",
                                "ServiceRestartTool",
                                Map.of("serviceName", "{service-name}", "dryRun", "false")),
                        OpsWorkflowStep.of(
                                "重启后验证",
                                "复采 failed 列表",
                                "SystemdTool")
                ),
                "seed",
                null,
                0,
                true
        );

        return List.of(diagnose, remediate);
    }

    public static List<OpsWorkflow> cpuWorkflows() {
        OpsWorkflow diagnose = new OpsWorkflow(
                "cpu-pressure-diagnose",
                "cpu",
                List.of("CPU_HIGH"),
                "CPU 负载诊断（只读）",
                "CPU 或负载偏高时，先采集 SystemLoadTool，再用 ProcessTool 定位高占用进程；无白名单时不自动 restart。",
                List.of(
                        OpsWorkflowStep.of(
                                "CPU 或系统负载 > {cpuWarnPercent}%",
                                "确认 CPU/内存/负载指标，避免误判瞬时尖峰",
                                "SystemLoadTool"),
                        OpsWorkflowStep.of(
                                "需要定位占用来源",
                                "列出高 CPU/内存进程（只读）",
                                "ProcessTool",
                                Map.of("minCpu", "5.0", "minMem", "5.0"))
                ),
                "seed",
                null,
                0,
                true
        );

        OpsWorkflow restart = new OpsWorkflow(
                "cpu-pressure-whitelist-restart",
                "cpu",
                List.of("CPU_HIGH", "FAILED_SERVICE"),
                "CPU 压力下的白名单服务重启",
                "仅在用户明确要求重启且服务在 allowlist 内时：预览 ServiceRestartTool → 确认 → 执行。",
                List.of(
                        OpsWorkflowStep.of(
                                "CPU 偏高且用户要求重启",
                                "先采集负载与进程，确认非误报",
                                "SystemLoadTool"),
                        OpsWorkflowStep.of(
                                "已定位可疑服务",
                                "预览重启（dryRun=true）",
                                "ServiceRestartTool",
                                Map.of("serviceName", "{service-name}", "dryRun", "true")),
                        OpsWorkflowStep.of(
                                "OpsTrustPolicy 允许且用户已确认",
                                "执行重启并提示人工验证业务",
                                "ServiceRestartTool",
                                Map.of("serviceName", "{service-name}", "dryRun", "false"))
                ),
                "seed",
                null,
                0,
                true
        );

        return List.of(diagnose, restart);
    }

    public static List<OpsWorkflow> diskWorkflows() {
        OpsWorkflow diagnose = new OpsWorkflow(
                "disk-pressure-diagnose",
                "disk",
                List.of("DISK_PRESSURE", "LOG_ANOMALY"),
                "磁盘压力诊断",
                "根分区或数据盘使用率偏高时，先只读采集分区分布并扫描热点目录，再决定清理目标。",
                List.of(
                        OpsWorkflowStep.of(
                                "根分区或挂载点使用率 > {threshold}%",
                                "确认各分区占用，避免误删系统盘关键路径",
                                "DiskTool"),
                        OpsWorkflowStep.of(
                                "已确认存在磁盘压力",
                                "定位大目录/大文件候选（只读）",
                                "DiskAnalyzeTool",
                                Map.of("path", "{log-path}", "includeSubdirs", "true", "topN", "12"))
                ),
                "seed",
                null,
                0,
                true
        );

        OpsWorkflow remediate = new OpsWorkflow(
                "disk-pressure-remediation",
                "disk",
                List.of("DISK_PRESSURE"),
                "磁盘压力释放（预览→执行→验证）",
                "在 OpsTrustPolicy 允许时：临时目录与日志根先 dryRun 预览，确认后执行删除并复采 df。",
                List.of(
                        OpsWorkflowStep.of(
                                "磁盘压力已确认",
                                "采集当前分区占用基线",
                                "DiskTool"),
                        OpsWorkflowStep.of(
                                "需要定位清理目标",
                                "扫描热点目录",
                                "DiskAnalyzeTool",
                                Map.of("path", "{log-path}", "topN", "12")),
                        OpsWorkflowStep.of(
                                "临时目录存在可回收文件",
                                "预览临时文件清理（dryRun=true）",
                                "CleanTempTool",
                                Map.of("path", "{temp-path}", "days", "{temp-days}", "dryRun", "true")),
                        OpsWorkflowStep.of(
                                "日志根存在陈旧日志",
                                "预览日志裁剪（dryRun=true）",
                                "LogCleanupTool",
                                Map.of("path", "{log-path}", "days", "{log-days}", "dryRun", "true")),
                        OpsWorkflowStep.of(
                                "用户已确认或策略为 AUTO/NOTIFY",
                                "执行写操作后复采验证",
                                "DiskTool")
                ),
                "seed",
                null,
                0,
                true
        );

        return List.of(diagnose, remediate);
    }
}
