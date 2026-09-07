package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.SearchQuery;
import dev.merlionos.customerservice.rag.api.TenantFilter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Optional;

/**
 * The vector store as retrieval sees it: every search is confined to the active knowledge
 * version, or to the version a caller names. Writes pass through untouched, so ingestion and
 * publication write whichever version they are building. Wrapped around the pgvector bean by
 * {@link VersionedVectorStorePostProcessor}, so {@code QuestionAnswerAdvisor} in an
 * {@code all} process and {@link LocalKnowledgeSearch} in a {@code knowledge} process both
 * read through it without either having to know.
 *
 * <p>Whose active version: the tenant named in the request's filter expression
 * ({@link TenantFilter}), which is how {@code QuestionAnswerAdvisor} lets a request say
 * anything. A request that names no tenant is the default tenant's -- the bundled corpus,
 * every pre-tenancy caller, every test that searches the store directly; the customer path
 * always names one, and {@code KnowledgeAdminIntegrationTest.tenantsRetrieveOnlyTheirOwn} is what fails if it stops.
 *
 * <p>With no active version -- a fresh database before the bundled corpus is adopted, or a
 * tenant that has not published -- a search finds nothing, which is the truth and, for the
 * default tenant, what readiness already says.
 */
public class ActiveVersionVectorStore implements VectorStore {

    static final String VERSION_KEY = FaqDocumentReader.METADATA_VERSION;

    private final VectorStore delegate;
    private final ActiveKnowledgeVersion active;

    public ActiveVersionVectorStore(VectorStore delegate, ActiveKnowledgeVersion active) {
        this.delegate = delegate;
        this.active = active;
    }

    public VectorStore delegate() {
        return delegate;
    }

    public ActiveKnowledgeVersion activeVersion() {
        return active;
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String tenant = TenantFilter.tenantOf(request.getFilterExpression()).orElse(SearchQuery.DEFAULT_TENANT);
        SearchRequest rest = SearchRequest.from(request).filterExpression(TenantFilter.without(request.getFilterExpression())).build();
        return active.get(tenant).map(version -> similaritySearch(rest, version)).orElse(List.of());
    }

    /** The same search, against one named version. */
    public List<Document> similaritySearch(SearchRequest request, String version) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression byVersion = builder.eq(VERSION_KEY, version).build();
        Filter.Expression combined = request.getFilterExpression() == null
                ? byVersion
                : new Filter.Expression(Filter.ExpressionType.AND, request.getFilterExpression(), byVersion);
        return delegate.similaritySearch(SearchRequest.from(request).filterExpression(combined).build());
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }
}
