package com.award.log.service;

import java.util.List;
import java.util.Map;

public interface UserApiKeyService {

    List<Map<String, Object>> listForUser(Integer userId);

    Map<String, Object> createForUser(Integer userId, String keyName);

    Map<String, Object> rotateForUser(Integer userId, Long keyId);

    Map<String, Object> revokeForUser(Integer userId, Long keyId);

    Map<String, Object> authenticate(String plainTextKey);

    long countActiveForUser(Integer userId);
}
