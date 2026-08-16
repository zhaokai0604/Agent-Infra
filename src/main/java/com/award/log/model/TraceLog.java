package com.award.log.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TraceLog {
    private String traceId;
    private String userInput;
    private String riskLevel;
    private String toolName;
    private String resultSummary;
    private long durationMs;
    private LocalDateTime timestamp;
}