package com.award.log.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka可靠性配置：消费重试 + DLQ
 */
@Configuration
@ConditionalOnProperty(prefix = "award.middleware", name = "kafka", havingValue = "true")
public class KafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaDefaultErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${log.pipeline.kafka.dlq-topic:raw-logs-dlq}") String dlqTopic,
            @Value("${log.pipeline.kafka.retry-interval-ms:1000}") long retryIntervalMs,
            @Value("${log.pipeline.kafka.retry-max-attempts:3}") long retryAttempts) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) ->
                        new org.apache.kafka.common.TopicPartition(dlqTopic, record.partition()));

        return new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, retryAttempts));
    }
}
