package dev.merlionos.customerservice.rag.api;

/**
 * One page of a tenant's entries, narrowed: a text over the id, the category and the
 * source; a source to keep ({@code typed} for entries a person wrote or the bundled corpus,
 * or one import's own source); and the page. One document is hundreds of chunks, so the
 * page is the server's, not the browser's.
 */
public record EntryFilter(String text, String source, int page, int size) {

    public static final String TYPED = "typed";
    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 25;

    public EntryFilter {
        text = text == null || text.isBlank() ? null : text.strip();
        source = source == null || source.isBlank() ? null : source.strip();
        page = Math.max(page, 0);
        size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public static EntryFilter first() {
        return new EntryFilter(null, null, 0, DEFAULT_SIZE);
    }
}
