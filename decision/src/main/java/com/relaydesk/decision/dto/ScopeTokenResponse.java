package com.relaydesk.decision.dto;

/** Short-lived scope token minted for channel → core-mock calls. {@code expiresAt} is epoch seconds. */
public record ScopeTokenResponse(String token, long expiresAt) {
}
