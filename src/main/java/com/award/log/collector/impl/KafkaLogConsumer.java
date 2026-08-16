package com.award.log.collector.impl;

import com.alibaba.fastjson.JSON;
import com.award.log.collector.model.RawLogEvent;
import com.award.log.service.impl.MvpStreamProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka日志消费者
 * 用于接收Kafka中的日志并进行处理
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "award.middleware", name = "kafka", havingValue = "true")
public class KafkaLogConsumer {

    private final MvpStreamProcessor mvpStreamProcessor;

    public KafkaLogConsumer(MvpStreamProcessor mvpStreamProcessor) {
        this.mvpStreamProcessor = mvpStreamProcessor;
    }

    @KafkaListener(topics = "${log.pipeline.kafka.raw-topic:${spring.kafka.template.default-topic}}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        try {
            RawLogEvent event = JSON.parseObject(message, RawLogEvent.class);
            if (event.getIngestTime() == null) {
                event.setIngestTime(System.currentTimeMillis());
            }
            mvpStreamProcessor.process(event);
        } catch (Exception ex) {
            log.warn("[Kafka] 消息非结构化，按原始文本处理: {}", ex.getMessage());
            RawLogEvent fallback = new RawLogEvent();
            fallback.setContent(message);
            fallback.setLevel("INFO");
            fallback.setIngestTime(System.currentTimeMillis());
            fallback.setEventTime(System.currentTimeMillis());
            fallback.setHost("unknown-host");
            mvpStreamProcessor.process(fallback);
        }
    }
}
