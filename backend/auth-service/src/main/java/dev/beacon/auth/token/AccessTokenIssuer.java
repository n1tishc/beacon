package dev.beacon.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import dev.beacon.auth.AuthProperties;
import dev.beacon.auth.user.User;
import dev.beacon.security.BeaconClaims;

/** Signs short-lived access tokens. Claim names come from security-common, shared with every consumer. */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(BeaconClaims.USERNAME, user.getUsername())
                .claim(BeaconClaims.DISPLAY_NAME, user.getDisplayName())
                .claim(BeaconClaims.ROLES, List.of(user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), now, expiresAt);
    }

    public record AccessToken(String value, Instant issuedAt, Instant expiresAt) {
    }
}
