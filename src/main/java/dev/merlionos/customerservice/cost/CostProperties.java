package dev.merlionos.customerservice.cost;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * @param conversationTokenBudget total tokens one conversation may spend before it has to be
 *                                handed to a human. 0 disables the cap
 * @param budgetRetention         how long a conversation's spend row is kept after it was
 *                                last touched; older rows are swept
 * @param prices                  dollars per million tokens, keyed by model id. Absent means
 *                                the model's spend is counted but not costed
 */
@ConfigurationProperties("app.cost")
public record CostProperties(
        long conversationTokenBudget,
        Duration budgetRetention,
        Map<String, ModelPrice> prices) {

    public record ModelPrice(double inputPerMillionUsd, double outputPerMillionUsd) {
    }
}
