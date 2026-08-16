package com.award.log.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 可配置的 CORS 白名单；禁止通配符 {@code http://*} / {@code https://*} 与 credentials 组合。
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {

    /** Spring {@link org.springframework.web.servlet.config.annotation.CorsRegistry} patterns */
    private List<String> allowedOriginPatterns = defaultPatterns();

    private boolean allowCredentials = true;

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? defaultPatterns()
                : allowedOriginPatterns;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return matchesOrigin(origin.trim());
    }

    public boolean isRefererAllowed(String referer) {
        if (referer == null || referer.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            int port = uri.getPort();
            String origin = port > 0
                    ? uri.getScheme() + "://" + uri.getHost() + ":" + port
                    : uri.getScheme() + "://" + uri.getHost();
            return matchesOrigin(origin);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesOrigin(String origin) {
        for (String pattern : allowedOriginPatterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            if (originMatchesPattern(origin, pattern.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean originMatchesPattern(String origin, String pattern) {
        if (origin.equalsIgnoreCase(pattern)) {
            return true;
        }
        if (!pattern.contains("*")) {
            return false;
        }
        String regex = pattern
                .replace(".", "\\.")
                .replace("://*", "://[^/]+")
                .replace("*", ".*");
        return origin.matches(regex);
    }

    private static List<String> defaultPatterns() {
        List<String> list = new ArrayList<>();
        list.add("http://localhost:*");
        list.add("http://127.0.0.1:*");
        return list;
    }
}
