package dev.beacon.auth.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.beacon.auth.session.SessionService;
import dev.beacon.auth.session.SessionService.Session;
import dev.beacon.auth.token.InvalidRefreshTokenException;
import dev.beacon.auth.user.UserService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SessionService sessions;
    private final UserService users;
    private final RefreshTokenCookies cookies;

    public AuthController(SessionService sessions, UserService users, RefreshTokenCookies cookies) {
        this.sessions = sessions;
        this.users = users;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(sessions.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<SessionResponse> refresh(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }
        return withRefreshCookie(sessions.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessions.logout(refreshToken);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.of(users.findById(UUID.fromString(jwt.getSubject())).map(UserResponse::from));
    }

    private ResponseEntity<SessionResponse> withRefreshCookie(Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken()))
                .body(SessionResponse.from(session));
    }
}
