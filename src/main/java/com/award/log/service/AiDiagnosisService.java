package com.award.log.service;

import com.award.log.dto.EnhancedLogParseResultEntity;
import java.util.List;

import reactor.core.publisher.Flux;

public interface AiDiagnosisService {
    /**
     * 根据异常日志生成诊断报告
     * @param anomalies 异常日志列表
     * @return 诊断结果文本
     */
    String generateDiagnosis(List<EnhancedLogParseResultEntity> anomalies);

    /**
     * 基于完整解析结果构造上下文（模板多样 + 正常对照 + 时间序），生成诊断报告。
     */
    String generateDiagnosisFromFullResult(List<EnhancedLogParseResultEntity> fullResult);

    /**
     * 对单条日志进行快速诊断（同步返回）
     * @param logEntry 单条日志实体
     * @return 诊断结果文本
     */
    String diagnoseSingleLog(EnhancedLogParseResultEntity logEntry);

    /**
     * 流式生成诊断报告
     * @param anomalies 异常日志列表
     * @return 诊断结果流
     */
    Flux<String> generateDiagnosisStream(List<EnhancedLogParseResultEntity> anomalies);

    /**
     * 基于完整解析结果的流式诊断（与 {@link #generateDiagnosisFromFullResult} 同源上下文）。
     */
    Flux<String> generateDiagnosisStreamFromFullResult(List<EnhancedLogParseResultEntity> fullResult);

    /**
     * 对单条日志进行快速诊断
     * @param logEntry 单条日志实体
     * @return 诊断结果流
     */
    Flux<String> generateSingleLogDiagnosisStream(EnhancedLogParseResultEntity logEntry);

    /**
     * 通用 AI 运维问诊对话
     * @param userMessage 用户输入
     * @return 回复流
     */
    Flux<String> chatStream(String userMessage);
}
