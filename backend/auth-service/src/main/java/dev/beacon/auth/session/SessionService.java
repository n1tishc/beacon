package dev.beacon.auth.session;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import dev.beacon.auth.token.AccessTokenIssuer;
import dev.beacon.auth.token.AccessTokenIssuer.AccessToken;
import dev.beacon.auth.token.InvalidRefreshTokenException;
import dev.beacon.auth.token.RefreshTokenService;
import dev.beacon.auth.user.AuthenticatedUser;
import dev.beacon.auth.user.User;
import dev.beacon.auth.user.UserService;

/** Login, refresh and logout. A session is an access token plus the refresh token that renews it. */
@Service
public class SessionService {

    private final AuthenticationManager authenticationManager;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;
    private final UserService users;

    SessionService(AuthenticationManager authenticationManager, AccessTokenIssuer accessTokens,
                   RefreshTokenService refreshTokens, UserService users) {
        this.authenticationManager = authenticationManager;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.users = users;
    }

    /**
     * Checks the password via Spring Security's DaoAuthenticationProvider, which also spends the
     * same hashing time on unknown usernames so response times do not reveal which accounts exist.
     *
     * @throws AuthenticationException for any bad username/password combination
     */
    public Session login(String username, String password) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        User user = ((AuthenticatedUser) authentication.getPrincipal()).user();
        return new Session(user, accessTokens.issue(user), refreshTokens.issue(user.getId()));
    }

    /** @throws InvalidRefreshTokenException if the token is unknown, expired, revoked or reused */
    public Session refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken);
        User user = users.findById(rotation.userId()).orElseThrow(InvalidRefreshTokenException::new);
        return new Session(user, accessTokens.issue(user), rotation.refreshToken());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    public record Session(User user, AccessToken accessToken, String refreshToken) {
    }
}
