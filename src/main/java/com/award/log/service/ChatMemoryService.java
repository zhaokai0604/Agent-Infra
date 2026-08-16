package com.award.log.service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public interface ChatMemoryService {

    void addToMemory(String sessionId, MemoryEntry entry);

    List<MemoryEntry> getRecentMemory(String sessionId, int limit);

    List<MemoryEntry> searchMemory(String sessionId, String keyword);

    void clearMemory(String sessionId);

    MemorySummary getMemorySummary(String sessionId);

    int getMemorySize(String sessionId);

    class MemoryEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        private String role;
        private String content;
        private LocalDateTime timestamp;
        private String intent;
        private String toolUsed;
        private boolean isAnalysis;

        public MemoryEntry() {}

        public MemoryEntry(String role, String content) {
            this.role = role;
            this.content = content;
            this.timestamp = LocalDateTime.now();
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        public String getIntent() { return intent; }
        public void setIntent(String intent) { this.intent = intent; }
        public String getToolUsed() { return toolUsed; }
        public void setToolUsed(String toolUsed) { this.toolUsed = toolUsed; }
        public boolean isAnalysis() { return isAnalysis; }
        public void setAnalysis(boolean analysis) { isAnalysis = analysis; }
    }

    class MemorySummary implements Serializable {
        private static final long serialVersionUID = 1L;
        private int totalEntries;
        private int analysisCount;
        private String lastIntent;
        private String lastTopic;
        private LocalDateTime lastActivity;

        public int getTotalEntries() { return totalEntries; }
        public void setTotalEntries(int totalEntries) { this.totalEntries = totalEntries; }
        public int getAnalysisCount() { return analysisCount; }
        public void setAnalysisCount(int analysisCount) { this.analysisCount = analysisCount; }
        public String getLastIntent() { return lastIntent; }
        public void setLastIntent(String lastIntent) { this.lastIntent = lastIntent; }
        public String getLastTopic() { return lastTopic; }
        public void setLastTopic(String lastTopic) { this.lastTopic = lastTopic; }
        public LocalDateTime getLastActivity() { return lastActivity; }
        public void setLastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; }
    }
}