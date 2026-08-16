package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime-selectable OpenAI-compatible model profiles. */
@Data
@ConfigurationProperties(prefix = "agent.ai")
public class AiModelRoutingProperties {

    /** DEFAULT uses the Spring AI model; AUTO selects the first suitable profile. */
    private String routingMode = "DEFAULT";

    private String defaultProfile = "default";

    private String defaultModel = "deepseek-chat";

    /** Conservative fallback when a provider does not return its context limit. */
    private int defaultContextWindow = 32_768;

    private List<String> autoProfiles = new ArrayList<>();

    private Map<String, Profile> profiles = new LinkedHashMap<>();

    @Data
    public static class Profile {
        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;
        private String model;
        private Integer contextWindow;
        private Integer priority = 100;
        private Double temperature;
        private Double topP;
    }
}
