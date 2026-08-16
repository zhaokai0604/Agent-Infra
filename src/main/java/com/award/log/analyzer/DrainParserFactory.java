package com.award.log.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志模板挖掘统一入口：当前仅使用 Drain-Plus 实现。
 */
@Slf4j
@Component
public class DrainParserFactory {

    private final DrainParser plusParser;

    public DrainParserFactory(@Qualifier("drainPlusParser") DrainParser plusParser) {
        this.plusParser = plusParser;
        log.info("[DrainParserFactory] 使用 Drain-Plus（长度分桶 + 前缀 Trie + 簇内相似度合并）");
    }

    public DrainParser getParser() {
        return plusParser;
    }

    public DrainParser getPlusParser() {
        return plusParser;
    }

    public Map<String, Object> getAllStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("plus", plusParser.getStats());
        stats.put("active", plusParser.getVersion());
        return stats;
    }
}
