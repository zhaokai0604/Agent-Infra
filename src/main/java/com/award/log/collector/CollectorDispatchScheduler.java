package com.award.log.collector;

import com.award.log.collector.impl.KafkaLogProducer;
import com.award.log.collector.model.RawLogEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采集层调度器：从采集器拉取日志并推送到Kafka原始Topic
 */
@Slf4j
@Component
public class CollectorDispatchScheduler {

    private final LogCollectorManager logCollectorManager;
    private final KafkaLogProducer kafkaLogProducer;

    public CollectorDispatchScheduler(LogCollectorManager logCollectorManager,
            @Autowired(required = false) KafkaLogProducer kafkaLogProducer) {
        this.logCollectorManager = logCollectorManager;
        this.kafkaLogProducer = kafkaLogProducer;
    }
    private final AtomicLong offsetGenerator = new AtomicLong(0);
    private final AtomicLong totalCollected = new AtomicLong(0);
    private final AtomicLong totalDispatched = new AtomicLong(0);
    private final AtomicLong lastBatchSize = new AtomicLong(0);
    private final AtomicLong lastDispatchTime = new AtomicLong(0);

    @Value("${log.pipeline.collector.dispatch-enabled:true}")
    private boolean dispatchEnabled;

    @Value("${log.pipeline.collector.source-id:file-watcher}")
    private String sourceId;

    @Scheduled(fixedDelayString = "${log.pipeline.collector.dispatch-interval-ms:1000}")
    public void dispatch() {
        if (!dispatchEnabled) {
            return;
        }
        // 始终由调度器单点 drain；Kafka 未装配时仍 fan-out，供 WebSocket 消费
        List<String> collected = logCollectorManager.collectAll();
        totalCollected.addAndGet(collected.size());
        if (collected.isEmpty()) {
            lastBatchSize.set(0);
            return;
        }

        if (kafkaLogProducer == null) {
            lastBatchSize.set(collected.size());
            lastDispatchTime.set(System.currentTimeMillis());
            return;
        }

        List<RawLogEvent> events = new ArrayList<>(collected.size());
        long now = System.currentTimeMillis();
        for (String line : collected) {
            RawLogEvent event = new RawLogEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setSourceId(sourceId);
            event.setSourceType("FILE");
            event.setHost(localHost());
            event.setOffset(offsetGenerator.incrementAndGet());
            event.setIngestTime(now);
            event.setEventTime(now);
            event.setLevel(detectLevel(line));
            event.setContent(line);
            events.add(event);
        }
        kafkaLogProducer.sendRawEvents(events);
        totalDispatched.addAndGet(events.size());
        lastBatchSize.set(events.size());
        lastDispatchTime.set(now);
        log.debug("[采集调度] 推送到Kafka完成，数量: {}", events.size());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("dispatchEnabled", dispatchEnabled);
        snapshot.put("sourceId", sourceId);
        snapshot.put("totalCollected", totalCollected.get());
        snapshot.put("totalDispatched", totalDispatched.get());
        snapshot.put("lastBatchSize", lastBatchSize.get());
        snapshot.put("lastDispatchTime", lastDispatchTime.get());
        snapshot.put("collectorStatus", logCollectorManager.getCollectorStatus());
        snapshot.put("collectorCount", logCollectorManager.getCollectorCount());
        return snapshot;
    }

    private String localHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String detectLevel(String line) {
        String lower = line == null ? "" : line.toLowerCase();
        if (lower.contains("fatal")) {
            return "FATAL";
        }
        if (lower.contains("error") || lower.contains("exception")) {
            return "ERROR";
        }
        if (lower.contains("warn")) {
            return "WARN";
        }
        if (lower.contains("debug")) {
            return "DEBUG";
        }
        return "INFO";
    }
}
