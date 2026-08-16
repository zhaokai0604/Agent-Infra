package com.award.log.config;

import com.award.log.service.StorageStrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import jakarta.annotation.Resource;

/**
 * 定时任务配置类
 * 用于定期执行存储策略的归档和清理任务
 */
@Slf4j
@Configuration
@EnableScheduling
public class ScheduledTaskConfig {

    @Resource
    private StorageStrategyService storageStrategyService;

    /** 延时运维任务专用调度线程池（与 @Scheduled 默认调度器分离） */
    @Bean(name = "opsDeferredTaskScheduler")
    public ThreadPoolTaskScheduler opsDeferredTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ops-deferred-");
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 每天凌晨执行存储策略的归档和清理任务
     * 执行时间：每天 00:00:00
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void executeStorageArchiveTask() {
        log.info("[定时任务] 开始执行存储策略归档任务");
        try {
            storageStrategyService.executeScheduledArchive();
            log.info("[定时任务] 存储策略归档任务执行完成");
        } catch (Exception e) {
            log.error("[定时任务] 存储策略归档任务执行失败", e);
        }
    }

    /**
     * 每小时执行一次存储状态检查
     * 执行时间：每小时的第0分钟
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void checkStorageStatus() {
        log.debug("[定时任务] 开始执行存储状态检查");
        try {
            var stats = storageStrategyService.getStorageStats();
            log.info("[定时任务] 存储状态检查完成，总存储大小: {} bytes, 总日志数量: {}", 
                    stats.getTotalStorageSize(), stats.getTotalLogCount());
        } catch (Exception e) {
            log.error("[定时任务] 存储状态检查失败", e);
        }
    }
}
