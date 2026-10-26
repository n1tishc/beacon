package dev.beacon.auth.api;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import dev.beacon.auth.AuthProperties;

/**
 * The refresh token cookie:
 * <ul>
 *   <li>{@code HttpOnly}: JavaScript (and so an XSS payload) cannot read it.</li>
 *   <li>{@code SameSite=Strict}: never sent on cross-site requests, which is what makes the
 *       cookie-authenticated refresh/logout endpoints safe without a CSRF token.</li>
 *   <li>{@code Path=/api/auth}: sent only to refresh/logout, not with every API call.</li>
 * </ul>
 */
@Component
public class RefreshTokenCookies {

    public static final String NAME = "beacon_refresh";
    static final String PATH = "/api/auth";

    private final AuthProperties properties;

    RefreshTokenCookies(AuthProperties properties) {
        this.properties = properties;
    }

    public String issue(String refreshToken) {
        return build(refreshToken, properties.refreshTokenTtl());
    }

    /** Expires the cookie. Path and attributes must match the original or the browser keeps it. */
    public String clear() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
