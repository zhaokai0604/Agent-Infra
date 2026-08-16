package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserApiKey {
    private Long id;
    private Integer userId;
    private String keyName;
    private String keyPrefix;
    private String keyHash;
    private String scopeBundle;
    private String status;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime revokedAt;
}
