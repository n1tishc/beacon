package dev.beacon.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class BeaconResourceServerConfigurationTest {

    private final BeaconResourceServerConfiguration configuration = new BeaconResourceServerConfiguration();

    @Test
    void convertsTheRolesClaimToRoleAuthoritiesAndUsesTheUsernameAsPrincipal() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("8a7f0c1e-0000-0000-0000-000000000000")
                .claim(BeaconClaims.USERNAME, "riley")
                .claim(BeaconClaims.ROLES, List.of("RESPONDER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authentication = configuration.jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("riley");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RESPONDER");
    }

    @Test
    void eachRoleImpliesTheLessPrivilegedOnes() {
        RoleHierarchy hierarchy = BeaconResourceServerConfiguration.roleHierarchy();

        assertThat(reachable(hierarchy, Role.ADMIN)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_RESPONDER", "ROLE_VIEWER");
        assertThat(reachable(hierarchy, Role.RESPONDER)).containsExactlyInAnyOrder("ROLE_RESPONDER", "ROLE_VIEWER");
        assertThat(reachable(hierarchy, Role.VIEWER)).containsExactly("ROLE_VIEWER");
    }

    private static List<String> reachable(RoleHierarchy hierarchy, Role role) {
        return hierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority(role.authority())))
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
