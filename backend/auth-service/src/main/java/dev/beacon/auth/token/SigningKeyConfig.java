package dev.beacon.auth.token;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

import dev.beacon.auth.AuthProperties;

/**
 * RS256 signing. Only auth-service holds the private key; every other service verifies tokens
 * with the public half, fetched from {@code /.well-known/jwks.json}. No shared secret exists.
 */
@Configuration
class SigningKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyConfig.class);

    @Bean
    RSAKey signingKey(AuthProperties properties) {
        if (StringUtils.hasText(properties.signingKey())) {
            return fromPem(properties.signingKey());
        }
        log.warn("No JWT_SIGNING_KEY configured; generated an ephemeral RSA key. Access tokens will not survive a restart "
                + "(refresh tokens will, so clients just refresh). Configure a key for any shared environment.");
        return generate();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }

    /** auth-service validates its own tokens (for /me and /api/users) without an HTTP round trip. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, AuthProperties properties) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static RSAKey fromPem(String pem) {
        try {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8()
                    .convert(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
            // A PKCS#8 RSA key carries the modulus and public exponent, so the public key can be derived.
            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) privateKey;
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return withThumbprintId(new RSAKey.Builder(publicKey).privateKey(privateKey));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("JWT_SIGNING_KEY is not a valid PKCS#8 PEM RSA private key", e);
        }
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return withThumbprintId(new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key id is derived from the key itself, so it changes exactly when the key does. */
    private static RSAKey withThumbprintId(RSAKey.Builder builder) {
        try {
            return builder.keyIDFromThumbprint().build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
