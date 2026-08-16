package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuleHitStat {
    private Long id;
    private String ruleId;
    private String ruleName;
    private Long hitCount;
    private Long missCount;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
    private LocalDateTime createTime;
}
