package com.relaydesk.decision.service;

/** Thrown when a provisioning request carries a role outside the four {@code Role} values (→ 422). */
public class InvalidRoleException extends RuntimeException {

    public InvalidRoleException(String message) {
        super(message);
    }
}
