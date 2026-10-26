package dev.beacon.auth.api;

import java.time.Duration;

import dev.beacon.auth.session.SessionService.Session;

/**
 * Body of login and refresh responses. The refresh token is deliberately absent: it travels only
 * in an HttpOnly cookie, out of reach of JavaScript.
 *
 * @param expiresIn seconds until the access token expires
 */
public record SessionResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    public static SessionResponse from(Session session) {
        long expiresIn = Duration.between(session.accessToken().issuedAt(), session.accessToken().expiresAt()).toSeconds();
        return new SessionResponse(session.accessToken().value(), "Bearer", expiresIn, UserResponse.from(session.user()));
    }
}
