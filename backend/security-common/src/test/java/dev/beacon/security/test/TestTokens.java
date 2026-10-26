package dev.beacon.security.test;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import dev.beacon.security.BeaconClaims;
import dev.beacon.security.Role;

/**
 * Mints real RS256 tokens shaped exactly like auth-service's, for tests in other modules.
 * Serve {@link #jwksJson()} from a stub (e.g. WireMock) and point {@code jwk-set-uri} at it,
 * so the service under test validates tokens through its real decoder and converter.
 */
public final class TestTokens {

    public static final String ISSUER = "beacon-auth";

    private final RSAKey key;
    private final NimbusJwtEncoder encoder;

    private TestTokens(RSAKey key) {
        this.key = key;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    /** A fresh key pair; tokens from different instances do not validate against each other. */
    public static TestTokens withNewKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new TestTokens(new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public JWK Set document, as auth-service serves it at /.well-known/jwks.json. */
    public String jwksJson() {
        return new JWKSet(key.toPublicJWK()).toString();
    }

    public String forRole(Role role) {
        return token(role, claims -> {
        });
    }

    /** A valid token for {@code role}; {@code customizer} can then break it (issuer, expiry, ...). */
    public String token(Role role, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim(BeaconClaims.USERNAME, role.name().toLowerCase())
                .claim(BeaconClaims.DISPLAY_NAME, "Test " + role.name().toLowerCase())
                .claim(BeaconClaims.ROLES, List.of(role.name()));
        customizer.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
