package com.award.log.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AgentOpsProperties.class, AiModelRoutingProperties.class})
public class AgentOpsConfiguration {
}
