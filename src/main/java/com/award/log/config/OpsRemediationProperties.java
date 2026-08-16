package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 处置执行侧策略：扫描深度、占用文件跳过、大目录 atime 命中等。
 */
@Data
@ConfigurationProperties(prefix = "ops.remediation")
public class OpsRemediationProperties {

    /** 删除时跳过被占用文件，避免卡住 */
    private boolean skipLockedFiles = true;

    /** 清理扫描最大目录深度（从目标根算起） */
    private int maxScanDepth = 8;

    /** 目录体积超过该阈值（GiB）时，优先按最后访问时间筛选 */
    private double largeDirThresholdGb = 5.0;

    /** 大目录模式下：优先删除「最后访问时间」早于该天数的文件 */
    private int preferAccessDays = 2;

    /** 写权限探针上限（毫秒），超时或失败则 SKIP */
    private long writeProbeTimeoutMs = 50L;
}
