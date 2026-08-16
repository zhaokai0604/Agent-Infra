package com.award.log.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ModelEvaluation {
    private Long id;
    private String modelVersion;
    private Integer sampleSize;
    private Double accuracy;
    private Double precisionScore;
    private Double recallScore;
    private Double f1Score;
    private Double rocAuc;
    private Double prAuc;
    private String confusionMatrix;
    private LocalDateTime createTime;
}
