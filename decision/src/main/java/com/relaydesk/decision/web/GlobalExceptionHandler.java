package com.relaydesk.decision.web;

import com.relaydesk.decision.service.DisabledUserException;
import com.relaydesk.decision.service.DuplicateEmailException;
import com.relaydesk.decision.service.InvalidRoleException;
import com.relaydesk.decision.service.UnknownUserException;
import com.relaydesk.shared.TokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps decision-tier failures to stable JSON error bodies. All responses are application/json. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ErrorBody> token(TokenException e) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_token", e.getMessage());
    }

    @ExceptionHandler(DisabledUserException.class)
    public ResponseEntity<ErrorBody> disabled(DisabledUserException e) {
        return error(HttpStatus.FORBIDDEN, "account_disabled", e.getMessage());
    }

    @ExceptionHandler(UnknownUserException.class)
    public ResponseEntity<ErrorBody> unknownUser(UnknownUserException e) {
        return error(HttpStatus.NOT_FOUND, "unknown_user", e.getMessage());
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<ErrorBody> invalidRole(InvalidRoleException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_role", e.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorBody> duplicateEmail(DuplicateEmailException e) {
        return error(HttpStatus.CONFLICT, "duplicate_email", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> other(Exception e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal server error");
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(error, message));
    }

    public record ErrorBody(String error, String message) {
    }
}
