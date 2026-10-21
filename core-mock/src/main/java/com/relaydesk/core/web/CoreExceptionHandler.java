package com.relaydesk.core.web;

import com.relaydesk.core.service.ContactNotFoundException;
import com.relaydesk.core.service.OrgNotFoundException;
import com.relaydesk.core.service.ScopeDeniedException;
import com.relaydesk.shared.TokenException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps failures to the vendor system's own error codes — external-system behavior: callers
 * switch on the problem+json {@code code} (CORE_1xxx) rather than on the HTTP status alone.
 */
@RestControllerAdvice
public class CoreExceptionHandler {

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    @ExceptionHandler(OrgNotFoundException.class)
    ResponseEntity<Problem> orgNotFound(OrgNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "CORE_1001", "organization not found", e.getMessage());
    }

    @ExceptionHandler(ScopeDeniedException.class)
    ResponseEntity<Problem> scopeDenied(ScopeDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "CORE_1002", "scope denied", e.getMessage());
    }

    @ExceptionHandler(TokenException.class)
    ResponseEntity<Problem> invalidToken(TokenException e) {
        return problem(HttpStatus.FORBIDDEN, "CORE_1003", "invalid scope token", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> bodyValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "CORE_1004", "validation failed", detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> constraintValidation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "CORE_1004", "validation failed", detail);
    }

    @ExceptionHandler(ContactNotFoundException.class)
    ResponseEntity<Problem> contactNotFound(ContactNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "CORE_1006", "contact not found", e.getMessage());
    }

    private static ResponseEntity<Problem> problem(HttpStatus status, String code, String message, String detail) {
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(new Problem(code, message, detail));
    }
}
