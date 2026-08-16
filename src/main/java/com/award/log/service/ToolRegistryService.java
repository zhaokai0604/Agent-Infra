package com.award.log.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolRegistryService {

    private final Map<String, AiTool> tools = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private List<AiTool> toolList;

    @PostConstruct
    public void init() {
        if (toolList != null) {
            for (AiTool tool : toolList) {
                register(tool);
            }
            log.info("已注册 {} 个AI工具: {}", tools.size(), tools.keySet());
        }
    }

    public void register(AiTool tool) {
        tools.put(tool.getName(), tool);
        log.info("注册工具: {} - {}", tool.getName(), tool.getDescription());
    }

    public void unregister(String toolName) {
        tools.remove(toolName);
    }

    public AiTool getTool(String toolName) {
        return tools.get(toolName);
    }

    public Set<String> getToolNames() {
        return tools.keySet();
    }

    public Map<String, String> getToolDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        for (Map.Entry<String, AiTool> entry : tools.entrySet()) {
            descriptions.put(entry.getKey(), entry.getValue().getDescription());
        }
        return descriptions;
    }

    public String getToolsSchema() {
        StringBuilder schema = new StringBuilder();
        schema.append("你可以使用以下工具来回答问题：\n\n");

        for (AiTool tool : tools.values()) {
            schema.append(String.format("【%s】\n", tool.getName()));
            schema.append(String.format("功能：%s\n", tool.getDescription()));
            schema.append(String.format("参数：%s\n\n", tool.getParameterDescription()));
        }

        schema.append("当需要执行实际操作时，请使用上述工具。");
        return schema.toString();
    }

    public int getToolCount() {
        return tools.size();
    }
}