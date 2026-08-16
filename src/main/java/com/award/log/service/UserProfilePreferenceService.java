package com.award.log.service;

import java.util.Map;

public interface UserProfilePreferenceService {

    Map<String, Object> getEffectivePreference(Integer userId);

    Map<String, Object> savePreference(Integer userId, Map<String, Object> body);
}
