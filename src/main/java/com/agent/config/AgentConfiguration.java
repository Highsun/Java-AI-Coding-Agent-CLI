package com.agent.config;

import com.agent.llm.GlmClient;
import java.net.URI;
import java.util.Locale;

/** Resolved settings. Deliberately redact credentials even in debug output. */
public record AgentConfiguration(String apiKey, String apiUrl, String model) {
    public boolean hasApiKey() {
        if (apiKey == null || apiKey.isBlank() || apiKey.chars()
                .anyMatch(c -> c <= 0x20 || c >= 0x7f || c == '"' || c == '\'' || c == '\\')) return false;
        return !java.util.Set.of("your-glm-api-key", "your-api-key", "your_key_here", "your-key")
                .contains(apiKey.toLowerCase(Locale.ROOT));
    }

    public static boolean validModel(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    }

    public static boolean validApiUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    public static AgentConfiguration defaults() {
        return new AgentConfiguration(null, GlmClient.DEFAULT_API_URL, GlmClient.DEFAULT_MODEL);
    }

    @Override public String toString() {
        return "AgentConfiguration[apiKey=<redacted>, apiUrl=" + apiUrl + ", model=" + model + "]";
    }
}
