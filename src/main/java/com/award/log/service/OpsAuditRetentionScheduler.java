package com.award.log.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期清理过期运维审计记录，防止 ops_audit_trace 表无限膨胀。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAuditRetentionScheduler {

    private final OpsAuditTraceService opsAuditTraceService;

    @Value("${audit.retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "${audit.cleanup-cron:0 0 3 * * ?}")
    public void purgeExpiredTraces() {
        int days = Math.min(365, Math.max(7, retentionDays));
        int deleted = opsAuditTraceService.deleteOlderThanDays(days);
        if (deleted > 0) {
            log.info("审计清理完成：删除 {} 条超过 {} 天的记录", deleted, days);
        }
    }
}
