package com.award.log.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 交付友好线程池：固定上限，避免启动时「低内存占用」把 core 自适应到 32+ 导致线程风暴。
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    @Bean("sseTaskExecutor")
    public ThreadPoolTaskExecutor sseTaskExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(4, cores));
        executor.setMaxPoolSize(Math.min(8, cores * 2));
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("sse-executor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("SSE 线程池 core={} max={} queue=100", executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }

    @Bean("logAnalysisExecutor")
    public ThreadPoolTaskExecutor logAnalysisExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 与 log.analysis.max-concurrent-tasks 对齐，避免编排任务占满
        executor.setCorePoolSize(Math.min(4, cores));
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("LogProcess-Thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("日志分析线程池 core={} max=8 queue=50", executor.getCorePoolSize());
        return executor;
    }

    /**
     * 分片工作池：与编排池分离，避免自等待死锁。
     */
    @Bean("logAnalysisShardExecutor")
    public ThreadPoolTaskExecutor logAnalysisShardExecutor() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cores);
        executor.setMaxPoolSize(Math.min(cores * 2, 16));
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("LogShard-Thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("日志分片线程池 core={} max={} queue=100", executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }
}
