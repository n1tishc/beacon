package dev.beacon.auth.api;

import java.util.UUID;

import dev.beacon.auth.user.User;
import dev.beacon.security.Role;

public record UserResponse(UUID id, String username, String displayName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
