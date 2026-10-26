package dev.beacon.security;

/** Names of the claims auth-service puts in access tokens, beyond the registered ones (iss, sub, exp...). */
public final class BeaconClaims {

    /** Array of {@link Role} names, e.g. {@code ["RESPONDER"]}. */
    public static final String ROLES = "roles";

    /** The user's login name; becomes {@code Authentication#getName()} in resource servers. */
    public static final String USERNAME = "preferred_username";

    /** Human-readable name for display. */
    public static final String DISPLAY_NAME = "name";

    private BeaconClaims() {
    }
}
