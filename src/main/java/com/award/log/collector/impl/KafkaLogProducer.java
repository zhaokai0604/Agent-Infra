package com.award.log.collector.impl;

import com.alibaba.fastjson.JSON;
import com.award.log.collector.model.RawLogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka日志生产者
 * 用于将采集到的日志发送到Kafka消息队列
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "award.middleware", name = "kafka", havingValue = "true")
public class KafkaLogProducer {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${log.pipeline.kafka.raw-topic:${spring.kafka.template.default-topic}}")
    private String logTopic;

    /**
     * 发送单条日志到Kafka
     * @param logContent 日志内容
     */
    public void sendLog(String logContent) {
        if (logContent == null || logContent.trim().isEmpty()) {
            return;
        }

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(logTopic, logContent);

        future.thenAccept(result -> {
            log.debug("[Kafka] 日志发送成功: {}", result.getRecordMetadata().offset());
        }).exceptionally(ex -> {
            log.error("[Kafka] 日志发送失败: {}", ex.getMessage());
            return null;
        });
    }

    /**
     * 批量发送日志到Kafka
     * @param logs 日志列表
     */
    public void sendLogs(List<String> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }

        for (String logContent : logs) {
            sendLog(logContent);
        }

        log.info("[Kafka] 批量发送日志完成，共 {} 条", logs.size());
    }

    /**
     * 发送结构化原始日志事件
     */
    public void sendRawEvent(RawLogEvent event) {
        if (event == null) {
            return;
        }
        sendLog(JSON.toJSONString(event));
    }

    /**
     * 批量发送结构化原始日志事件
     */
    public void sendRawEvents(List<RawLogEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (RawLogEvent event : events) {
            sendRawEvent(event);
        }
    }
}
