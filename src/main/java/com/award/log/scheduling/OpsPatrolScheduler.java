package com.award.log.scheduling;

import com.award.log.service.OpsPatrolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运维主动巡检定时入口（默认每 5 分钟，可通过 ops.patrol.cron 调整）。
 */
@Slf4j
@Component
public class OpsPatrolScheduler {

    private final OpsPatrolService opsPatrolService;

    @Value("${ops.patrol.enabled:true}")
    private boolean enabled;

    public OpsPatrolScheduler(OpsPatrolService opsPatrolService) {
        this.opsPatrolService = opsPatrolService;
    }

    @Scheduled(cron = "${ops.patrol.cron:0 */5 * * * ?}")
    public void runPatrol() {
        if (!enabled) {
            return;
        }
        try {
            opsPatrolService.runPatrolCycle();
        } catch (Exception e) {
            log.error("[运维巡检] 执行失败", e);
        }
    }
}
