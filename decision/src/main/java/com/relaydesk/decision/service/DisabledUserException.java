package com.relaydesk.decision.service;

/** Thrown when a verified token references a disabled user (→ 403). */
public class DisabledUserException extends RuntimeException {

    public DisabledUserException(String message) {
        super(message);
    }
}
