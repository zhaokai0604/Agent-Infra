package com.award.log.websocket;

import com.award.log.analyzer.RealTimeLogAnalyzer;
import com.award.log.collector.LogCollectorManager;
import com.award.log.model.LogDocument;
import com.award.log.service.ElasticsearchService;
import com.award.log.service.LogAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 日志流WebSocket处理器
 * 用于实时传输日志数据到前端
 */
@Slf4j
@Component
public class LogStreamWebSocketHandler extends TextWebSocketHandler {

    // 存储活跃的WebSocket会话
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    
    // 线程池用于处理日志流
    private ExecutorService executorService;
    
    // 线程池核心大小
    private volatile int corePoolSize;
    
    // 内存使用率阈值
    private static final double MEMORY_THRESHOLD_HIGH = 0.8;
    private static final double MEMORY_THRESHOLD_MEDIUM = 0.6;
    private static final double MEMORY_THRESHOLD_LOW = 0.4;
    
    // 日志批处理大小
    private volatile int batchSize = 100;
    
    // 最大批处理大小
    private static final int MAX_BATCH_SIZE = 500;
    
    // 最小批处理大小
    private static final int MIN_BATCH_SIZE = 20;
    
    // 内存检查间隔（毫秒）
    private static final long MEMORY_CHECK_INTERVAL = 30000;
    
    // 日志收集器管理器
    @Autowired
    private LogCollectorManager logCollectorManager;
    
    // 日志分析服务
    @Autowired
    private LogAnalysisService logAnalysisService;
    
    // 实时日志分析器
    @Autowired
    private RealTimeLogAnalyzer realTimeLogAnalyzer;
    
    // Elasticsearch服务
    @Autowired(required = false)
    private ElasticsearchService elasticsearchService;

    // 标记实时分析任务是否已启动
    private static boolean realTimeAnalysisStarted = false;

    // 内存检查任务调度器
    private ScheduledExecutorService memoryCheckScheduler;

    @PostConstruct
    public void init() {
        // 初始化线程池
        adjustThreadPoolSize();
        
        // 注册自身到实时日志分析器的消息广播器
        try {
            realTimeLogAnalyzer.registerWebSocketHandler(this);
            log.info("已注册到实时日志分析器的消息广播器");
        } catch (Exception e) {
            log.warn("注册到实时日志分析器失败: {}", e.getMessage());
        }
        
        // 启动内存检查调度器
        startMemoryCheckScheduler();
    }

    @PreDestroy
    public void destroy() {
        // 关闭线程池
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // 关闭内存检查调度器
        if (memoryCheckScheduler != null) {
            memoryCheckScheduler.shutdown();
            try {
                if (!memoryCheckScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    memoryCheckScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                memoryCheckScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 检查当前内存使用情况
     */
    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (double) usedMemory / maxMemory;
    }

    /**
     * 调整线程池大小
     */
    private void adjustThreadPoolSize() {
        double memoryUsage = getMemoryUsage();
        int processors = Runtime.getRuntime().availableProcessors();
        int newCoreSize;
        
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，减少线程数
            newCoreSize = Math.max(1, processors / 2);
            log.info("内存使用率高 ({}), 线程池核心大小调整为: {}", String.format("%.2f", memoryUsage), newCoreSize);
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度线程数
            newCoreSize = Math.min(8, processors);
            log.info("内存使用率中等 ({}), 线程池核心大小调整为: {}", String.format("%.2f", memoryUsage), newCoreSize);
        } else {
            // 内存使用低，增加线程数
            newCoreSize = Math.min(16, processors * 2);
            log.info("内存使用率低 ({}), 线程池核心大小调整为: {}", String.format("%.2f", memoryUsage), newCoreSize);
        }
        
        // 只有当线程池大小发生变化时才重新创建
        if (corePoolSize != newCoreSize) {
            corePoolSize = newCoreSize;
            
            // 关闭旧线程池
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // 创建新线程池
            executorService = Executors.newFixedThreadPool(corePoolSize, new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "log-stream-executor-" + threadNumber.getAndIncrement());
                }
            });
            log.info("线程池已重新创建，核心大小: {}", corePoolSize);
        }
        
