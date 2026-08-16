package com.award.log.service;

import lombok.Data;
import java.util.List;

public interface IntentRecognitionService {

    enum Intent {
        QUERY_ANOMALIES,
        QUERY_ERRORS,
        QUERY_BY_TIME,
        DIAGNOSE_ISSUE,
        GENERATE_REPORT,
        STATISTICS,
        HELP,
        UNKNOWN
    }

    @Data
    class RecognitionResult {
        private Intent intent;
        private String originalQuery;
        private String refinedQuery;
        private TimeRange timeRange;
        private List<String> keywords;
        private String confidence;
        private String targetService;

        public RecognitionResult(Intent intent, String originalQuery) {
            this.intent = intent;
            this.originalQuery = originalQuery;
            this.confidence = "HIGH";
        }
    }

    @Data
    class TimeRange {
        private String startTime;
        private String endTime;
        private String duration;

        public TimeRange(String startTime, String endTime, String duration) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = duration;
        }
    }

    RecognitionResult recognize(String userQuery);
}