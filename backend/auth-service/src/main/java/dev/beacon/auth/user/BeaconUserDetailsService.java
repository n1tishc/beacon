package dev.beacon.auth.user;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/** Lets Spring Security's DaoAuthenticationProvider check passwords against our users table. */
@Component
class BeaconUserDetailsService implements UserDetailsService {

    private final UserService userService;

    BeaconUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userService.findByUsername(username)
                .map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }
}
