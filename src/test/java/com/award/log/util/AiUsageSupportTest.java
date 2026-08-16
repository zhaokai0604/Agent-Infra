package com.award.log.util;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiUsageSupportTest {

    @Test
    void estimatesPromptAndMarksProviderUsageAsEstimatedWhenMissing() {
        int estimated = AiUsageSupport.estimatePromptTokens(List.of(new UserMessage("检查磁盘和服务状态")));
        Map<String, Object> usage = AiUsageSupport.usage(
                null, "default", "test-model", 1000, estimated, 20);

        assertTrue(estimated > 0);
        assertEquals("estimated", usage.get("source"));
        assertEquals(estimated + 20, usage.get("totalTokens"));
        assertEquals(1000, usage.get("contextWindow"));
    }
}
