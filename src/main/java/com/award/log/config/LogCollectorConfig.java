package com.award.log.config;

import com.award.log.collector.LogCollectorPathResolver;
import com.award.log.collector.LogCollectorManager;
import com.award.log.collector.impl.DatabaseLogCollector;
import com.award.log.collector.impl.FileLogCollector;
import com.award.log.collector.impl.KafkaLogProducer;
import com.award.log.collector.impl.NetworkLogCollector;
import com.award.log.collector.impl.NetworkLogCollectorV2;
import com.award.log.security.signal.SecuritySignalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import jakarta.annotation.PreDestroy;

import java.util.List;

/**
 * 日志采集器配置类
 * 用于初始化和管理日志采集器
 */
@Slf4j
@Configuration
public class LogCollectorConfig {

    private final KafkaLogProducer kafkaLogProducer;
    private final DataSource dataSource;
    private final SecuritySignalService securitySignalService;

    public LogCollectorConfig(@Autowired(required = false) KafkaLogProducer kafkaLogProducer,
                              DataSource dataSource,
                              SecuritySignalService securitySignalService) {
        this.kafkaLogProducer = kafkaLogProducer;
        this.dataSource = dataSource;
        this.securitySignalService = securitySignalService;
    }

    @Value("${log.collector.enabled:true}")
    private boolean enabled;

    @Value("${log.collector.file.max-depth:4}")
    private int maxDepth;

    @Value("${log.collector.file.poll-interval-ms:5000}")
    private int pollIntervalMs;

    @Value("${log.collector.file.path:auto}")
    private String filePath;

    @Value("${log.collector.file.include-extensions:.log,.txt,.out,.err,.debug,.info,.warn,.error,.fatal,.access,.audit,.trace}")
    private String includeExtensions;

    @Value("${log.collector.file.exclude-directories:node_modules,target,.git,.idea,.vscode,.cursor}")
    private String excludeDirectories;

    @Value("${log.collector.network.port:514}")
    private int networkPort;

    @Value("${log.collector.network.protocol:UDP}")
    private String networkProtocol;

    @Value("${log.collector.network.enabled:false}")
    private boolean networkEnabled;

    @Value("${log.collector.db.enabled:false}")
    private boolean dbEnabled;

    @Value("${log.pipeline.collector.buffer-max-lines:8000}")
    private int bufferMaxLines;

    @Value("${log.pipeline.collector.start-at-eof:true}")
    private boolean startAtEof;

    @Value("${log.collector.db.query:SELECT id, level, message, created_at FROM system_log WHERE id > {lastId} ORDER BY id ASC LIMIT 500}")
    private String dbQuery;

    @Value("${log.collector.db.checkpoint-file:}")
    private String dbCheckpointFile;

    @Bean
    public LogCollectorManager logCollectorManager() {
        LogCollectorManager collectorManager = new LogCollectorManager();
        if (kafkaLogProducer != null) {
            collectorManager.setKafkaLogProducer(kafkaLogProducer);
        } else {
            log.info("[采集器配置] Kafka 未启用，采集结果将不推送到 MQ");
        }
        
        if (enabled) {
            log.info("[采集器配置] 初始化日志采集器");

            // 初始化文件采集器（支持 auto 多根路径）
            List<String> roots = LogCollectorPathResolver.resolve(filePath);
            if (roots.isEmpty()) {
                log.warn("[采集器配置] 未解析到任何日志采集根路径");
            } else {
                log.info("[采集器配置] 文件采集模式: {}，根路径数: {}",
                        LogCollectorPathResolver.isAutoMode(filePath) ? "自动感知" : "手动指定",
                        roots.size());
                int idx = 0;
                for (String root : roots) {
                    try {
                        String collectorName = roots.size() == 1
                                ? "file-collector"
                                : "file-collector-" + (++idx);
                        FileLogCollector fileCollector = new FileLogCollector(
                                root, collectorName, includeExtensions, excludeDirectories,
                                maxDepth, pollIntervalMs, bufferMaxLines, startAtEof);
                        collectorManager.addCollector(fileCollector);
                        log.info("[采集器配置] 文件采集器 {} → {}", collectorName, root);
                    } catch (Exception e) {
                        log.error("[采集器配置] 初始化文件采集器失败 [{}]: {}", root, e.getMessage(), e);
                    }
                }
            }

            if (networkEnabled) {
                try {
                    if ("UDP".equalsIgnoreCase(networkProtocol)) {
                        collectorManager.addCollector(new NetworkLogCollectorV2(networkPort, networkProtocol, securitySignalService));
                    } else {
                        NetworkLogCollector networkCollector = new NetworkLogCollector(networkPort, networkProtocol, "network-collector");
                        collectorManager.addCollector(networkCollector);
                    }
                    log.info("[采集器配置] 网络采集器初始化成功，协议: {}, 端口: {}", networkProtocol, networkPort);
                } catch (Exception e) {
                    log.error("[采集器配置] 初始化网络采集器失败: {}", e.getMessage(), e);
                }
            }

            if (dbEnabled) {
                try {
                    DatabaseLogCollector dbCollector = new DatabaseLogCollector(dataSource, dbQuery, "db-collector", dbCheckpointFile);
                    collectorManager.addCollector(dbCollector);
                    log.info("[采集器配置] 数据库采集器初始化成功");
                } catch (Exception e) {
                    log.error("[采集器配置] 初始化数据库采集器失败: {}", e.getMessage(), e);
                }
            }

            // 启动所有采集器
            try {
                collectorManager.startAll();
                log.info("[采集器配置] 所有采集器启动成功，数量: {}", collectorManager.getCollectorCount());
            } catch (Exception e) {
                log.error("[采集器配置] 启动采集器失败: {}", e.getMessage(), e);
            }
        } else {
            log.info("[采集器配置] 日志采集器已禁用");
        }
        
        return collectorManager;
    }

    @PreDestroy
    public void destroyCollectors() {
        // No need to implement this since we're not storing a reference to the collectorManager
    }
}
