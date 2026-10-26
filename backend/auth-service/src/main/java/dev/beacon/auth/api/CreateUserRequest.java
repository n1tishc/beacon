package dev.beacon.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import dev.beacon.security.Role;

public record CreateUserRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}", message = "3-64 letters, digits, '.', '_' or '-'") String username,
        @NotBlank @Size(max = 128) String displayName,
        @NotNull @Size(min = 12, max = 128) String password,
        @NotNull Role role) {

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", role=" + role + "]";
    }
}
