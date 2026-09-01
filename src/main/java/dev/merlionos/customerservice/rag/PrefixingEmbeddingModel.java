package dev.merlionos.customerservice.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.BatchingStrategy;

import java.util.List;

/**
 * Applies the asymmetric input prefixes that retrieval-trained embedding models require.
 *
 * <p>The e5 family is trained with {@code query: } in front of a search query and
 * {@code passage: } in front of an indexed document. The prefixes are not decoration: they are
 * how the model knows which side of an asymmetric pair it is looking at. Omitting them costs
 * real retrieval quality, and omitting them on only one side is worse than omitting both.
 *
 * <p>Wrapping the embedding model is the right seam because the vector store already
 * distinguishes the two cases for us -- {@code PgVectorStore} embeds documents through
 * {@code embed(List<Document>, ...)} when writing and through {@code embed(String)} when
 * searching. Nothing above this class needs to know the convention exists, and switching to a
 * model that does not want prefixes is a matter of blanking two properties.
 *
 * <p>Documents are copied rather than mutated: the prefix belongs in the vector, not in the
 * text stored alongside it or shown to the model as context.
 */
class PrefixingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final String queryPrefix;
    private final String passagePrefix;

    PrefixingEmbeddingModel(EmbeddingModel delegate, String queryPrefix, String passagePrefix) {
        this.delegate = delegate;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.passagePrefix = passagePrefix == null ? "" : passagePrefix;
    }

    /** The search path: {@code PgVectorStore.similaritySearch} calls this. */
    @Override
    public float[] embed(String text) {
        return delegate.embed(queryPrefix + text);
    }

    /** The indexing path, single document. */
    @Override
    public float[] embed(Document document) {
        return delegate.embed(withPassagePrefix(document));
    }

    /**
     * The indexing path used by the vector store. Delegating with prefixed copies rather than
     * flattening to strings keeps the batching strategy in play, which is what stops a large
     * corpus from exceeding the model's token limit in one call.
     */
    @Override
    public List<float[]> embed(List<Document> documents, EmbeddingOptions options,
                               BatchingStrategy batchingStrategy) {
        return delegate.embed(documents.stream().map(this::withPassagePrefix).toList(),
                options, batchingStrategy);
    }

    private Document withPassagePrefix(Document document) {
        return new Document(document.getId(), passagePrefix + document.getText(),
                document.getMetadata());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        // Reached only by callers that bypass the methods above; the prefix cannot be inferred
        // here, so the text is passed through untouched rather than guessed at.
        return delegate.call(request);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }
}
