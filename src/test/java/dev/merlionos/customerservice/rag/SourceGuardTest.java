package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a URL import may not fetch: every address inside the deployment, on every hop. */
class SourceGuardTest {

    private final SourceGuard guard = new SourceGuard(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/help", "http://LOCALHOST:8080/", "http://app.localhost/", "http://postgres.internal/",
            "http://127.0.0.1/", "http://127.1.2.3/", "http://[::1]/", "http://0.0.0.0/",
            "http://169.254.169.254/latest/meta-data/", "http://[fe80::1]/",
            "http://10.0.0.1/", "http://172.16.5.5/", "http://192.168.1.1/", "http://100.64.0.1/",
            "http://[fc00::1]/", "http://[fd12::1]/", "http://224.0.0.1/"})
    @DisplayName("addresses inside the deployment are refused")
    void refusesInternalAddresses(String url) {
        assertThatThrownBy(() -> guard.check(url)).isInstanceOf(KnowledgeRuleException.class)
                .hasMessageContaining("inside the deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/x", "file:///etc/passwd", "gopher://x/", "example.com/help", "", "http:///nohost"})
    @DisplayName("only http and https with a host are URLs an import takes")
    void refusesOtherSchemes(String url) {
        assertThatThrownBy(() -> guard.check(url)).isInstanceOf(KnowledgeRuleException.class);
    }

    @Test
    @DisplayName("credentials in a URL are refused before anything is resolved")
    void refusesUserInfo() {
        assertThatThrownBy(() -> guard.check("https://user:secret@example.com/")).isInstanceOf(KnowledgeRuleException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("a public address passes; the private-network switch lets loopback through for a laptop or a test")
    void publicAndSwitched() throws Exception {
        assertThatCode(() -> guard.check("https://93.184.216.34/help")).doesNotThrowAnyException();
        assertThat(SourceGuard.isPublic(InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(SourceGuard.isPublic(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946"))).isTrue();
        assertThat(SourceGuard.isPublic(InetAddress.getByName("192.0.0.8"))).isFalse();
        assertThat(SourceGuard.isPublic(InetAddress.getByName("240.1.1.1"))).isFalse();
        assertThatCode(() -> new SourceGuard(true).check("http://localhost:1234/page")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the chunk language is Chinese when a fifth of the letters are; the entry id prefix is stable per source")
    void languageAndHash() {
        assertThat(KnowledgeImporter.languageOf("退货有时间限制吗？ 30 天内可以退。")).isEqualTo("zh");
        assertThat(KnowledgeImporter.languageOf("Returns are accepted within 30 days.")).isEqualTo("en");
        assertThat(KnowledgeImporter.languageOf("Order ORD-1 (订单) shipped")).isEqualTo("en");
        assertThat(KnowledgeImporter.hash8("https://example.com/help")).hasSize(8)
                .isEqualTo(KnowledgeImporter.hash8("https://example.com/help"))
                .isNotEqualTo(KnowledgeImporter.hash8("https://example.com/help2"));
    }
}
