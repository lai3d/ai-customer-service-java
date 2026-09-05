package dev.merlionos.customerservice.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease must outlive the longest turn that can still succeed, or a slow but healthy turn
 * loses its own conversation to the next request. Both values are defaults in
 * {@code application.yml}; this reads them from there so a change to either is checked
 * against the other.
 */
class ChatPropertiesTest {

    @Test
    @DisplayName("the turn lease outlives the HTTP read timeout")
    void leaseOutlivesReadTimeout() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));

        Duration lease = defaultOf(yml, "TURN_LEASE");
        Duration readTimeout = defaultOf(yml, "HTTP_READ_TIMEOUT");

        assertThat(lease).isGreaterThan(readTimeout);
    }

    private static Duration defaultOf(String yml, String variable) {
        Matcher matcher = Pattern.compile("\\$\\{" + variable + ":([^}]+)}").matcher(yml);
        assertThat(matcher.find()).as("a default for " + variable).isTrue();
        return DurationStyle.detectAndParse(matcher.group(1));
    }
}
