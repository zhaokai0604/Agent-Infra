package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LogTemplateRecord {
    private Long id;
    private String templateId;
    private String templateName;
    private String templateContent;
    private String severity;
    private Long useCount;
    private LocalDateTime lastSeenTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
