package dev.merlionos.customerservice.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class EmbeddingConfig {

    /**
     * Wraps the auto-configured in-process model so everything that embeds -- the vector store
     * on both the write and search paths -- goes through the prefix convention the model was
     * trained with. Marked primary so injection points keep asking for {@link EmbeddingModel}
     * and get the wrapper without knowing it exists.
     */
    @Bean
    @Primary
    EmbeddingModel prefixingEmbeddingModel(TransformersEmbeddingModel delegate, RagProperties properties) {
        return new PrefixingEmbeddingModel(delegate, properties.queryPrefix(), properties.passagePrefix());
    }
}
