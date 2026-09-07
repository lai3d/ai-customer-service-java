package dev.merlionos.customerservice.evaluation;

import java.time.Instant;
import java.util.List;

/**
 * One question and what a correct answer to it looks like. The rubric is deliberately
 * mechanical -- phrases that must and must not appear, entries retrieval must find, a tool
 * that must run, or a refusal -- so a score means the same thing on every run and costs
 * nothing beyond the turn itself. It measures what can be checked by string, which is the
 * facts; whether an answer is well put is a judgement this does not make.
 *
 * @param expectedEntryIds every one must be among the retrieved passages; empty means no requirement
 * @param mustContain      every phrase must appear in the answer, case-insensitively
 * @param anyOf            at least one must appear; empty means no requirement
 * @param mustNotContain   none may appear
 * @param expectTool       a tool name that must have run, or null
 * @param expectRefusal    the question is outside the knowledge and a correct answer declines
 */
public record GoldenCase(Long id, String tenantId, String question, String language, List<String> expectedEntryIds,
                         List<String> mustContain, List<String> anyOf, List<String> mustNotContain, String expectTool,
                         boolean expectRefusal, boolean enabled, String note, Instant createdAt, String createdBy) {
}
