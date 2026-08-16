package com.award.log.security.effect;

import java.util.UUID;

/**
 * 一次性写能力凭证：确认后签发，绑定效果指纹与请求方。
 */
public record WriteCapabilityToken(
        String tokenId,
        String effectFingerprint,
        String toolName,
        String requester,
        long issuedAtMs,
        long expiresAtMs
) {

    public static WriteCapabilityToken issue(
            String effectFingerprint,
            String toolName,
            String requester,
            long ttlMs) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(60_000L, ttlMs);
        return new WriteCapabilityToken(
                UUID.randomUUID().toString().replace("-", ""),
                effectFingerprint == null ? "" : effectFingerprint,
                toolName == null ? "" : toolName,
                requester == null || requester.isBlank() ? "anonymous" : requester.trim(),
                now,
                now + ttl);
    }

    public boolean matches(String presentedTokenId, String expectedFingerprint, String expectedRequester, long nowMs) {
        if (presentedTokenId == null || presentedTokenId.isBlank()) {
            return false;
        }
        if (!tokenId.equals(presentedTokenId.trim())) {
            return false;
        }
        if (nowMs > expiresAtMs) {
            return false;
        }
        if (expectedFingerprint != null && !expectedFingerprint.equals(effectFingerprint)) {
            return false;
        }
        String req = expectedRequester == null || expectedRequester.isBlank() ? "anonymous" : expectedRequester.trim();
        return requester.equals(req);
    }
}
