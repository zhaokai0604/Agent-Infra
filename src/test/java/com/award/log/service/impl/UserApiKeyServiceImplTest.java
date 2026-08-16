package com.award.log.service.impl;

import com.award.log.mapper.UserApiKeyMapper;
import com.award.log.model.UserApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserApiKeyServiceImplTest {

    @Mock
    private UserApiKeyMapper userApiKeyMapper;

    private UserApiKeyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserApiKeyServiceImpl(userApiKeyMapper);
    }

    @Test
    void createForUserShouldPersistHashedKeyAndReturnPlainTextOnce() {
        doAnswer(inv -> {
            UserApiKey key = inv.getArgument(0);
            key.setId(10L);
            return 1;
        }).when(userApiKeyMapper).insert(any());

        Map<String, Object> created = service.createForUser(1, "  prod key  ");

        assertTrue(created.containsKey("plainTextApiKey"));
        assertTrue(String.valueOf(created.get("plainTextApiKey")).startsWith("uak_"));
        assertEquals("prod key", created.get("name"));
        ArgumentCaptor<UserApiKey> captor = ArgumentCaptor.forClass(UserApiKey.class);
        verify(userApiKeyMapper).insert(captor.capture());
        assertNotEquals(captor.getValue().getKeyHash(), created.get("plainTextApiKey"));
    }

    @Test
    void authenticateShouldSucceedForMatchingActiveKey() {
        String plain = "uak_" + "A".repeat(32);
        UserApiKey stored = activeKey(1L, 1, plain);

        when(userApiKeyMapper.selectByPrefix(anyString())).thenReturn(List.of(stored));

        Map<String, Object> auth = service.authenticate(plain);

        assertTrue((Boolean) auth.get("success"));
        assertEquals(1, auth.get("userId"));
        verify(userApiKeyMapper).touchLastUsed(eq(1L), any(), any());
    }

    @Test
    void authenticateShouldRejectMissingBlankOrRevokedKeys() {
        assertEquals("API_KEY_MISSING", service.authenticate(" ").get("securityCode"));
        when(userApiKeyMapper.selectByPrefix(anyString())).thenReturn(List.of());
        assertEquals("API_KEY_INVALID", service.authenticate("uak_unknown").get("securityCode"));

        String plain = "uak_" + "B".repeat(32);
        UserApiKey revoked = activeKey(2L, 1, plain);
        revoked.setStatus("REVOKED");
        when(userApiKeyMapper.selectByPrefix(anyString())).thenReturn(List.of(revoked));
        assertEquals("API_KEY_REVOKED", service.authenticate(plain).get("securityCode"));
    }

    @Test
    void rotateForUserShouldUpdateKeyMaterial() {
        String plain = "uak_" + "C".repeat(32);
        UserApiKey key = activeKey(3L, 5, plain);
        UserApiKey refreshed = activeKey(3L, 5, plain);
        when(userApiKeyMapper.selectById(3L)).thenReturn(key, refreshed);

        Map<String, Object> rotated = service.rotateForUser(5, 3L);

        assertTrue(rotated.containsKey("plainTextApiKey"));
        verify(userApiKeyMapper).updateKeyMaterial(eq(3L), anyString(), anyString(), any());
    }

    @Test
    void revokeForUserShouldMarkKeyRevoked() {
        String plain = "uak_" + "C".repeat(32);
        UserApiKey key = activeKey(3L, 5, plain);
        UserApiKey revoked = activeKey(3L, 5, plain);
        revoked.setStatus("REVOKED");
        when(userApiKeyMapper.selectById(3L)).thenReturn(key, revoked);

        Map<String, Object> result = service.revokeForUser(5, 3L);

        assertEquals("REVOKED", result.get("status"));
        verify(userApiKeyMapper).revoke(eq(3L), any(), any());
    }

    @Test
    void listForUserAndCountActiveShouldDelegateToMapper() {
        UserApiKey key = activeKey(4L, 9, "uak_" + "D".repeat(32));
        when(userApiKeyMapper.selectByUserId(9)).thenReturn(List.of(key));
        when(userApiKeyMapper.countActiveByUserId(9)).thenReturn(1L);

        assertEquals(1, service.listForUser(9).size());
        assertEquals(1L, service.countActiveForUser(9));
    }

    private static UserApiKey activeKey(long id, int userId, String plain) {
        UserApiKey key = new UserApiKey();
        key.setId(id);
        key.setUserId(userId);
        key.setKeyName("test");
        key.setKeyPrefix(plain.length() <= 20 ? plain : plain.substring(0, 20));
        key.setKeyHash(new BCryptPasswordEncoder().encode(plain));
        key.setScopeBundle("PROFILE_READONLY_PLUS_INSIGHT");
        key.setStatus("ACTIVE");
        return key;
    }
}
