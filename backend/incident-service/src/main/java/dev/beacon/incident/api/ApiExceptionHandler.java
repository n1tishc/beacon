package dev.beacon.incident.api;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.beacon.incident.domain.IllegalTransitionException;
import dev.beacon.incident.domain.IncidentNotFoundException;
import dev.beacon.incident.domain.StaleIncidentException;

/**
 * Turns every error the API can produce into an RFC 7807 problem response.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} covers Spring MVC's own exceptions (bad JSON,
 * unknown routes, validation). The handlers below add Beacon's domain errors. There is deliberately
 * no catch-all {@code Exception} handler: it would also catch Spring Security's
 * {@code AccessDeniedException} and turn a 403 into a 500.
 *
 * <p>Both 409 cases share the title "Incident changed" when the fix is the same (reload and try
 * again); an illegal transition gets its own title because reloading will not help.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String INCIDENT_CHANGED = "Incident changed";

    /** One invalid field, by its JSON name, so a client can show the message next to that field. */
    public record InvalidField(String field, String message) {
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    ProblemDetail incidentNotFound(IncidentNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Incident not found", exception.getMessage());
    }

    @ExceptionHandler(StaleIncidentException.class)
    ProblemDetail staleIncident(StaleIncidentException exception) {
        return problem(HttpStatus.CONFLICT, INCIDENT_CHANGED, exception.getMessage());
    }

    /** Two commands raced past the version check; the database's version check stopped the second one. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException exception) {
        return problem(HttpStatus.CONFLICT, INCIDENT_CHANGED,
                "Someone else changed this incident at the same moment. Reload it and try again.");
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(IllegalTransitionException exception) {
        return problem(HttpStatus.CONFLICT, "Transition not allowed", exception.getMessage());
    }

    /** A {@code @Valid @RequestBody} failed Bean Validation. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> errors = exception.getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(exception, invalidRequest(errors), headers, status, request);
    }

    /**
     * Method-level validation failed: a constrained {@code @RequestParam} such as {@code size}, or a
     * request body on a method that also has constrained parameters.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<InvalidField> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> switch (result) {
                    case ParameterErrors body -> body.getFieldErrors().stream()
                            .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()));
                    default -> result.getResolvableErrors().stream()
                            .map(error -> new InvalidField(result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage()));
                })
                .toList();
        return handleExceptionInternal(exception, invalidRequest(errors), headers, status, request);
    }

    private static ProblemDetail invalidRequest(List<InvalidField> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Some fields are missing or invalid.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
