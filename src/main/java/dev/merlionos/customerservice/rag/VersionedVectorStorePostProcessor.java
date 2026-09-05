package dev.merlionos.customerservice.rag;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Wraps the auto-configured pgvector bean in {@link ActiveVersionVectorStore}. A decorator
 * bean of the same type would have made {@code PgVectorStoreAutoConfiguration} back off
 * ({@code @ConditionalOnMissingBean}) and there would have been nothing to wrap; a
 * post-processor leaves the auto-configuration alone and changes what every injection
 * point receives. Only the bean named {@code vectorStore} is touched; a {@code chat}
 * process has a remote store under another name and no pgvector at all.
 */
@Component
class VersionedVectorStorePostProcessor implements BeanPostProcessor, BeanFactoryAware {

    static final String PGVECTOR_BEAN = "vectorStore";

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (PGVECTOR_BEAN.equals(beanName) && bean instanceof VectorStore store && !(bean instanceof ActiveVersionVectorStore)) {
            return new ActiveVersionVectorStore(store, new ActiveKnowledgeVersion(beanFactory.getBean(JdbcTemplate.class)));
        }
        return bean;
    }
}
