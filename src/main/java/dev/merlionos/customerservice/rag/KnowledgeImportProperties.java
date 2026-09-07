package dev.merlionos.customerservice.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param allowPrivateNetworks lets a URL import reach loopback, link-local and private
 *                             addresses. Off by default because a URL typed by a tenant's
 *                             admin is a request the knowledge role makes from inside the
 *                             deployment's network; on for tests and for a laptop
 * @param maxDocumentSize      the largest page or PDF an import reads
 * @param fetchTimeout         connect and read timeout for one fetch, and per redirect hop
 * @param chunkTokens          the size of the chunks a document is split into, each one entry
 */
@ConfigurationProperties("app.knowledge-import")
public record KnowledgeImportProperties(Boolean allowPrivateNetworks, DataSize maxDocumentSize, Duration fetchTimeout,
                                        Integer chunkTokens) {

    public boolean allowPrivateNetworksOrDefault() {
        return Boolean.TRUE.equals(allowPrivateNetworks);
    }

    public DataSize maxDocumentSizeOrDefault() {
        return maxDocumentSize == null ? DataSize.ofMegabytes(10) : maxDocumentSize;
    }

    public Duration fetchTimeoutOrDefault() {
        return fetchTimeout == null ? Duration.ofSeconds(10) : fetchTimeout;
    }

    public int chunkTokensOrDefault() {
        return chunkTokens == null ? 400 : chunkTokens;
    }
}
