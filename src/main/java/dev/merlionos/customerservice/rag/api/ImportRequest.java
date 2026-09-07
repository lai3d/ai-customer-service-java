package dev.merlionos.customerservice.rag.api;

/**
 * An import as it crosses the seam. A URL import carries the URL; a PDF import carries the
 * file name and the bytes, which Jackson writes as base64. The actor is trusted for the same
 * reason every other command on the seam is: the bearer token authenticates the calling
 * process, which checked the role.
 */
public record ImportRequest(String actor, String url, String fileName, byte[] content) {

    public static ImportRequest url(String actor, String url) {
        return new ImportRequest(actor, url, null, null);
    }

    public static ImportRequest pdf(String actor, String fileName, byte[] content) {
        return new ImportRequest(actor, null, fileName, content);
    }
}
