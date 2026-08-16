package com.award.log.service;

import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatAnalysisService {

    Flux<String> chat(String sessionId, String userMessage);

    Flux<String> analyzeStream(String sessionId, String userMessage);

    List<ChatMessage> getConversationHistory(String sessionId);

    void clearHistory(String sessionId);

    AnalysisResult analyze(String userMessage);

    class AnalysisResult {
        private String summary;
        private java.util.Map<String, Object> details;
        private String suggestedAction;

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public java.util.Map<String, Object> getDetails() { return details; }
        public void setDetails(java.util.Map<String, Object> details) { this.details = details; }
        public String getSuggestedAction() { return suggestedAction; }
        public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
    }

    class ChatMessage {
        private final String role;
        private final String content;
        private final long timestamp;
        private String intent;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }

        public ChatMessage(String role, String content, String intent) {
            this.role = role;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
            this.intent = intent;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
        public long getTimestamp() { return timestamp; }
        public String getIntent() { return intent; }
    }
}
