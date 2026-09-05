package dev.merlionos.customerservice.rag.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One knowledge operation on the wire between a {@code chat} process and a {@code knowledge}
 * process: who is acting, what they expected to be active, and the one or two fields the
 * operation takes. The operation itself is the path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeCommand(String actor, String expectedActive, String note, String category, String version,
                               Boolean retired) {
}
