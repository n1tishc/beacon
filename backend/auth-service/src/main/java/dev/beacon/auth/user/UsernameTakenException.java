package dev.beacon.auth.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

public class UsernameTakenException extends ErrorResponseException {

    public UsernameTakenException(String username) {
        super(HttpStatus.CONFLICT);
        setTitle("Username taken");
        setDetail("A user named '" + username + "' already exists");
    }
}
