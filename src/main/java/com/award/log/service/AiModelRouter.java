package com.award.log.service;

import com.award.log.config.AiModelRoutingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects an OpenAI-compatible profile without changing tool/security policy.
 * Profiles are intentionally server-side so a browser cannot inject an endpoint or API key.
 */
@Slf4j
@Service
public class AiModelRouter {

    private final ChatModel defaultChatModel;
    private final AiModelRoutingProperties properties;
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public AiModelRouter(ChatModel defaultChatModel, AiModelRoutingProperties properties) {
        this.defaultChatModel = defaultChatModel;
        this.properties = properties;
    }

    public ResolvedModel resolve(String userMessage, int estimatedPromptTokens, boolean toolAgent) {
        return resolve(userMessage, estimatedPromptTokens, toolAgent, null);
    }

    public ResolvedModel resolve(String userMessage,
                                 int estimatedPromptTokens,
                                 boolean toolAgent,
                                 String requestedProfile) {
        String mode = normalize(properties.getRoutingMode());
        String profileName = "default";
        AiModelRoutingProperties.Profile profile = null;
        String requested = requestedProfile == null ? "" : requestedProfile.trim();
        if (!requested.isBlank() && "AUTO".equalsIgnoreCase(requested)) {
            mode = "AUTO";
        } else if (!requested.isBlank() && !isDefaultProfile(requested)) {
            mode = "REQUESTED";
            profileName = requested;
            profile = properties.getProfiles().get(profileName);
        } else if (!requested.isBlank()) {
            mode = "DEFAULT";
        }
        if ("AUTO".equals(mode)) {
            for (String candidate : orderedAutoProfiles()) {
                AiModelRoutingProperties.Profile candidateProfile = properties.getProfiles().get(candidate);
                if (usable(candidateProfile, estimatedPromptTokens, toolAgent)) {
                    profileName = candidate;
                    profile = candidateProfile;
                    break;
                }
            }
        } else if (!isDefaultProfile(properties.getDefaultProfile())) {
            profileName = properties.getDefaultProfile();
            profile = properties.getProfiles().get(profileName);
        }

        if (profile == null || !usable(profile, estimatedPromptTokens, toolAgent)) {
            return new ResolvedModel(defaultChatModel, "default", properties.getDefaultModel(),
                    Math.max(1, properties.getDefaultContextWindow()), mode);
        }
        final String selectedProfileName = profileName;
        final AiModelRoutingProperties.Profile selectedProfile = profile;
        ChatModel model = modelCache.computeIfAbsent(
                selectedProfileName, ignored -> buildModel(selectedProfileName, selectedProfile));
        if (model == null) {
            return new ResolvedModel(defaultChatModel, "default", properties.getDefaultModel(),
                    Math.max(1, properties.getDefaultContextWindow()), mode);
        }
        return new ResolvedModel(model, selectedProfileName,
                blankToDefault(selectedProfile.getModel(), properties.getDefaultModel()),
                contextWindow(selectedProfile), mode);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("routingMode", normalize(properties.getRoutingMode()));
        out.put("defaultProfile", properties.getDefaultProfile());
        out.put("defaultModel", properties.getDefaultModel());
        out.put("defaultContextWindow", properties.getDefaultContextWindow());
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (Map.Entry<String, AiModelRoutingProperties.Profile> entry : properties.getProfiles().entrySet()) {
            AiModelRoutingProperties.Profile p = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("enabled", p != null && p.isEnabled());
            item.put("model", p == null ? "" : blankToDefault(p.getModel(), ""));
            item.put("contextWindow", p == null ? null : contextWindow(p));
            item.put("configured", p != null && !blank(p.getBaseUrl()) && !blank(p.getApiKey())
                    && !blank(p.getModel()));
            profiles.add(item);
        }
        out.put("profiles", profiles);
        return out;
    }

    private List<String> orderedAutoProfiles() {
        if (properties.getAutoProfiles() != null && !properties.getAutoProfiles().isEmpty()) {
            return properties.getAutoProfiles();
        }
        return properties.getProfiles().entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue() == null ? Integer.MAX_VALUE
                        : e.getValue().getPriority() == null ? 100 : e.getValue().getPriority()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private boolean usable(AiModelRoutingProperties.Profile profile, int promptTokens, boolean toolAgent) {
        if (profile == null || !profile.isEnabled()
                || blank(profile.getBaseUrl()) || blank(profile.getApiKey()) || blank(profile.getModel())) {
            return false;
        }
        // Tool Agent stays on configured OpenAI-compatible profiles; unsupported native providers are not guessed.
        if (toolAgent && !profile.getBaseUrl().toLowerCase(Locale.ROOT).startsWith("http")) {
            return false;
        }
        return promptTokens < Math.max(1, contextWindow(profile) - 512);
    }

    private ChatModel buildModel(String name, AiModelRoutingProperties.Profile profile) {
        if (!(defaultChatModel instanceof OpenAiChatModel openAi)) {
            log.warn("Cannot clone non-OpenAI ChatModel for profile {}; using default model", name);
            return null;
        }
        try {
            OpenAiChatOptions options = OpenAiChatOptions.fromOptions(
                    (OpenAiChatOptions) openAi.getDefaultOptions());
            options.setModel(profile.getModel());
            if (profile.getTemperature() != null) options.setTemperature(profile.getTemperature());
            if (profile.getTopP() != null) options.setTopP(profile.getTopP());
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(profile.getBaseUrl())
                    .apiKey(profile.getApiKey())
                    .build();
            return openAi.mutate()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build();
        } catch (Exception e) {
            log.warn("Cannot initialize model profile {}: {}", name, e.getMessage());
            return null;
        }
    }

    private int contextWindow(AiModelRoutingProperties.Profile profile) {
        return profile.getContextWindow() == null || profile.getContextWindow() < 1
                ? Math.max(1, properties.getDefaultContextWindow()) : profile.getContextWindow();
    }

    private boolean isDefaultProfile(String name) {
        return name == null || name.isBlank() || "default".equalsIgnoreCase(name);
    }

    private static String blankToDefault(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        return blankToDefault(value, "DEFAULT").toUpperCase(Locale.ROOT);
    }

    public record ResolvedModel(ChatModel chatModel,
                                String profile,
                                String model,
                                int contextWindow,
                                String routingMode) {
    }
}
