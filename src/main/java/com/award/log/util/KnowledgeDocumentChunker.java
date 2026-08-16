package com.award.log.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 运维文档分块：按段落/句号优先切分，避免硬截断句子。
 */
public final class KnowledgeDocumentChunker {

    private KnowledgeDocumentChunker() {
    }

    public static List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(200, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.length() <= size) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + size, normalized.length());
            if (end < normalized.length()) {
                int breakAt = findBreakPoint(normalized, start, end);
                if (breakAt > start + size / 4) {
                    end = breakAt;
                }
            }
            String piece = normalized.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - ov);
        }
        return chunks;
    }

    private static int findBreakPoint(String text, int start, int end) {
        int best = -1;
        for (int i = end - 1; i > start; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i;
            }
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                best = i + 1;
                break;
            }
        }
        if (best > start) {
            return best;
        }
        int space = text.lastIndexOf(' ', end - 1);
        return space > start ? space : end;
    }
}
