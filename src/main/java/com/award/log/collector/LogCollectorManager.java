package com.award.log.collector;

import com.award.log.collector.impl.KafkaLogProducer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 日志采集器管理器
 * 用于统一管理多个日志采集器实例
 */
@Slf4j
public class LogCollectorManager {

    private static final int FAN_OUT_CAPACITY = 10_000;

    private final Map<String, LogCollector> collectors;
    private KafkaLogProducer kafkaLogProducer;
    /** WebSocket 等次级消费者读此队列，不与调度器抢空采集器缓冲 */
    private final ConcurrentLinkedQueue<String> fanOutQueue = new ConcurrentLinkedQueue<>();

    public LogCollectorManager() {
        this.collectors = new ConcurrentHashMap<>();
    }

    public void setKafkaLogProducer(KafkaLogProducer kafkaLogProducer) {
        this.kafkaLogProducer = kafkaLogProducer;
    }

    /**
     * 添加采集器
     * @param collector 日志采集器实例
     */
    public void addCollector(LogCollector collector) {
        if (collector == null) {
            log.warn("[采集器管理器] 无效的采集器实例");
            return;
        }
        String name = collector.getName();
        if (collectors.containsKey(name)) {
            log.warn("[采集器管理器] 采集器名称已存在: {}", name);
            return;
        }
        collectors.put(name, collector);
        log.info("[采集器管理器] 添加采集器: {}", name);
    }

    /**
     * 移除采集器
     * @param name 采集器名称
     */
    public void removeCollector(String name) {
        LogCollector collector = collectors.remove(name);
        if (collector != null) {
            collector.stop();
            log.info("[采集器管理器] 移除采集器: {}", name);
        }
    }

    /**
     * 启动所有采集器
     */
    public void startAll() {
        log.info("[采集器管理器] 启动所有采集器");
        collectors.values().forEach(LogCollector::start);
    }

    /**
     * 停止所有采集器
     */
    public void stopAll() {
        log.info("[采集器管理器] 停止所有采集器");
        collectors.values().forEach(LogCollector::stop);
    }

    /**
     * 从各采集器 drain 一次，并将副本 fan-out 给 WebSocket 等订阅方。
     * 仅应由调度器（主消费方）调用，避免多方 collect 抢空。
     */
    public List<String> collectAll() {
        List<String> allLogs = new ArrayList<>();
        collectors.values().forEach(collector -> {
            if (collector.isRunning()) {
                List<String> logs = collector.collect();
                allLogs.addAll(logs);
            }
        });
        publishFanOut(allLogs);
        return allLogs;
    }

    /**
     * WebSocket 等次级消费：只读 fan-out，不清空采集器内部缓冲。
     */
    public List<String> takeFanOut() {
        List<String> out = new ArrayList<>();
        String line;
        while ((line = fanOutQueue.poll()) != null) {
            out.add(line);
        }
        return out;
    }

    private void publishFanOut(List<String> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        for (String line : logs) {
            while (fanOutQueue.size() >= FAN_OUT_CAPACITY) {
                fanOutQueue.poll();
            }
            fanOutQueue.offer(line);
        }
    }

    /**
     * 获取采集器状态
     * @return 采集器状态映射
     */
    public Map<String, Boolean> getCollectorStatus() {
        Map<String, Boolean> statusMap = new ConcurrentHashMap<>();
        collectors.forEach((name, collector) -> {
            statusMap.put(name, collector.isRunning());
        });
        return statusMap;
    }

    /**
     * 获取采集器数量
     * @return 采集器数量
     */
    public int getCollectorCount() {
        return collectors.size();
    }

    /**
     * 获取指定采集器
     * @param name 采集器名称
     * @return 采集器实例
     */
    public LogCollector getCollector(String name) {
        return collectors.get(name);
    }
}
