package com.award.log.security;

/**
 * 运维写操作的信任档位：决定是否在策略内自动执行，而非一律弹窗确认。
 */
public enum OpsTrustTier {
    /** 风险分低于 auto 阈值：预览通过后可直接执行 */
    AUTO,
    /** 风险分低于 notify 阈值：执行并在会话中通报 */
    NOTIFY,
    /** 需用户明确跟进（如回复「执行清理」） */
    APPROVE,
    /** 超过 confirm 上限：拒绝 */
    BLOCK
}
