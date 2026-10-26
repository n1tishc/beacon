package dev.beacon.security;

/**
 * Beacon's roles, most privileged first. Each role implies the ones below it
 * (see {@link BeaconResourceServerConfiguration#roleHierarchy()}).
 */
public enum Role {
    /** Manages users, plus everything a responder can do. */
    ADMIN,
    /** Triggers, acknowledges and resolves incidents. */
    RESPONDER,
    /** Read-only access. */
    VIEWER;

    /** The Spring Security authority for this role, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
