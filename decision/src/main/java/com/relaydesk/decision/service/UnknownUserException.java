package com.relaydesk.decision.service;

/** Thrown when a verified token references a user that does not exist in decision's database (→ 404). */
public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String message) {
        super(message);
    }
}
