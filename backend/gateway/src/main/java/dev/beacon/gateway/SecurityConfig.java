package dev.beacon.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * The gateway rejects requests without a valid access token before they reach any service.
 * Services still validate the token themselves and enforce roles (defence in depth); the gateway
 * only checks that the caller is authenticated.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Bearer tokens are not sent automatically by browsers, so CSRF does not apply to them.
                // The refresh cookie is SameSite=Strict and scoped to /api/auth (see auth-service).
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .pathMatchers("/actuator/health/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
