package com.award.log.service;

import java.util.Map;

public interface AiTool {

    String getName();

    String getDescription();

    String getParameterDescription();

    ToolResult execute(Map<String, Object> parameters);

    Map<String, Object> getFunctionSchema();

    default boolean requiresConfirmation() {
        return false;
    }

    default String getConfirmationMessage(Map<String, Object> params) {
        return null;
    }

    class ToolResult {
        private boolean success;
        private String content;
        private String error;
        private Map<String, Object> data;
        private String suggestedAction;

        public static ToolResult success(String content) {
            ToolResult result = new ToolResult();
            result.success = true;
            result.content = content;
            return result;
        }

        public static ToolResult success(String content, Map<String, Object> data) {
            ToolResult result = new ToolResult();
            result.success = true;
            result.content = content;
            result.data = data;
            return result;
        }

        public static ToolResult success(String content, Map<String, Object> data, String suggestedAction) {
            ToolResult result = new ToolResult();
            result.success = true;
            result.content = content;
            result.data = data;
            result.suggestedAction = suggestedAction;
            return result;
        }

        public static ToolResult error(String error) {
            ToolResult result = new ToolResult();
            result.success = false;
            result.error = error;
            return result;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
        public String getSuggestedAction() { return suggestedAction; }
        public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
    }
}