package com.relaydesk.core.service;

/**
 * The verified scope token does not permit the requested action (customer touching a
 * foreign org, or a non-admin mutation) → 403 CORE_1002.
 */
public class ScopeDeniedException extends RuntimeException {
    public ScopeDeniedException(String message) {
        super(message);
    }
}
