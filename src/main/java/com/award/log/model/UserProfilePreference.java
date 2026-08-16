package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserProfilePreference {
    private Integer userId;
    private Boolean emailEnabled;
    private Boolean smsEnabled;
    private Boolean taskAlerts;
    private LocalDateTime updateTime;
}
