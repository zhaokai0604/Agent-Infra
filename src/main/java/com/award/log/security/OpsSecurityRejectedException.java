package com.award.log.security;

/**
 *工具执行前安全护栏拒绝（与 HTTP 层拦截语义一致，便于统一处理）。
 */
public class OpsSecurityRejectedException extends RuntimeException {

    public OpsSecurityRejectedException(String message) {
        super(message);
    }
}
