package com.award.log.service.impl;

import com.award.log.mapper.UserApiKeyMapper;
import com.award.log.model.UserApiKey;
import com.award.log.service.UserApiKeyService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserApiKeyServiceImpl implements UserApiKeyService {

    private static final String SCOPE_BUNDLE = "PROFILE_READONLY_PLUS_INSIGHT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final int KEY_PREFIX_LEN = 20;

    private final UserApiKeyMapper userApiKeyMapper;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public UserApiKeyServiceImpl(UserApiKeyMapper userApiKeyMapper) {
        this.userApiKeyMapper = userApiKeyMapper;
    }

    @Override
    public List<Map<String, Object>> listForUser(Integer userId) {
        return userApiKeyMapper.selectByUserId(userId).stream()
                .map(this::toListView)
                .toList();
    }

    @Override
    public Map<String, Object> createForUser(Integer userId, String keyName) {
        ensureUserId(userId);
        String fullKey = generatePlainTextKey();
        UserApiKey entity = new UserApiKey();
        entity.setUserId(userId);
        entity.setKeyName(normalizeKeyName(keyName));
        entity.setKeyPrefix(prefixOf(fullKey));
        entity.setKeyHash(passwordEncoder.encode(fullKey));
        entity.setScopeBundle(SCOPE_BUNDLE);
        entity.setStatus(STATUS_ACTIVE);
        userApiKeyMapper.insert(entity);
        return createdPayload(entity, fullKey);
    }

    @Override
    public Map<String, Object> rotateForUser(Integer userId, Long keyId) {
        UserApiKey existing = ownedActiveKey(userId, keyId);
        String fullKey = generatePlainTextKey();
        LocalDateTime now = LocalDateTime.now();
        userApiKeyMapper.updateKeyMaterial(existing.getId(), prefixOf(fullKey), passwordEncoder.encode(fullKey), now);
        UserApiKey refreshed = userApiKeyMapper.selectById(existing.getId());
        return createdPayload(refreshed, fullKey);
    }

    @Override
    public Map<String, Object> revokeForUser(Integer userId, Long keyId) {
        UserApiKey existing = ownedKey(userId, keyId);
        if (STATUS_REVOKED.equalsIgnoreCase(existing.getStatus())) {
            return toListView(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        userApiKeyMapper.revoke(existing.getId(), now, now);
        return toListView(userApiKeyMapper.selectById(existing.getId()));
    }

    @Override
    public Map<String, Object> authenticate(String plainTextKey) {
        if (plainTextKey == null || plainTextKey.isBlank()) {
            return Map.of("success", false, "securityCode", "API_KEY_MISSING", "message", "API key missing");
        }
        String prefix = prefixOf(plainTextKey);
        List<UserApiKey> candidates = userApiKeyMapper.selectByPrefix(prefix);
        if (candidates.isEmpty()) {
            return Map.of("success", false, "securityCode", "API_KEY_INVALID", "message", "API key invalid");
        }
        for (UserApiKey key : candidates) {
            if (!passwordEncoder.matches(plainTextKey, key.getKeyHash())) {
                continue;
            }
            if (STATUS_REVOKED.equalsIgnoreCase(key.getStatus())) {
                return Map.of("success", false, "securityCode", "API_KEY_REVOKED", "message", "API key revoked");
            }
            if (!STATUS_ACTIVE.equalsIgnoreCase(key.getStatus())) {
                return Map.of("success", false, "securityCode", "API_KEY_INVALID", "message", "API key disabled");
            }
            LocalDateTime now = LocalDateTime.now();
            userApiKeyMapper.touchLastUsed(key.getId(), now, now);
            LinkedHashMap<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            ok.put("userId", key.getUserId());
            ok.put("scopeBundle", key.getScopeBundle());
            ok.put("keyId", key.getId());
            return ok;
        }
        return Map.of("success", false, "securityCode", "API_KEY_INVALID", "message", "API key invalid");
    }

    @Override
    public long countActiveForUser(Integer userId) {
        ensureUserId(userId);
        return userApiKeyMapper.countActiveByUserId(userId);
    }

    private Map<String, Object> createdPayload(UserApiKey entity, String plainTextKey) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>(toListView(entity));
        body.put("plainTextApiKey", plainTextKey);
        body.put("message", "API key created. This value is shown only once.");
        return body;
    }

    private Map<String, Object> toListView(UserApiKey entity) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("id", entity.getId());
        body.put("name", entity.getKeyName());
        body.put("keyName", entity.getKeyName());
        body.put("keyPrefix", entity.getKeyPrefix());
        body.put("scopeBundle", entity.getScopeBundle());
        body.put("status", entity.getStatus());
        body.put("lastUsedAt", entity.getLastUsedAt() == null ? null : entity.getLastUsedAt().toString());
        body.put("createdAt", entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        body.put("updatedAt", entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
        body.put("revokedAt", entity.getRevokedAt() == null ? null : entity.getRevokedAt().toString());
        return body;
    }

    private UserApiKey ownedActiveKey(Integer userId, Long keyId) {
        UserApiKey key = ownedKey(userId, keyId);
        if (!STATUS_ACTIVE.equalsIgnoreCase(key.getStatus())) {
            throw new IllegalArgumentException("API key is not active");
        }
        return key;
    }

    private UserApiKey ownedKey(Integer userId, Long keyId) {
        ensureUserId(userId);
        if (keyId == null) {
            throw new IllegalArgumentException("keyId cannot be null");
        }
        UserApiKey key = userApiKeyMapper.selectById(keyId);
        if (key == null || !userId.equals(key.getUserId())) {
            throw new IllegalArgumentException("API key not found");
        }
        return key;
    }

    private static void ensureUserId(Integer userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
    }

    private String generatePlainTextKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "uak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String prefixOf(String key) {
        String normalized = key == null ? "" : key.trim();
        return normalized.length() <= KEY_PREFIX_LEN ? normalized : normalized.substring(0, KEY_PREFIX_LEN);
    }

    private static String normalizeKeyName(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return "My API Key";
        }
        String normalized = keyName.trim();
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48);
        }
        return normalized;
    }
}
