package dev.beacon.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.nimbusds.jose.jwk.RSAKey;

import dev.beacon.auth.AuthProperties;
import dev.beacon.auth.user.User;
import dev.beacon.security.BeaconResourceServerConfiguration;
import dev.beacon.security.Role;

/**
 * Contract between the issuer and every consumer: a token minted here must turn into the right
 * principal and authorities when read by security-common's converter. Catches claim-name drift
 * that unit tests on either side alone would miss.
 */
class AccessTokenContractTest {

    private static final Instant NOW = Instant.now();
    private final AuthProperties properties = new AuthProperties("beacon-auth", Duration.ofMinutes(15),
            Duration.ofDays(7), true, null, new AuthProperties.DemoUsers(false, null));
    private final SigningKeyConfig keys = new SigningKeyConfig();
    private final RSAKey key = keys.signingKey(properties);

    @Test
    void issuedTokensBecomeTheRightPrincipalAndAuthoritiesInResourceServers() throws Exception {
        User user = user("riley", Role.RESPONDER);
        String token = issuer(Clock.fixed(NOW, ZoneOffset.UTC)).issue(user).value();

        Jwt jwt = keys.jwtDecoder(key, properties).decode(token);
        var authentication = new BeaconResourceServerConfiguration().jwtAuthenticationConverter().convert(jwt);

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("beacon-auth");
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.SECONDS));
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID());
        assertThat(authentication.getName()).isEqualTo("riley");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RESPONDER");
    }

    @Test
    void expiredTokensAreRejected() throws Exception {
        String token = issuer(Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC))
                .issue(user("val", Role.VIEWER)).value();
        JwtDecoder decoder = keys.jwtDecoder(key, properties);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private AccessTokenIssuer issuer(Clock clock) {
        return new AccessTokenIssuer(keys.jwtEncoder(key), properties, clock);
    }

    private static User user(String username, Role role) {
        User user = new User(username, "Test " + username, "{noop}unused", role, NOW);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
