package com.award.log.service;

import com.award.log.model.LogDocument;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步批量写入 Elasticsearch，避免阻塞实时分析线程。
 */
@Slf4j
@Component
public class LogDocumentAsyncIndexer {

    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    @Value("${log.elasticsearch.async.enabled:true}")
    private boolean enabled;

    @Value("${log.elasticsearch.async.batch-size:100}")
    private int batchSize;

    @Value("${log.elasticsearch.async.flush-interval-ms:5000}")
    private long flushIntervalMs;

    @Value("${log.elasticsearch.async.queue-capacity:10000}")
    private int queueCapacity;

    private BlockingQueue<LogDocument> queue;
    private ExecutorService consumer;
    private volatile boolean running = true;
    private final AtomicLong dropped = new AtomicLong();

    @PostConstruct
    void start() {
        if (elasticsearchService == null || !enabled) {
            log.info("LogDocumentAsyncIndexer 未启用（无 ES 或 log.elasticsearch.async.enabled=false）");
            return;
        }
        queue = new LinkedBlockingQueue<>(Math.max(1000, queueCapacity));
        consumer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "es-log-async-indexer");
            t.setDaemon(true);
            return t;
        });
        consumer.submit(this::consumeLoop);
        log.info("LogDocumentAsyncIndexer 已启动 batchSize={} flushIntervalMs={}", batchSize, flushIntervalMs);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (consumer != null) {
            consumer.shutdown();
            try {
                consumer.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
    }

    public void enqueue(LogDocument document) {
        if (document == null || elasticsearchService == null || !enabled || queue == null) {
            return;
        }
        if (!queue.offer(document)) {
            dropped.incrementAndGet();
            log.warn("ES 异步队列已满，丢弃文档 id={}（累计丢弃 {}）", document.getId(), dropped.get());
        }
    }

    private void consumeLoop() {
        List<LogDocument> batch = new ArrayList<>(batchSize);
        long lastFlush = System.currentTimeMillis();
        while (running) {
            try {
                LogDocument doc = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (doc != null) {
                    batch.add(doc);
                }
                queue.drainTo(batch, Math.max(0, batchSize - batch.size()));
                long now = System.currentTimeMillis();
                boolean due = !batch.isEmpty()
                        && (batch.size() >= batchSize || now - lastFlush >= flushIntervalMs);
                if (due) {
                    flushBatch(batch);
                    batch.clear();
                    lastFlush = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("ES 异步索引循环异常: {}", e.getMessage());
            }
        }
        flushRemaining();
    }

    private void flushRemaining() {
        if (queue == null || elasticsearchService == null) {
            return;
        }
        List<LogDocument> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flushBatch(rest);
        }
    }

    private void flushBatch(List<LogDocument> batch) {
        try {
            int n = elasticsearchService.bulkIndexLogs(batch);
            log.debug("ES 异步批量索引 {} 条（请求 {} 条）", n, batch.size());
        } catch (Exception e) {
            log.warn("ES 异步批量索引失败: {}", e.getMessage());
        }
    }
}
