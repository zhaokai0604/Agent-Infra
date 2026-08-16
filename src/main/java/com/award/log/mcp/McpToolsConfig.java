package com.award.log.mcp;

import com.award.log.config.TtlConcurrentMapCacheManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
@EnableCaching
public class McpToolsConfig {

    public static final String CACHE_DISK_USAGE = "diskUsage";
    public static final String CACHE_SYSTEM_LOAD = "systemLoad";
    public static final String CACHE_PROCESS_LIST = "processList";
    public static final String CACHE_LOG_ANALYSIS = "logAnalysis";

    @Bean("mcpToolExecutor")
    public ThreadPoolTaskExecutor mcpToolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("mcp-tool-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("MCP Tools 线程池初始化完成，核心线程: 4，最大线程: 8");
        return executor;
    }

    @Bean("mcpCacheManager")
    public CacheManager cacheManager(
            @Value("${agent.mcp.cache-ttl-ms:45000}") long cacheTtlMs) {
        TtlConcurrentMapCacheManager cacheManager = new TtlConcurrentMapCacheManager(
                cacheTtlMs,
                CACHE_DISK_USAGE,
                CACHE_SYSTEM_LOAD,
                CACHE_PROCESS_LIST,
                CACHE_LOG_ANALYSIS
        );
        log.info("MCP Tools 缓存管理器初始化完成，TTL={}ms", cacheTtlMs);
        return cacheManager;
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean("mcpToolExecutorService")
    @Primary
    public ExecutorService executorService(@Qualifier("mcpToolExecutor") ThreadPoolTaskExecutor taskExecutor) {
        return taskExecutor.getThreadPoolExecutor();
    }
}
