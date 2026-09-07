package dev.merlionos.customerservice.rag.api;

/**
 * A retrieval request as the chat side phrases it: whose knowledge, the customer's words,
 * how many passages, and the similarity floor. The last three are the values
 * {@code QuestionAnswerAdvisor} puts in its {@code SearchRequest}, so the two paths cannot
 * drift; the tenant is what the advisor carries as a filter ({@link TenantFilter}).
 *
 * @param tenantId whose active version to search; never null
 * @param version  a knowledge version to search instead of the tenant's active one; null
 *                 for the active one. Only the admin's preview sets it
 */
public record SearchQuery(String tenantId, String text, int topK, double similarityThreshold, String version) {

    /** The tenant every pre-tenancy row belongs to; the same literal as {@code Tenant.DEFAULT} on the chat side. */
    public static final String DEFAULT_TENANT = "default";

    /** Against the tenant's active version, which is what every customer turn asks. */
    public SearchQuery(String tenantId, String text, int topK, double similarityThreshold) {
        this(tenantId, text, topK, similarityThreshold, null);
    }
}
