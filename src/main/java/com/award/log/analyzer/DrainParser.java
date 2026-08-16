package com.award.log.analyzer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface DrainParser {

    String parse(String raw);

    String parse(String raw, Consumer<TemplateInfo> onNewTemplate);

    List<TemplateInfo> parallelParse(List<String> rawLogs, int threads);

    int getTemplateCount();

    Map<String, Object> getStats();

    String getVersion();

    interface TemplateInfo {
        String getTemplateId();
        String getTemplateText();
        long getCount();
    }
}
