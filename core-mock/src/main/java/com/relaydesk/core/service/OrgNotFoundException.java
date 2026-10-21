package com.relaydesk.core.service;

/** Unknown organization id → 404 CORE_1001. */
public class OrgNotFoundException extends RuntimeException {
    public OrgNotFoundException(String message) {
        super(message);
    }
}
