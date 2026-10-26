package dev.beacon.auth.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.beacon.auth.token.InvalidRefreshTokenException;

/** Turns authentication failures into RFC 7807 problem responses. */
@RestControllerAdvice
class AuthExceptionHandler {

    private final RefreshTokenCookies cookies;

    AuthExceptionHandler(RefreshTokenCookies cookies) {
        this.cookies = cookies;
    }

    /** One message for every failure, so the response never reveals whether a username exists. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail loginFailed() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        problem.setTitle("Login failed");
        return problem;
    }

    /** Also clears the cookie, so the browser stops sending a token that will never work again. */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ProblemDetail> refreshFailed() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Your session has ended. Please sign in again.");
        problem.setTitle("Session expired");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cookies.clear())
                .body(problem);
    }
}
