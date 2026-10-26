package dev.beacon.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.auth.AuthProperties;

/**
 * Opaque, single-use refresh tokens.
 * <ul>
 *   <li>The client gets 256 random bits; the database stores only their SHA-256 hash.</li>
 *   <li>Every refresh <em>rotates</em> the token: the old one is revoked and a new one issued.</li>
 *   <li>Presenting an already-rotated token means two parties hold it, i.e. it was probably stolen.
 *       All of that user's sessions are then revoked, so both the thief and the user must log in again.</li>
 * </ul>
 */
@Service
@Transactional
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final AuthProperties properties;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new session for the user and returns the raw token to hand to the client. */
    public String issue(UUID userId) {
        String raw = newRawToken();
        Instant now = now();
        tokens.save(new RefreshToken(userId, hash(raw), now, now.plus(properties.refreshTokenTtl())));
        return raw;
    }

    /**
     * Exchanges a valid refresh token for a new one.
     * Does not roll back on {@link InvalidRefreshTokenException}: revoking a compromised user's sessions must stick.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String raw) {
        Instant now = now();
        RefreshToken current = tokens.findByTokenHash(hash(raw)).orElseThrow(InvalidRefreshTokenException::new);
        if (current.isRevoked()) {
            int revoked = tokens.revokeAllActiveForUser(current.getUserId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} active session(s)", current.getUserId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (current.isExpiredAt(now)) {
            throw new InvalidRefreshTokenException();
        }
        current.revoke(now);
        return new Rotation(current.getUserId(), issue(current.getUserId()));
    }

    /** Ends one session. Unknown or already-revoked tokens are ignored, so logout is idempotent. */
    public void revoke(String raw) {
        tokens.findByTokenHash(hash(raw)).ifPresent(token -> token.revoke(now()));
    }

    public record Rotation(UUID userId, String refreshToken) {
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
