package dev.merlionos.customerservice.admin;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same line the demo page holds ({@code DemoPageMarkupTest}): the admin renders
 * usernames and, in later steps, ticket summaries and customer messages, none of which the
 * page wrote. Every element is built with {@code createElement} and {@code textContent}, so
 * a string can only ever become a text node.
 */
class AdminPageMarkupTest {

    private static final String[] STRING_TO_MARKUP_SINKS = {
            "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write",
            "eval(", "new Function", "srcdoc",
    };

    @ParameterizedTest
    @ValueSource(strings = {"admin/index.html", "admin/login.html"})
    void neverTurnsAStringIntoMarkup(String page) throws IOException {
        String markup = new ClassPathResource(page).getContentAsString(StandardCharsets.UTF_8);
        for (String sink : STRING_TO_MARKUP_SINKS) {
            assertThat(markup).as("%s in %s builds markup from a string", sink, page).doesNotContain(sink);
        }
    }
}
