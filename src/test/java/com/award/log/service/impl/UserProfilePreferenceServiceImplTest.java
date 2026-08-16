package com.award.log.service.impl;

import com.award.log.mapper.UserProfilePreferenceMapper;
import com.award.log.model.UserProfilePreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfilePreferenceServiceImplTest {

    @Mock
    private UserProfilePreferenceMapper preferenceMapper;

    private UserProfilePreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserProfilePreferenceServiceImpl(preferenceMapper);
    }

    @Test
    void getEffectivePreferenceShouldApplyDefaultsWhenMissing() {
        Map<String, Object> prefs = service.getEffectivePreference(1);
        assertEquals(Boolean.FALSE, prefs.get("emailEnabled"));
        assertEquals(Boolean.TRUE, prefs.get("taskAlerts"));
    }

    @Test
    void getEffectivePreferenceShouldReadStoredValues() {
        UserProfilePreference stored = new UserProfilePreference();
        stored.setEmailEnabled(true);
        stored.setSmsEnabled(true);
        stored.setTaskAlerts(false);
        stored.setUpdateTime(LocalDateTime.of(2024, 1, 2, 10, 0));
        when(preferenceMapper.selectByUserId(2)).thenReturn(stored);

        Map<String, Object> prefs = service.getEffectivePreference(2);
        assertEquals(Boolean.TRUE, prefs.get("emailEnabled"));
        assertEquals(Boolean.FALSE, prefs.get("taskAlerts"));
        assertNotNull(prefs.get("updatedAt"));
    }

    @Test
    void savePreferenceShouldRejectNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.savePreference(null, Map.of("emailEnabled", true)));
    }

    @Test
    void savePreferenceShouldUpsertAndReturnEffective() {
        UserProfilePreference stored = new UserProfilePreference();
        stored.setUserId(3);
        stored.setEmailEnabled(true);
        stored.setSmsEnabled(false);
        stored.setTaskAlerts(false);
        when(preferenceMapper.selectByUserId(3)).thenReturn(stored);

        Map<String, Object> saved = service.savePreference(3, Map.of(
                "emailEnabled", "true",
                "smsEnabled", false,
                "taskAlerts", "false"));
        verify(preferenceMapper).upsert(any(UserProfilePreference.class));
        assertEquals(Boolean.TRUE, saved.get("emailEnabled"));
        assertEquals(Boolean.FALSE, saved.get("taskAlerts"));
    }
}
