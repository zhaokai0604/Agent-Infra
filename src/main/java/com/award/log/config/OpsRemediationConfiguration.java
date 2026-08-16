package com.award.log.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpsRemediationProperties.class)
public class OpsRemediationConfiguration {
}
