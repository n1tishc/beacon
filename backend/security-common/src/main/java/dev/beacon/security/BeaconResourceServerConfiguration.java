package dev.beacon.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Beans every servlet resource server imports so that all services read tokens the same way.
 * Usage: {@code @Import(BeaconResourceServerConfiguration.class)} on the service's security config.
 */
@Configuration(proxyBeanMethods = false)
public class BeaconResourceServerConfiguration {

    /**
     * Maps the {@code roles} claim to {@code ROLE_*} authorities and uses the username as the principal name.
     * Spring's default converter would read the {@code scope} claim instead and find no roles.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(BeaconClaims.ROLES);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(BeaconClaims.USERNAME);
        return converter;
    }

    /**
     * ADMIN implies RESPONDER, which implies VIEWER, so endpoints declare the minimum role they need.
     * Picked up automatically by both URL and method security.
     */
    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.RESPONDER.name())
                .role(Role.RESPONDER.name()).implies(Role.VIEWER.name())
                .build();
    }
}
