package dev.merlionos.customerservice.orders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorSecretsTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("with a key a token is sealed with a fresh nonce each time and opens to itself; without one it is stored as given")
    void sealAndOpen() {
        ConnectorSecrets sealed = new ConnectorSecrets(KEY);
        String a = sealed.seal("shpat_secret");
        String b = sealed.seal("shpat_secret");
        assertThat(sealed.encrypting()).isTrue();
        assertThat(a).startsWith("enc:").doesNotContain("shpat").isNotEqualTo(b);
        assertThat(sealed.open(a)).isEqualTo("shpat_secret");
        assertThat(sealed.open(b)).isEqualTo("shpat_secret");

        ConnectorSecrets plain = new ConnectorSecrets("");
        assertThat(plain.encrypting()).isFalse();
        assertThat(plain.seal("shpat_secret")).isEqualTo("plain:shpat_secret");
        assertThat(plain.open("plain:shpat_secret")).isEqualTo("shpat_secret");
        assertThat(sealed.open("plain:shpat_secret")).as("a key can be introduced after plain tokens were stored").isEqualTo("shpat_secret");
        assertThatThrownBy(() -> plain.open(a)).hasMessageContaining("secret-key is unset");
        assertThatThrownBy(() -> new ConnectorSecrets(Base64.getEncoder().encodeToString(new byte[16]))).hasMessageContaining("32 bytes");
    }
}
