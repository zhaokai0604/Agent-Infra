package com.award.log.config;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ThreshCore API 接口文档")
                        .description("ThreshCore 后端服务 REST 接口说明（日志分析、智能诊断、运维工具与审计等能力）")
                        .contact(new Contact().name("ThreshCore 开发团队").email("dev@threshcore.local"))
                        .version("1.0"));
    }
}