package com.award.log.knowledge;

import java.util.List;

/**
 * 内置运维 Runbook，冷启动写入知识库，保证无手工上传时 RAG 仍可检索。
 */
public final class KnowledgeSeedData {

    public record Entry(String title, String category, String content) {}

    private KnowledgeSeedData() {
    }

    public static List<Entry> all() {
        return List.of(
                diskCleanup(),
                cpuPressure(),
                failedService(),
                logSpike(),
                tempCleanup()
        );
    }

    private static Entry diskCleanup() {
        return new Entry(
                "磁盘空间不足处置",
                "disk",
                """
                ## 现象
                工作盘使用率超过 85%，或巡检报告 diskHotspots 出现大目录。

                ## 排查步骤
                1. 使用 DiskInsightTool 查看各挂载点使用率与大目录热点。
                2. 使用 LogAnalysisTool 扫描 /var/log 或项目 logs 目录，确认是否日志膨胀。
                3. 检查临时目录 /tmp、C:/Users/*/AppData/Local/Temp 是否堆积安装包或缓存。

                ## 处置建议
                - 优先清理临时目录与过期日志（CleanTempTool / CleanLogTool），默认 dry-run 预览。
                - 禁止直接删除系统目录、数据库数据目录或未知大文件。
                - 清理后再次执行 DiskInsightTool 验证使用率下降。
                """
        );
    }

    private static Entry cpuPressure() {
        return new Entry(
                "CPU 占用过高排查",
                "cpu",
                """
                ## 现象
                CPU 持续高于 80%，或用户反馈系统卡顿。

                ## 排查步骤
                1. ProcessTool 列出 Top 进程，关注异常高 CPU 的 PID 与命令行。
                2. 检查是否存在僵尸进程或大量短生命周期任务。
                3. 结合本机负载 WebSocket 数据确认是否为采集周期内均值突增。

                ## 处置建议
                - 只读诊断优先，确认进程归属后再决定是否重启服务。
                - 服务重启须命中 service-restart allowlist 且经二次确认。
                - 若为应用 bug 导致忙循环，记录 PID 与日志片段后交开发处理。
                """
        );
    }

    private static Entry failedService() {
        return new Entry(
                "systemd 失败单元诊断",
                "service",
                """
                ## 现象
                存在 failed systemd unit，或服务无法启动。

                ## 排查步骤
                1. SystemdTool 列出 failed 单元与最近状态。
                2. 使用 LogAnalysisTool 读取 journal 或 /var/log 下对应服务日志。
                3. 核对服务名是否在 agent.service-restart.allowlist 内。

                ## 处置建议
                - 配置错误：修正配置文件后 reload，再 restart（须确认）。
                - 端口冲突：ProcessTool 查占用端口进程。
                - 依赖未就绪：先恢复数据库/网络，再重启应用服务。
                """
        );
    }

    private static Entry logSpike() {
        return new Entry(
                "日志异常突增分析",
                "log",
                """
                ## 现象
                告警显示 ERROR/FATAL 条数突增，或 Drain 模板 ID 新增异常簇。

                ## 排查步骤
                1. 上传或指定路径日志，执行 Drain-Plus 模板聚类。
                2. 关注 anomalyScore 高的条目与 Linux/容器解析字段。
                3. 检索 knowledge 中同类历史案例（RAG similarHistoricalCases）。

                ## 处置建议
                - 区分单次部署错误与持续性故障（看时间分布）。
                - 结合 TraceId / 调用链字段定位上游服务。
                - 修复根因前避免批量自动重启。
                """
        );
    }

    private static Entry tempCleanup() {
        return new Entry(
                "临时目录安全清理",
                "cleanup",
                """
                ## 范围
                白名单：/tmp、/var/tmp、C:/Windows/Temp、用户 Local/Temp 等 agent.paths.clean 配置路径。

                ## 执行原则
                1. 一律先 dryRun=true 预览将删除的文件数量与示例路径。
                2. days 参数建议 7 天以上，避免误删正在使用的临时文件。
                3. 确认后再 dryRun=false 执行；全程写入 ops_audit_trace。

                ## 禁止
                - 清理系统盘 Windows、Program Files、数据库 data 目录。
                - 在未确认业务停机窗口时清理应用运行中产生的 lock 文件。
                """
        );
    }
}
