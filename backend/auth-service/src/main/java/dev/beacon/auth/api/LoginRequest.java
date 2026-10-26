package dev.beacon.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 128) String password) {

    /** Keeps the password out of logs if a request object is ever printed. */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
