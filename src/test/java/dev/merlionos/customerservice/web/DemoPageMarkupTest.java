package dev.merlionos.customerservice.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demo page renders a small Markdown subset, and the text it renders is written by the
 * model -- whose own input includes retrieved passages. The safeguard is structural rather
 * than a sanitiser: every branch of the renderer appends a text node or an element it
 * created, so model text either becomes a text node or does not appear.
 *
 * <p>That property is invisible in a screenshot and easy to lose in a one-line edit, which
 * is exactly the kind of thing worth asserting. It is a grep, and a grep is a weak test --
 * but the thing being defended is the absence of a construct, and absence is what a grep
 * can actually check.
 */
class DemoPageMarkupTest {

    private static final String[] STRING_TO_MARKUP_SINKS = {
            "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write",
            "eval(", "new Function", "srcdoc",
    };

    @Test
    @DisplayName("the demo page never turns a string into markup")
    void neverTurnsAStringIntoMarkup() throws IOException {
        String page = page();
        for (String sink : STRING_TO_MARKUP_SINKS) {
            assertThat(page)
                    .as("%s builds markup from a string; the demo page renders model-authored "
                        + "text and must build DOM nodes instead", sink)
                    .doesNotContain(sink);
        }
    }

    @Test
    @DisplayName("the renderer is still there to be protected")
    void theRendererExists() throws IOException {
        assertThat(page()).contains("function renderMarkdown(");
    }

    private String page() throws IOException {
        return new ClassPathResource("static/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
