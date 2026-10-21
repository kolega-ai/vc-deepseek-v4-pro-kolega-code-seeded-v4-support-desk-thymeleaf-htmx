package com.relaydesk.decision.service;

/** Thrown when a provisioning request uses an email already bound to another principal (→ 409). */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String message) {
        super(message);
    }
}
