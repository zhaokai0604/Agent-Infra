package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EngineOfflineMetric {
    private Long id;
    private String engineType;
    private Integer sampleSize;
    private Integer falsePositive;
    private Integer falseNegative;
    private Double precisionScore;
    private Double recallScore;
    private Double f1Score;
    private LocalDateTime createTime;
}
