package com.award.log.service.impl;



import com.award.log.agent.OpsReportFormat;

import com.award.log.mapper.LogAnalysisDetailMapper;

import com.award.log.mapper.LogAnalysisTaskMapper;

import com.award.log.model.LogAnalysisTask;

import com.award.log.service.AiTool;

import com.award.log.service.StatisticsService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;



import java.time.format.DateTimeFormatter;

import java.util.HashMap;

import java.util.List;

import java.util.Map;



@Slf4j

@Component

public class GenerateReportTool implements AiTool {



    private final StatisticsService statisticsService;

    private final LogAnalysisTaskMapper taskMapper;

    private final LogAnalysisDetailMapper detailMapper;



    public GenerateReportTool(

            StatisticsService statisticsService,

            LogAnalysisTaskMapper taskMapper,

            LogAnalysisDetailMapper detailMapper) {

        this.statisticsService = statisticsService;

        this.taskMapper = taskMapper;

        this.detailMapper = detailMapper;

    }



    @Override

    public String getName() {

        return "generate_report";

    }



    @Override

    public String getDescription() {

        return "生成近 N 日日志分析汇总报告：任务趋势、异常分布、近期任务列表。";

    }



    @Override

    public String getParameterDescription() {

        return "{\"days\":\"统计窗口天数，默认7\"}";

    }



    @Override

    public Map<String, Object> getFunctionSchema() {

        Map<String, Object> schema = new HashMap<>();

        schema.put("name", getName());

        schema.put("description", getDescription());

        Map<String, Object> days = new HashMap<>();

        days.put("type", "integer");

        days.put("description", "统计窗口天数，默认7");

        days.put("default", 7);

        schema.put("parameters", Map.of("days", days));

        return schema;

    }



    @Override

    public ToolResult execute(Map<String, Object> parameters) {

        int days = 7;

        Object rawDays = parameters != null ? parameters.get("days") : null;

        if (rawDays != null) {

            try {

                days = Math.max(1, Math.min(30, Integer.parseInt(String.valueOf(rawDays))));

            } catch (NumberFormatException ignored) {

            }

        }



        try {

            Map<String, Object> summary = statisticsService.getRecentLogSummary(days);

            Map<String, Object> anomaly = statisticsService.getAnomalyLogStatistics(days);

            Map<String, Object> tasks = statisticsService.getTaskStatusStatistics();

            long detailCount = detailMapper.countSinceDays(days);



            StringBuilder md = new StringBuilder();

            md.append("## 日志分析汇总报告 (近 ").append(days).append(" 天)\n\n");

            md.append(OpsReportFormat.tableHeaderLine("指标", "数值"));

            md.append("| 分析任务数 | `").append(summary.getOrDefault("totalTasks", 0)).append("` |\n");

            md.append("| 明细条数 | `").append(detailCount).append("` |\n");

            md.append("| 异常日志 | `").append(anomaly.getOrDefault("totalAnomalyLogs", 0)).append("` |\n\n");



            md.append("### 任务状态\n\n");

            Object statusCounts = tasks.get("statusCount");

            if (statusCounts instanceof Map<?, ?> map && !map.isEmpty()) {

                md.append(OpsReportFormat.tableHeaderLine("状态", "数量"));

                for (Map.Entry<?, ?> entry : map.entrySet()) {

                    md.append("| `").append(entry.getKey()).append("` | `")

                            .append(entry.getValue()).append("` |\n");

                }

            } else {

                md.append("- 暂无任务统计\n");

            }



            md.append("\n### 近期任务\n\n");

            List<LogAnalysisTask> recent = taskMapper.selectPage(0, 5);

            if (recent.isEmpty()) {

                md.append("- 暂无任务记录\n");

            } else {

                md.append(OpsReportFormat.tableHeaderLine("文件", "状态", "创建时间"));

                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

                for (LogAnalysisTask t : recent) {

                    md.append("| `").append(t.getFileName()).append("` | `")

                            .append(t.getStatus()).append("` | `")

                            .append(t.getCreateTime() != null ? t.getCreateTime().format(fmt) : "N/A")

                            .append("` |\n");

                }

            }



            Map<String, Object> data = new HashMap<>();

            data.put("days", days);

            data.put("summary", summary);

            data.put("anomaly", anomaly);

            data.put("tasks", tasks);

            return ToolResult.success(md.toString(), data);

        } catch (Exception e) {

            log.error("生成报告失败", e);

            return ToolResult.error("生成报告失败: " + e.getMessage());

        }

    }

}


