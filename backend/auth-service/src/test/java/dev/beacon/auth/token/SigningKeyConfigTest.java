package dev.beacon.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.nimbusds.jose.jwk.RSAKey;

import dev.beacon.auth.AuthProperties;

/** The configured-key path (JWT_SIGNING_KEY), as used in any shared environment. */
class SigningKeyConfigTest {

    private final SigningKeyConfig config = new SigningKeyConfig();

    @Test
    void loadsAPkcs8PemKeyAndUsesItToSignAndVerify() throws Exception {
        KeyPair pair = newRsaKeyPair();
        AuthProperties properties = withSigningKey(toPkcs8Pem(pair));

        RSAKey key = config.signingKey(properties);

        assertThat(key.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
        assertThat(key.getKeyID()).isNotBlank();
        String token = config.jwtEncoder(key).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().issuer("beacon-auth").subject("someone")
                        .expiresAt(Instant.now().plusSeconds(60)).build())).getTokenValue();
        assertThat(config.jwtDecoder(key, properties).decode(token).getSubject()).isEqualTo("someone");
    }

    @Test
    void theKeyIdIsStableForTheSameKey() throws Exception {
        String pem = toPkcs8Pem(newRsaKeyPair());

        assertThat(config.signingKey(withSigningKey(pem)).getKeyID())
                .isEqualTo(config.signingKey(withSigningKey(pem)).getKeyID());
    }

    @Test
    void rejectsSomethingThatIsNotAPemKeyWithAClearMessage() {
        assertThatThrownBy(() -> config.signingKey(withSigningKey("not-a-key")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SIGNING_KEY is not a valid PKCS#8 PEM RSA private key");
    }

    private static AuthProperties withSigningKey(String pem) {
        return new AuthProperties("beacon-auth", Duration.ofMinutes(15), Duration.ofDays(7), true, pem,
                new AuthProperties.DemoUsers(false, null));
    }

    private static KeyPair newRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** What `openssl genpkey -algorithm RSA` writes. */
    private static String toPkcs8Pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }
}
