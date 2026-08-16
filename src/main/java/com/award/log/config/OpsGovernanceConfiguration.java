package com.award.log.config;

import com.award.log.governance.OpsGovernanceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpsGovernanceProperties.class)
public class OpsGovernanceConfiguration {
}
