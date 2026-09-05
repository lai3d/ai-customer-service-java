package dev.merlionos.customerservice.target;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;

/**
 * What one process is. The same artifact runs as everything at once, or as one role of it,
 * chosen by {@code app.target} at start time. See {@code docs/adr/001-deployment-targets.md}.
 *
 * <p>{@link #ALL} is the default and is the process this repository has always been: every
 * role in one JVM, wired by local calls. The other three are the roles; each owns its beans,
 * its tables and, eventually, its endpoints.
 */
public enum DeploymentTarget {

    /** Every role, in-process. The benchmark baseline. */
    ALL,
    /** The public API, SSE, the advisor chain, the tool adapters, memory and budget. */
    CHAT,
    /** Embedding, search, the corpus and its import. Package {@code rag}. */
    KNOWLEDGE,
    /** Ticket persistence, deduplication and the per-conversation cap. */
    TICKET;

    public static final String PROPERTY = "app.target";

    /** Whether a process running as this target runs the given role. */
    public boolean runs(DeploymentTarget role) {
        return this == ALL || this == role;
    }

    /** Parses the configured value; blank or absent means {@link #ALL}. */
    public static DeploymentTarget from(Environment environment) {
        return parse(environment.getProperty(PROPERTY));
    }

    public static DeploymentTarget parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + PROPERTY + " '" + value
                    + "'. Valid values: " + Arrays.toString(values()).toLowerCase(Locale.ROOT), e);
        }
    }
}
