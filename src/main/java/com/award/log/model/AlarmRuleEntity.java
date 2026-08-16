package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AlarmRuleEntity {
    private Long id;
    private String name;
    private String description;
    private String ruleType;
    private String ruleExpression;
    private String severity;
    private String pushChannels;
    private Boolean enabled;
    private String createBy;
    private String updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
