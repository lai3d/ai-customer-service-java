package dev.merlionos.customerservice.config;

import dev.merlionos.customerservice.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards two deliberate departures from the defaults. Both look like configuration noise and
 * would be easy to delete during a tidy-up; both are the difference between an assistant that
 * fails in seconds and one that leaves a customer watching a spinner.
 */
@SpringBootTest
@Import(PostgresTestcontainer.class)
@ActiveProfiles("test")
class ResilienceConfigurationTest {

    @Autowired SpringAiRetryProperties retryProperties;
    @Autowired Environment environment;

    @Test
    @DisplayName("retry gives up in seconds, not in the nineteen minutes the defaults allow")
    void retryIsBoundedForAnInteractiveAssistant() {
        // Spring AI defaults: 10 attempts, 2s initial, multiplier 5, 180s cap
        // -> 2 + 10 + 50 + 180*6 = 1142s of backoff before the customer is told it failed.
        assertThat(retryProperties.getMaxAttempts()).isLessThanOrEqualTo(3);

        long worstCaseSeconds = worstCaseBackoffSeconds();
        assertThat(worstCaseSeconds)
                .as("total backoff across all attempts, in seconds")
                .isLessThanOrEqualTo(15);
    }

    private long worstCaseBackoffSeconds() {
        Duration interval = retryProperties.getBackoff().getInitialInterval();
        Duration cap = retryProperties.getBackoff().getMaxInterval();
        long total = 0;
        for (int attempt = 1; attempt < retryProperties.getMaxAttempts(); attempt++) {
            total += Math.min(interval.toSeconds(), cap.toSeconds());
            interval = interval.multipliedBy(retryProperties.getBackoff().getMultiplier());
        }
        return total;
    }

    @Test
    @DisplayName("HTTP timeouts are set, because Spring Boot ships none")
    void httpTimeoutsAreConfigured() {
        // Without these a hung upstream request never returns and the request thread waits
        // forever. This asserts the configuration is present, not that the socket honours it.
        assertThat(environment.getProperty("spring.http.client.connect-timeout")).isNotBlank();
        assertThat(environment.getProperty("spring.http.client.read-timeout")).isNotBlank();
    }
}
