package com.award.log.service.mcp;

import com.award.log.security.effect.ToolEffect;
import com.award.log.security.effect.WriteCapabilityToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpPendingConfirmationService {

    private static final long MIN_TTL_MS = 60_000L;

    private final ConcurrentHashMap<String, PendingConfirmation> pending = new ConcurrentHashMap<>();

    @Value("${agent.security.confirmation-ttl-ms:900000}")
    private long confirmationTtlMs;

    public PendingConfirmation register(
            String confirmationId,
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String userInstruction,
            String requester) {
        return register(confirmationId, toolName, parameters, userMessage, userInstruction, requester, null, "", null);
    }

    public PendingConfirmation register(
            String confirmationId,
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String userInstruction,
            String requester,
            ToolEffect effect,
            String effectFingerprint,
            WriteCapabilityToken capabilityToken) {
        purgeExpired();
        long now = System.currentTimeMillis();
        long ttl = Math.max(MIN_TTL_MS, confirmationTtlMs);
        WriteCapabilityToken token = capabilityToken != null
                ? capabilityToken
                : WriteCapabilityToken.issue(
                        effectFingerprint == null ? "" : effectFingerprint,
                        toolName,
                        requester,
                        ttl);
        PendingConfirmation value = new PendingConfirmation(
                confirmationId,
                toolName,
                immutableCopy(parameters),
                normalizeText(userMessage),
                normalizeText(userInstruction),
                normalizeRequester(requester),
                now,
                now + ttl,
                effect,
                effectFingerprint == null ? "" : effectFingerprint,
                token);
        pending.put(confirmationId, value);
        return value;
    }

    public TakeResult take(String confirmationId, String requester) {
        return take(confirmationId, requester, null);
    }

    /**
     * @param presentedCapabilityToken 客户端回传的能力凭证；为空时仅校验确认快照归属（兼容旧前端）。
     */
    public TakeResult take(String confirmationId, String requester, String presentedCapabilityToken) {
        if (confirmationId == null || confirmationId.isBlank()) {
            return new TakeResult(null, TakeStatus.NOT_FOUND);
        }
        long now = System.currentTimeMillis();
        String normalizedRequester = normalizeRequester(requester);
        Holder holder = new Holder();
        pending.compute(confirmationId.trim(), (key, existing) -> {
            if (existing == null) {
                holder.status = TakeStatus.NOT_FOUND;
                return null;
            }
            if (existing.isExpired(now)) {
                holder.status = TakeStatus.EXPIRED;
                return null;
            }
            if (!existing.requester().equals(normalizedRequester)) {
                holder.status = TakeStatus.REQUESTER_MISMATCH;
                return existing;
            }
            if (presentedCapabilityToken != null && !presentedCapabilityToken.isBlank()) {
                WriteCapabilityToken token = existing.capabilityToken();
                if (token == null || !token.matches(
                        presentedCapabilityToken,
                        existing.effectFingerprint(),
                        normalizedRequester,
                        now)) {
                    holder.status = TakeStatus.TOKEN_MISMATCH;
                    return existing;
                }
            }
            holder.status = TakeStatus.OK;
            holder.pending = existing;
            return null;
        });
        return new TakeResult(holder.pending, holder.status);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    private static String normalizeRequester(String requester) {
        if (requester == null || requester.isBlank()) {
            return "anonymous";
        }
        return requester.trim();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    public enum TakeStatus {
        OK,
        NOT_FOUND,
        EXPIRED,
        REQUESTER_MISMATCH,
        TOKEN_MISMATCH
    }

    public record TakeResult(PendingConfirmation pending, TakeStatus status) {
    }

    public record PendingConfirmation(
            String confirmationId,
            String toolName,
            Map<String, Object> parameters,
            String userMessage,
            String userInstruction,
            String requester,
            long createdAtMs,
            long expiresAtMs,
            ToolEffect effect,
            String effectFingerprint,
            WriteCapabilityToken capabilityToken) {

        boolean isExpired(long nowMs) {
            return nowMs > expiresAtMs;
        }
    }

    private static final class Holder {
        private PendingConfirmation pending;
        private TakeStatus status = TakeStatus.NOT_FOUND;
    }
}
