package com.coopaggregate.error;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.coopaggregate.identity.IdentityProviderException;
import com.coopaggregate.member.NationalIdTakenException;

import jakarta.persistence.EntityNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return new ApiError(message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(Exception e) {
        return new ApiError("The request is missing data or has a value in the wrong format.");
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(BadRequestException e) {
        return ApiError.of(e);
    }

    @ExceptionHandler({IllegalArgumentException.class, EntityNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleNotFound(RuntimeException e) {
        return new ApiError(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleBusinessRule(IllegalStateException e) {
        return ApiError.of(e);
    }

    // The identity provider is not set up (503) or failed (502). The message never carries the provider's answer.
    @ExceptionHandler(IdentityProviderException.class)
    public ResponseEntity<ApiError> handleIdentityProvider(IdentityProviderException e) {
        HttpStatus status = e.isNotConfigured() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ApiError.of(e));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleDataConflict(DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        // Two registrations with the same national ID at the same moment: the database refuses the second. The ID is
        // never logged (Postgres would put it in its message), and the member is not known here.
        if (cause.contains(NATIONAL_ID_CONSTRAINT)) {
            log.warn("Data integrity violation: national ID already used (constraint {})", NATIONAL_ID_CONSTRAINT);
            return ApiError.of(new NationalIdTakenException(null));
        }
        log.warn("Data integrity violation: {}", cause);
        return new ApiError("The request conflicts with existing data.");
    }

    // The UNIQUE constraint on member.national_id (V2), as Postgres named it.
    static final String NATIONAL_ID_CONSTRAINT = "member_national_id_key";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // Spring's own web errors (unknown URL, wrong HTTP method, ...) already carry the right status.
        if (e instanceof ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(new ApiError(errorResponse.getBody().getDetail()));
        }
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Something went wrong. Please try again later."));
    }
}
