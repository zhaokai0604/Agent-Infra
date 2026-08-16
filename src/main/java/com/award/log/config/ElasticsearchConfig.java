package com.award.log.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Elasticsearch配置类
 * 用于配置Elasticsearch的连接和初始化
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.elasticsearch.enabled", havingValue = "true")
@EnableElasticsearchRepositories(basePackages = "com.award.log.repository")
public class ElasticsearchConfig {

    // Elasticsearch的连接配置已在application.yml中定义
    // 这里只在显式启用 Elasticsearch 时注册 Repository，避免纯 MySQL/MariaDB 生产部署启动失败

}
