package com.award.log.service.impl;

import com.award.log.service.AiAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 分析服务：接入 Spring AI（DashScope 兼容接口），未配置密钥时不返回伪造结论。
 */
@Slf4j
@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private final ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public AiAnalysisServiceImpl(@Autowired(required = false) ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    private boolean aiReady() {
        return chatClient != null && apiKey != null && !apiKey.isBlank();
    }

    private String ask(String systemHint, String userContent) {
        if (!aiReady()) {
            return "AI 分析未就绪：请在环境变量或 application-local.yml 中配置 AI_API_KEY。";
        }
        try {
            return chatClient.prompt()
                    .system(systemHint)
                    .user(userContent)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("AI 分析请求失败: {}", e.getMessage());
            return "AI 分析请求失败：" + e.getMessage();
        }
    }

    @Override
    public String analyzeLog(String logContent) {
        return ask(
                "你是资深 Linux 运维工程师。根据用户提供的日志片段，指出异常模式、可能根因与可执行的排查/修复步骤。回答简洁、可落地，不要编造未出现的日志行。",
                "请分析以下日志：\n" + (logContent == null ? "" : logContent));
    }

    @Override
    public String analyzePerformance(String metricName, double value) {
        return ask(
                "你是系统性能分析专家。结合指标名称与当前数值，判断是否正常并给出运维建议。",
                String.format("指标 %s 当前值为 %.4f，请评估并给出建议。", metricName, value));
    }

    @Override
    public String analyzeAlert(String alertContent) {
        return ask(
                "你是告警处置专家。根据告警内容评估严重级别、影响面与优先处置步骤。",
                "告警内容：\n" + (alertContent == null ? "" : alertContent));
    }

    @Override
    public String generateReport(String period) {
        return ask(
                "你是运维报告撰写助手。基于给定时间范围，输出结构化的运行摘要（状态、风险、建议）。若缺少具体指标数据，请明确说明需补充哪些采集项，不要虚构数字。",
                "请生成过去 " + (period == null ? "24h" : period) + " 的系统运行分析报告。");
    }
}
