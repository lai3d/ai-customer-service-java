package dev.merlionos.customerservice.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey          xAI credential, its own property rather than OpenAI's
 * @param baseUrl         xAI's OpenAI-compatible endpoint
 * @param completionsPath path appended to the base URL; xAI mirrors OpenAI's
 * @param model           e.g. {@code grok-4.6}. List what a key can reach with
 *                        {@code curl https://api.x.ai/v1/models -H "Authorization: Bearer $XAI_API_KEY"}
 */
@ConfigurationProperties("spring.ai.xai")
public record XaiProperties(String apiKey, String baseUrl, String completionsPath, String model) {
}
