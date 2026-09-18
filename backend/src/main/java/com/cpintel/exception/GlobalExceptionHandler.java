package com.cpintel.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    record ErrorResponse(String code, String message, Instant timestamp, Map<String, String> errors) {
        ErrorResponse(String code, String message) {
            this(code, message, Instant.now(), null);
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        log.warn("API exception [{}]: {}", ex.getCode(), ex.getMessage());

        var response = ResponseEntity.status(ex.getStatus());

        // A 429 without Retry-After tells a client it is being throttled but not for how long,
        // which leaves it to guess — and a client that guesses badly retries into the same wall.
        // The seconds are already in the message; this puts them where a client can read them.
        if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            response = response.header(HttpHeaders.RETRY_AFTER,
                String.valueOf(retryAfterFrom(ex.getMessage())));
        }

        return response.body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    /** Pulls the countdown back out of the message, falling back to a minute if it is not there. */
    private long retryAfterFrom(String message) {
        if (message == null) return 60;
        var matcher = java.util.regex.Pattern.compile("(\\d+) seconds").matcher(message);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 60;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            errors.put(field, error.getDefaultMessage());
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_ERROR", "Validation failed", Instant.now(), errors));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse("INVALID_CREDENTIALS", "Invalid email or password"));
    }

    /**
     * The container rejected the body before any controller saw it, so the file service's own
     * friendlier limit message never got a chance to run. Say something useful anyway rather
     * than letting this surface as a 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(new ErrorResponse("FILE_TOO_LARGE", "That upload is too large."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("FORBIDDEN", "Access denied"));
    }

    /**
     * A path that matches no controller.
     *
     * Spring raises this as an ordinary exception, so the catch-all below used to turn every
     * typo'd URL into a 500 with a full stack trace at ERROR. That cost twice: a client could
     * not tell a wrong path from a broken server, and routine 404 traffic filled the log that
     * real incidents get read from.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        log.debug("No handler for request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("NOT_FOUND", "No such endpoint"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
