package dev.merlionos.customerservice.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A connector's access token at rest. With {@code app.connectors.secret-key} (32 bytes,
 * base64), AES-256-GCM with a fresh nonce per value, stored as {@code enc:<base64>}; without
 * it, stored as {@code plain:<token>} and said so at startup, because a laptop demo should
 * not have to mint a key and a deployment holding a customer's store token should.
 */
@Component
public class ConnectorSecrets {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSecrets.class);
    private static final String ENC = "enc:";
    private static final String PLAIN = "plain:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @org.springframework.beans.factory.annotation.Autowired
    public ConnectorSecrets(ConnectorProperties properties) {
        this(properties.secretKey());
    }

    ConnectorSecrets(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            log.warn("app.connectors.secret-key (ORDER_CONNECTOR_KEY) is unset: connector access tokens are stored as given. "
                    + "Set a 32-byte base64 key before a tenant's real store token is configured.");
        }
        else {
            byte[] bytes = Base64.getDecoder().decode(base64Key.strip());
            if (bytes.length != 32) {
                throw new IllegalStateException("app.connectors.secret-key must be 32 bytes, base64; got " + bytes.length + " bytes");
            }
            this.key = new SecretKeySpec(bytes, "AES");
        }
    }

    public boolean encrypting() {
        return key != null;
    }

    public String seal(String secret) {
        if (key == null) {
            return PLAIN + secret;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return ENC + Base64.getEncoder().encodeToString(out);
        }
        catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads either form, so a key can be introduced after a plain token was stored. */
    public String open(String stored) {
        if (stored.startsWith(PLAIN)) {
            return stored.substring(PLAIN.length());
        }
        if (!stored.startsWith(ENC)) {
            throw new IllegalStateException("a stored connector token is neither plain: nor enc:");
        }
        if (key == null) {
            throw new IllegalStateException("a connector token is encrypted but app.connectors.secret-key is unset");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(ENC.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            return new String(cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        }
        catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("a stored connector token could not be decrypted; was the key changed?", e);
        }
    }
}
