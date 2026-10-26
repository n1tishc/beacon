package dev.beacon.auth.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.beacon.auth.user.User;
import dev.beacon.auth.user.UserService;

/** User administration. There is no self-signup: admins create accounts. */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserResponse> list() {
        return users.list().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User user = users.create(request.username(), request.displayName(), request.password(), request.role());
        return ResponseEntity.created(URI.create("/api/users/" + user.getId())).body(UserResponse.from(user));
    }
}
