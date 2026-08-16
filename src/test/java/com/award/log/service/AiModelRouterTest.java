package com.award.log.service;

import com.award.log.config.AiModelRoutingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import static org.mockito.Mockito.mock;

class AiModelRouterTest {

    @Test
    void defaultModeKeepsExistingSpringAiModel() {
        ChatModel existing = mock(ChatModel.class);
        AiModelRoutingProperties properties = new AiModelRoutingProperties();
        properties.setDefaultModel("deepseek-chat");

        AiModelRouter router = new AiModelRouter(existing, properties);
        AiModelRouter.ResolvedModel resolved = router.resolve("检查磁盘", 100, true);

        assertSame(existing, resolved.chatModel());
        assertEquals("default", resolved.profile());
        assertEquals("deepseek-chat", resolved.model());
        assertEquals("DEFAULT", resolved.routingMode());
        Map<String, Object> snapshot = router.snapshot();
        assertEquals("DEFAULT", snapshot.get("routingMode"));
    }
}
