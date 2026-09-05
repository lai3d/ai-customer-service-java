package dev.merlionos.customerservice.target;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code app.target} into the property overrides a role needs, before any
 * auto-configuration has run -- and refuses to start a role whose configuration is incomplete.
 *
 * <p>Bean conditions cannot do this job. Which Spring AI model types a process initialises,
 * whether it builds a vector store, which health indicators make up readiness: those are
 * switches on auto-configurations that run before any bean of ours is considered. So a
 * {@code ticket} process starts without an LLM key and without loading an ONNX model because
 * this class says {@code spring.ai.model.chat=none} and {@code spring.ai.model.embedding=none}
 * for it, not because a bean somewhere is missing.
 *
 * <p>The overrides sit above {@code application.yml} and below the command line and the
 * environment, so an operator can still set any of them explicitly. Values that should
 * remain operator-settable are written as placeholders with a role-specific default, which
 * is how the knowledge role's import mode defaults to {@code off} while {@code all} keeps
 * {@code startup}.
 */
public class TargetEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE = "deploymentTarget";

    private static final String NONE = "none";

    /**
     * The staff login for the operations admin exists only where the admin does: the chat role.
     * A knowledge or ticket process has no {@code /admin} and no staff session, so Spring
     * Security's and Spring Session's auto-configurations are switched off there outright --
     * left on, Boot's default chain would put a generated password in front of every
     * {@code /internal/**} endpoint the bearer token already guards. Auto-configurations are
     * switched off by property, like the model types above, because no bean condition of ours
     * runs early enough to reach them.
     */
    static final String ADMIN_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
            "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        DeploymentTarget target = DeploymentTarget.from(environment);
        Map<String, Object> overrides = overridesFor(target);
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addAfter(
                    // Below command-line args and the OS environment (which sit at the top),
                    // above every application*.yml.
                    lastNonFileSource(environment), new MapPropertySource(PROPERTY_SOURCE, overrides));
        }
        requireConfiguration(target, environment);
    }

    /** What each role switches off, and what it needs switched on. Package-private for the test. */
    static Map<String, Object> overridesFor(DeploymentTarget target) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        switch (target) {
            case ALL -> { /* application.yml as written */ }
            case CHAT -> {
                overrides.put("spring.ai.model.embedding", NONE);
                overrides.put("spring.ai.vectorstore.type", NONE);
                overrides.put("app.rag.import-mode", "off");
                overrides.put("management.endpoint.health.group.readiness.include", "readinessState,knowledge");
            }
            case KNOWLEDGE -> {
                overrides.put("spring.ai.model.chat", NONE);
                overrides.put("spring.ai.chat.client.enabled", "false");
                // Serving replicas do not import; a Job with APP_RAG_IMPORT_MODE=once does.
                overrides.put("app.rag.import-mode", "${APP_RAG_IMPORT_MODE:off}");
                overrides.put("spring.autoconfigure.exclude", ADMIN_AUTO_CONFIGURATIONS);
            }
            case TICKET -> {
                overrides.put("spring.ai.model.chat", NONE);
                overrides.put("spring.ai.model.embedding", NONE);
                overrides.put("spring.ai.vectorstore.type", NONE);
                overrides.put("spring.ai.chat.client.enabled", "false");
                overrides.put("app.rag.import-mode", "off");
                overrides.put("management.endpoint.health.group.readiness.include", "readinessState");
                overrides.put("spring.autoconfigure.exclude", ADMIN_AUTO_CONFIGURATIONS);
            }
        }
        return overrides;
    }

    private static void requireConfiguration(DeploymentTarget target, ConfigurableEnvironment environment) {
        List<String> missing = new ArrayList<>();
        switch (target) {
            case ALL -> { /* nothing beyond what application.yml already requires */ }
            case CHAT -> {
                require(environment, "app.services.knowledge.url", missing);
                require(environment, "app.services.ticket.url", missing);
                require(environment, "app.internal.token", missing);
            }
            case KNOWLEDGE, TICKET -> require(environment, "app.internal.token", missing);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(DeploymentTarget.PROPERTY + "=" + target.name().toLowerCase()
                    + " needs " + String.join(", ", missing) + " to be set. A "
                    + target.name().toLowerCase() + " process cannot do its job without them, so it "
                    + "refuses to start rather than report healthy and fail every request.");
        }
    }

    private static void require(ConfigurableEnvironment environment, String property, List<String> missing) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            missing.add(property);
        }
    }

    private static String lastNonFileSource(ConfigurableEnvironment environment) {
        String last = null;
        for (var source : environment.getPropertySources()) {
            if (source.getName().startsWith("Config resource") || source.getName().startsWith("applicationConfig")) {
                break;
            }
            last = source.getName();
        }
        return last;
    }
}
