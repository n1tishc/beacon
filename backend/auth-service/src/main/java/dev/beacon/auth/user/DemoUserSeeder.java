package dev.beacon.auth.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import dev.beacon.auth.AuthProperties;
import dev.beacon.security.Role;

/**
 * Creates one account per role so the app is usable straight after {@code docker compose up}.
 * The password comes from the environment (DEMO_USER_PASSWORD) and is never in the repo.
 * Existing users are left untouched, so restarts are harmless.
 */
@Component
@ConditionalOnProperty(name = "beacon.auth.demo-users.enabled", havingValue = "true")
class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private record DemoUser(String username, String displayName, Role role) {
    }

    private static final DemoUser[] DEMO_USERS = {
            new DemoUser("admin", "Avery Admin", Role.ADMIN),
            new DemoUser("responder", "Riley Responder", Role.RESPONDER),
            new DemoUser("viewer", "Val Viewer", Role.VIEWER),
    };

    private final UserService userService;
    private final AuthProperties properties;

    DemoUserSeeder(UserService userService, AuthProperties properties) {
        this.userService = userService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.demoUsers().password();
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException("beacon.auth.demo-users.password (DEMO_USER_PASSWORD) must be set when demo users are enabled");
        }
        for (DemoUser demo : DEMO_USERS) {
            if (!userService.exists(demo.username())) {
                userService.create(demo.username(), demo.displayName(), password, demo.role());
                log.info("Created demo user '{}' ({})", demo.username(), demo.role());
            }
        }
    }
}
