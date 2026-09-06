package dev.merlionos.customerservice.admin;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The session bounds are refused at bind time when they cannot mean anything: a lifetime of
 * zero would end every session on its first request, a limit of zero would end every
 * sign-in. The defaults in {@code application.yml} are read from there so a change to them
 * is checked against the record's own.
 */
class AdminPropertiesTest {

    private static final AdminProperties.Seed NO_SEED = new AdminProperties.Seed(null, null);

    @Test
    @DisplayName("a zero or negative lifetime, or a limit below one, refuses to bind")
    void boundsMustBePositive() {
        assertThatThrownBy(() -> new AdminProperties(NO_SEED, Duration.ZERO, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ADMIN_SESSION_MAX_LIFETIME");
        assertThatThrownBy(() -> new AdminProperties(NO_SEED, Duration.ofMinutes(-1), 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("session-max-lifetime");
        assertThatThrownBy(() -> new AdminProperties(NO_SEED, Duration.ofHours(1), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ADMIN_SESSION_LIMIT");
        assertThat(new AdminProperties(NO_SEED, Duration.ofHours(1), 1).sessionLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("absent values take the defaults, which are the ones application.yml declares")
    void defaultsMatchTheConfiguration() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        AdminProperties absent = new AdminProperties(null, null, null);

        assertThat(absent.sessionMaxLifetime()).isEqualTo(DurationStyle.detectAndParse(defaultOf(yml, "ADMIN_SESSION_MAX_LIFETIME")));
        assertThat(absent.sessionLimit()).isEqualTo(Integer.parseInt(defaultOf(yml, "ADMIN_SESSION_LIMIT")));
        assertThat(absent.seed().configured()).isFalse();
    }

    private static String defaultOf(String yml, String variable) {
        Matcher matcher = Pattern.compile("\\$\\{" + variable + ":([^}]+)}").matcher(yml);
        assertThat(matcher.find()).as("a default for " + variable).isTrue();
        return matcher.group(1);
    }
}
