package com.award.log.service.impl;

import com.award.log.service.ChatMemoryService;
import com.award.log.service.ChatMemoryService.MemoryEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private final Map<String, List<MemoryEntry>> sessionMemories = new ConcurrentHashMap<>();
    private static final int MAX_MEMORY_SIZE = 100;

    @Autowired
    private ChatSessionRetention sessionRetention;

    @Override
    public void addToMemory(String sessionId, MemoryEntry entry) {
        sessionRetention.touch(sessionId);
        List<MemoryEntry> memory = sessionMemories.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        memory.add(entry);
        trim(memory, MAX_MEMORY_SIZE);
        log.debug("记忆已添加至会话 {}: role={}, content长度={}", sessionId, entry.getRole(), entry.getContent().length());
    }

    @Override
    public List<MemoryEntry> getRecentMemory(String sessionId, int limit) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, List.of());
        if (memory.isEmpty()) {
            return List.of();
        }
        int size = Math.min(limit, memory.size());
        return List.copyOf(memory.subList(memory.size() - size, memory.size()));
    }

    @Override
    public List<MemoryEntry> searchMemory(String sessionId, String keyword) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, List.of());
        return memory.stream()
                .filter(e -> e.getContent() != null && e.getContent().contains(keyword))
                .collect(Collectors.toList());
    }

    @Override
    public void clearMemory(String sessionId) {
        sessionMemories.remove(sessionId);
        sessionRetention.forget(sessionId);
        log.info("会话 {} 的记忆已清除", sessionId);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 180_000)
    public void evictStaleSessions() {
        sessionRetention.evictExpired(sessionId -> sessionMemories.remove(sessionId));
    }

    @Override
    public MemorySummary getMemorySummary(String sessionId) {
        List<MemoryEntry> memory = sessionMemories.getOrDefault(sessionId, List.of());
        MemorySummary summary = new MemorySummary();
        summary.setTotalEntries(memory.size());
        summary.setAnalysisCount((int) memory.stream().filter(MemoryEntry::isAnalysis).count());

        if (!memory.isEmpty()) {
            MemoryEntry last = memory.get(memory.size() - 1);
            summary.setLastIntent(last.getIntent());
            summary.setLastActivity(last.getTimestamp());
            summary.setLastTopic(extractTopic(last.getContent()));
        }

        return summary;
    }

    @Override
    public int getMemorySize(String sessionId) {
        return sessionMemories.getOrDefault(sessionId, List.of()).size();
    }

    private static void trim(List<MemoryEntry> memory, int maxSize) {
        while (memory.size() > maxSize) {
            memory.remove(0);
        }
    }

    private String extractTopic(String content) {
        if (content == null || content.isEmpty()) {
            return "未知";
        }
        String[] words = content.split("\\s+");
        if (words.length <= 5) {
            return content;
        }
        return String.join(" ", Arrays.copyOf(words, 5)) + "...";
    }
}
