package com.relaydesk.decision.dto;

/**
 * Validation request DTO. This is the ONLY bindable field: any user id, role, scope or limit
 * supplied in headers, paths, queries or extra body fields is ignored by construction.
 */
public record TokenRequest(String token) {
}
