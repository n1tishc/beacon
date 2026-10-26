package dev.beacon.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param issuer          value of the {@code iss} claim; resource servers reject any other
 * @param accessTokenTtl  short, because access tokens cannot be revoked
 * @param refreshTokenTtl how long a session survives without activity
 * @param secureCookie    sets {@code Secure} on the refresh cookie; only disable for plain-http local dev
 * @param signingKey      PKCS#8 PEM RSA private key; when blank an ephemeral key is generated at startup
 * @param demoUsers       seeds admin/responder/viewer accounts for local use and the public demo
 */
@ConfigurationProperties("beacon.auth")
public record AuthProperties(
        @DefaultValue("beacon-auth") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl,
        @DefaultValue("true") boolean secureCookie,
        String signingKey,
        @DefaultValue DemoUsers demoUsers) {

    public record DemoUsers(@DefaultValue("false") boolean enabled, String password) {
    }
}
