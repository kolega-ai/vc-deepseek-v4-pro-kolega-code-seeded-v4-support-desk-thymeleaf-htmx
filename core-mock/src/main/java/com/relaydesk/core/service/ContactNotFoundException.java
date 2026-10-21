package com.relaydesk.core.service;

/** Unknown contact id → 404 CORE_1006. */
public class ContactNotFoundException extends RuntimeException {
    public ContactNotFoundException(String message) {
        super(message);
    }
}
