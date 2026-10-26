package dev.beacon.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Wires the {@link InMemoryRateLimiter} and the two ways of identifying a client.
 * Routes pick a key resolver in application.yml; the {@code @Primary} one is the default.
 */
@Configuration
class RateLimitConfig {

    @Bean
    InMemoryRateLimiter inMemoryRateLimiter(RateLimitProperties properties) {
        return new InMemoryRateLimiter(properties);
    }

    /**
     * Behind a proxy every request arrives from the proxy's address, so the client IP is read from
     * X-Forwarded-For. Only the entries appended by our own proxies are trusted; anything further
     * left could be forged by the client. With no X-Forwarded-For it falls back to the socket address.
     */
    @Bean
    RemoteAddressResolver clientAddressResolver(RateLimitProperties properties) {
        return XForwardedRemoteAddressResolver.maxTrustedIndex(properties.trustedProxyHops());
    }

    /** Per user when authenticated (JWT subject), otherwise per client IP. */
    @Bean
    @Primary
    KeyResolver userOrClientIpKeyResolver(RemoteAddressResolver clientAddressResolver) {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(subject -> "user:" + subject)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange, clientAddressResolver)));
    }

    /** Per client IP; used for login, where nobody is authenticated yet. */
    @Bean
    KeyResolver clientIpKeyResolver(RemoteAddressResolver clientAddressResolver) {
        return exchange -> Mono.fromSupplier(() -> "ip:" + clientIp(exchange, clientAddressResolver));
    }

    private static String clientIp(ServerWebExchange exchange, RemoteAddressResolver resolver) {
        InetSocketAddress address = resolver.resolve(exchange);
        return address == null ? "unknown" : address.getHostString();
    }
}