        // 调整批处理大小
        adjustBatchSize(memoryUsage);
    }

    /**
     * 调整批处理大小
     */
    private void adjustBatchSize(double memoryUsage) {
        int newBatchSize;
        
        if (memoryUsage > MEMORY_THRESHOLD_HIGH) {
            // 内存使用高，减少批处理大小
            newBatchSize = MIN_BATCH_SIZE;
            log.info("内存使用率高 ({}), 批处理大小调整为: {}", String.format("%.2f", memoryUsage), newBatchSize);
        } else if (memoryUsage > MEMORY_THRESHOLD_MEDIUM) {
            // 内存使用中等，适度批处理大小
            newBatchSize = 100;
            log.info("内存使用率中等 ({}), 批处理大小调整为: {}", String.format("%.2f", memoryUsage), newBatchSize);
        } else {
            // 内存使用低，增加批处理大小
            newBatchSize = MAX_BATCH_SIZE;
            log.info("内存使用率低 ({}), 批处理大小调整为: {}", String.format("%.2f", memoryUsage), newBatchSize);
        }
        
        batchSize = newBatchSize;
    }

    /**
     * 启动内存检查调度器
     */
    private void startMemoryCheckScheduler() {
        memoryCheckScheduler = Executors.newSingleThreadScheduledExecutor();
        memoryCheckScheduler.scheduleAtFixedRate(() -> {
            try {
                adjustThreadPoolSize();
            } catch (Exception e) {
                log.error("内存检查任务执行失败: {}", e.getMessage(), e);
            }
        }, 0, MEMORY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        log.info("内存检查调度器已启动，检查间隔: {}ms", MEMORY_CHECK_INTERVAL);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        activeSessions.put(sessionId, session);
        log.info("WebSocket连接建立: {}", sessionId);
        
        // 发送连接成功消息
        session.sendMessage(new TextMessage("{\"type\": \"connection\", \"message\": \"连接成功\", \"sessionId\": \"" + sessionId + "\"}"));
        
        // 启动日志流任务
        startLogStreamTask(sessionId);
        
        // 在第一个连接建立时启动实时分析任务
        if (!realTimeAnalysisStarted) {
            synchronized (LogStreamWebSocketHandler.class) {
                if (!realTimeAnalysisStarted) {
                    realTimeLogAnalyzer.startRealTimeAnalysis();
                    realTimeAnalysisStarted = true;
                    log.info("实时日志分析任务已启动");
                }
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();
        log.info("收到WebSocket消息: {} from {}", payload, sessionId);
        
        // 处理前端发送的消息
        // 这里可以根据消息类型执行不同的操作
        session.sendMessage(new TextMessage("{\"type\": \"echo\", \"message\": \"收到消息\", \"payload\": \"" + payload + "\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        activeSessions.remove(sessionId);
        log.info("WebSocket连接关闭: {}, 状态: {}", sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("WebSocket传输错误: {} from {}", exception.getMessage(), sessionId);
        
        // 发送错误消息
        if (session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\": \"error\", \"message\": \"传输错误: " + exception.getMessage() + "\"}"));
        }
    }

    /**
     * 启动日志流任务
     * @param sessionId WebSocket会话ID
     */
    private void startLogStreamTask(String sessionId) {
        executorService.submit(() -> {
            try {
                WebSocketSession session = activeSessions.get(sessionId);
                if (session == null || !session.isOpen()) {
                    return;
                }
                
                // 日志批处理缓冲区
                List<Object> logBatch = new ArrayList<>(batchSize);
                
                // 每1秒发送一次日志数据
                while (session.isOpen()) {
                    // 从 fan-out 队列取日志，不与调度器抢空采集器缓冲
                    var logs = logCollectorManager.takeFanOut();
                    
                    if (!logs.isEmpty()) {
                        // 批量处理日志
                        logBatch.addAll(logs);
                        
                        if (logBatch.size() >= batchSize) {
                            processLogBatch(session, logBatch);
                            logBatch.clear();
                        }
                    } else if (!logBatch.isEmpty()) {
                        // 如果没有新日志但缓冲区不为空，也处理一次
                        processLogBatch(session, logBatch);
                        logBatch.clear();
                    }
                    
                    // 休眠1秒
                    TimeUnit.SECONDS.sleep(1);
                }
                
                // 处理剩余日志
                if (!logBatch.isEmpty()) {
                    processLogBatch(session, logBatch);
                }
            } catch (Exception e) {
                log.error("日志流任务执行失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 处理日志批处理
     */
    private void processLogBatch(WebSocketSession session, List<Object> logBatch) {
        try {
            // 发送日志数据到前端
            String logData = "{\"type\": \"logs\", \"data\": " + com.alibaba.fastjson.JSON.toJSONString(logBatch) + "}";
            session.sendMessage(new TextMessage(logData));
            
            // 将日志添加到实时分析器的缓冲区
            try {
                // 解析日志数据并添加到分析器
                List<Map<String, Object>> parsedLogs = parseLogs(JSON.toJSONString(logBatch));
                for (Map<String, Object> logItem : parsedLogs) {
                    realTimeLogAnalyzer.addLog(logItem);
                    // 同时索引到Elasticsearch
                    try {
                        if (elasticsearchService != null) {
                            LogDocument logDocument = convertToLogDocument(logItem);
                            elasticsearchService.indexLog(logDocument);
                            LogStreamWebSocketHandler.this.log.debug("日志已索引到Elasticsearch: {}", logDocument.getId());
                        } else {
                            LogStreamWebSocketHandler.this.log.debug("Elasticsearch服务未启用，跳过日志索引");
                        }
                    } catch (Exception e) {
                        LogStreamWebSocketHandler.this.log.warn("索引日志到Elasticsearch失败: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("解析日志失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("处理日志批处理失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析日志数据
     * @param logs 日志数据字符串
     * @return 解析后的日志列表
     */
    private List<Map<String, Object>> parseLogs(String logs) {
        List<Map<String, Object>> parsedLogs = new ArrayList<>();
        try {
            // 解析JSON格式的日志数据
            JSONArray jsonArray = JSON.parseArray(logs);
            for (Object obj : jsonArray) {
                if (obj instanceof JSONObject) {
                    parsedLogs.add((Map<String, Object>) obj);
                } else if (obj instanceof String) {
                    // 如果是字符串，创建一个简单的日志对象
                    Map<String, Object> log = new HashMap<>();
                    log.put("content", obj);
                    log.put("level", "INFO");
                    log.put("timestamp", System.currentTimeMillis());
                    parsedLogs.add(log);
                }
            }
        } catch (Exception e) {
            log.warn("解析日志失败: {}", e.getMessage());
            // 创建一个错误日志对象
            Map<String, Object> errorLog = new HashMap<>();
            errorLog.put("content", "日志解析失败: " + logs);
            errorLog.put("level", "ERROR");
            errorLog.put("timestamp", System.currentTimeMillis());
            parsedLogs.add(errorLog);
        }
        return parsedLogs;
    }

    /**
     * 发送日志数据到所有活跃会话
     * @param logData 日志数据
     */
    public void broadcastLogData(String logData) {
        for (WebSocketSession session : activeSessions.values()) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(logData));
                } catch (Exception e) {
                    log.error("广播日志数据失败: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 获取活跃会话数量
     * @return 活跃会话数量
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
    
    /**
     * 将日志Map转换为LogDocument对象
     * @param log 日志数据
     * @return LogDocument对象
     */
    private LogDocument convertToLogDocument(Map<String, Object> log) {
        LogDocument document = new LogDocument();
        document.setId(java.util.UUID.randomUUID().toString());
        document.setTimestamp(java.time.LocalDateTime.now());
        
        // 设置日志级别
        String level = (String) log.get("level");
        if (level != null) {
            document.setSeverity(level);
        } else {
            document.setSeverity("INFO");
        }
        
        // 设置日志内容
        String content = (String) log.get("content");
        if (content != null) {
            document.setContent(content);
        } else if (log.get("message") != null) {
            document.setContent((String) log.get("message"));
        } else {
            document.setContent("无内容");
        }
        
        // 设置日志来源
        document.setProtocol("WEBSOCKET_STREAM");
        
        // 设置异常标记
        boolean isAnomaly = false;
        if (level != null) {
            level = level.toUpperCase();
            isAnomaly = level.equals("ERROR") || level.equals("WARN") || level.equals("FATAL");
        }
        if (!isAnomaly && content != null) {
            content = content.toLowerCase();
            isAnomaly = content.contains("error") || content.contains("exception") || 
                       content.contains("fail") || content.contains("warn") ||
                       content.contains("fatal") || content.contains("critical");
        }
        document.setAnomaly(isAnomaly);
        
        return document;
    }
}
