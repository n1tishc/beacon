package dev.beacon.auth.token;

/** The refresh token is unknown, expired, or revoked. The client must log in again. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token is invalid or expired");
    }
}
