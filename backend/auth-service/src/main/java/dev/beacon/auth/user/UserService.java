package dev.beacon.auth.user;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.beacon.security.Role;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public User create(String username, String displayName, String rawPassword, Role role) {
        String normalized = normalize(username);
        if (users.existsByUsername(normalized)) {
            throw new UsernameTakenException(normalized);
        }
        return users.save(new User(normalized, displayName, passwordEncoder.encode(rawPassword), role,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }

    public List<User> list() {
        return users.findAll(Sort.by("username"));
    }

    public Optional<User> findById(UUID id) {
        return users.findById(id);
    }

    public Optional<User> findByUsername(String username) {
        return users.findByUsername(normalize(username));
    }

    public boolean exists(String username) {
        return users.existsByUsername(normalize(username));
    }

    /** Usernames are case-insensitive: stored and looked up in lower case. */
    static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
