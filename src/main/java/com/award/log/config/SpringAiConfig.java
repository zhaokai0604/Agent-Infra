package com.award.log.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.Builder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置类
 * 用于配置ChatClient等AI相关组件
 */
@Configuration
public class SpringAiConfig {
    
    /**
     * 配置ChatClient Bean
     * @param builder ChatClient构建器
     * @return ChatClient实例
     */
    @Bean
    public ChatClient chatClient(Builder builder) {
        return builder.build();
    }
}