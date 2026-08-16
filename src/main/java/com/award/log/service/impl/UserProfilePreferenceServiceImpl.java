package com.award.log.service.impl;

import com.award.log.mapper.UserProfilePreferenceMapper;
import com.award.log.model.UserProfilePreference;
import com.award.log.service.UserProfilePreferenceService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserProfilePreferenceServiceImpl implements UserProfilePreferenceService {

    private final UserProfilePreferenceMapper preferenceMapper;

    public UserProfilePreferenceServiceImpl(UserProfilePreferenceMapper preferenceMapper) {
        this.preferenceMapper = preferenceMapper;
    }

    @Override
    public Map<String, Object> getEffectivePreference(Integer userId) {
        UserProfilePreference preference = userId == null ? null : preferenceMapper.selectByUserId(userId);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("emailEnabled", preference != null && Boolean.TRUE.equals(preference.getEmailEnabled()));
        out.put("smsEnabled", preference != null && Boolean.TRUE.equals(preference.getSmsEnabled()));
        out.put("taskAlerts", preference == null || !Boolean.FALSE.equals(preference.getTaskAlerts()));
        out.put("updatedAt", preference == null || preference.getUpdateTime() == null ? null : preference.getUpdateTime().toString());
        return out;
    }

    @Override
    public Map<String, Object> savePreference(Integer userId, Map<String, Object> body) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        UserProfilePreference preference = new UserProfilePreference();
        preference.setUserId(userId);
        preference.setEmailEnabled(asBoolean(body.get("emailEnabled"), false));
        preference.setSmsEnabled(asBoolean(body.get("smsEnabled"), false));
        preference.setTaskAlerts(asBoolean(body.get("taskAlerts"), true));
        preferenceMapper.upsert(preference);
        return getEffectivePreference(userId);
    }

    private static Boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
