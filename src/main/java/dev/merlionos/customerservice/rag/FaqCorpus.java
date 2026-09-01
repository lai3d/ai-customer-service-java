package dev.merlionos.customerservice.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The FAQ corpus file as it sits on disk.
 *
 * <p>Unknown fields are ignored explicitly rather than left to the injected ObjectMapper's
 * configuration. The corpus is external data that people edit by hand; adding a comment or a
 * field this code does not read should not stop the application from starting, and the answer
 * should not depend on which ObjectMapper happens to be wired in.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record FaqCorpus(String version, List<FaqEntry> entries) {
}
