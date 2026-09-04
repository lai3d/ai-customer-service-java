package dev.merlionos.customerservice.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compose reads {@code .env} for interpolation but does <em>not</em> inject undeclared
 * variables into a container. A variable documented in {@code .env.example} and absent from the
 * app service's {@code environment:} list therefore does nothing, silently — which is what
 * happened to the tracing settings: the README claimed {@code docker compose up} pointed the
 * exporter at the bundled Jaeger, and it did not, because a text edit to this file failed to
 * match and nobody checked.
 *
 * <p>This reads the files rather than running Compose, so it costs nothing and needs no Docker.
 */
class ComposeEnvironmentTest {

    private static final Path COMPOSE = Path.of("docker-compose.yml");
    private static final Path ENV_EXAMPLE = Path.of(".env.example");

    /** Matches `NAME=` at the start of a line in .env.example, commented out or not. */
    private static final Pattern DECLARED = Pattern.compile("(?m)^#?\\s*([A-Z][A-Z0-9_]*)=");



    private static Map<String, Object> composeService(String name) throws IOException {
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> services =
                    (Map<String, Map<String, Object>>) root.get("services");
            return services.get(name);
        }
    }

    private static Set<String> appEnvironmentNames() throws IOException {
        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) composeService("app").get("environment");
        // Entries are either `NAME` (pass through from the environment) or `NAME=value`.
        return entries.stream()
                .map(entry -> entry.split("=", 2)[0])
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("every variable documented in .env.example reaches the app container")
    void documentedVariablesArePassedThrough() throws IOException {
        Set<String> passed = appEnvironmentNames();

        Matcher matcher = DECLARED.matcher(Files.readString(ENV_EXAMPLE));
        List<String> declared = matcher.results().map(result -> result.group(1)).distinct().toList();

        assertThat(declared).as("sanity: .env.example should document something").isNotEmpty();
        assertThat(passed)
                .as("documented but not passed through, so it silently does nothing")
                .containsAll(declared);
    }

    @Test
    @DisplayName("tracing in Compose points at the bundled Jaeger, not at localhost")
    void tracingTargetsTheBundledJaeger() throws IOException {
        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) composeService("app").get("environment");

        assertThat(entries).contains("OTLP_TRACING_EXPORT_ENABLED=true");
        assertThat(entries)
                .as("localhost inside the app container is the app container")
                .contains("OTLP_TRACING_ENDPOINT=http://jaeger:4318/v1/traces");
    }

    @Test
    @DisplayName("the database host is the compose alias, never the host-side port variable")
    void databaseHostIsTheComposeAlias() throws IOException {
        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) composeService("app").get("environment");

        assertThat(entries).contains("POSTGRES_HOST=postgres", "POSTGRES_PORT=5432");
    }

    @Test
    @DisplayName("no secret is written into the compose file itself")
    void secretsArePassedByNameOnly() throws IOException {
        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) composeService("app").get("environment");

        assertThat(entries)
                .filteredOn(entry -> entry.contains("API_KEY"))
                .as("an API key entry must be name-only so no value is committed")
                .allSatisfy(entry -> assertThat(entry).doesNotContain("="));
    }
}
