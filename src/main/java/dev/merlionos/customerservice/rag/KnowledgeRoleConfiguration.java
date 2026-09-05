package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeAdmin;
import dev.merlionos.customerservice.rag.api.KnowledgeSearch;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import dev.merlionos.customerservice.target.ConditionalOnTarget;
import dev.merlionos.customerservice.target.DeploymentTarget;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * The knowledge role: the embedding model, the corpus and its import, and search. Present in
 * {@code all} and {@code knowledge} processes. The package is still called {@code rag}; the
 * role is what the ADR calls it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnTarget(DeploymentTarget.KNOWLEDGE)
@ComponentScan(basePackageClasses = KnowledgeRoleConfiguration.class)
public class KnowledgeRoleConfiguration {

    @Bean
    KnowledgeSearch knowledgeSearch(VectorStore vectorStore) {
        return new LocalKnowledgeSearch(vectorStore);
    }

    /** Editing and publishing, over the same store retrieval reads through. */
    @Bean
    KnowledgeAdmin knowledgeAdmin(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                                  VectorStore vectorStore, MeterRegistry meterRegistry) {
        if (!(vectorStore instanceof ActiveVersionVectorStore versioned)) {
            throw new IllegalStateException("The knowledge role's vector store should be wrapped for versions; got "
                    + vectorStore.getClass().getName());
        }
        return new JdbcKnowledgeAdmin(jdbcTemplate, transactionManager, versioned, meterRegistry);
    }
}
