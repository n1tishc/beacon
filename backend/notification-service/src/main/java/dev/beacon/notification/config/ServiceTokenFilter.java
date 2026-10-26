package dev.beacon.notification.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates incident-service by the shared token it sends in {@value #HEADER}. A missing or
 * wrong token leaves the request anonymous, and the filter chain answers 401.
 *
 * <p>A shared secret is the simplest thing that works for one caller. With more services, the next
 * step would be short-lived service tokens from auth-service (OAuth2 client credentials).
 */
class ServiceTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Beacon-Service-Token";
    static final String ROLE = "SERVICE";

    private final byte[] expectedToken;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    ServiceTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        // Constant-time comparison, so response timing reveals nothing about the expected token.
        if (token != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedToken)) {
            SecurityContext context = contexts.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "incident-service", null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
            contexts.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
