package com.relaydesk.channel.web;

import java.util.UUID;

/**
 * Request-scoped context of an authenticated API key, set by {@link ApiKeyAuthFilter} as the
 * {@code apiKeyOwner} request attribute. Scopes are the raw comma-separated list from the key
 * row; the owner's role/org scope is resolved through decision on the same request.
 */
public record ApiKeyContext(UUID keyId, UUID ownerId, String scopes) {

    public boolean hasScope(String scope) {
        if (scopes == null) {
            return false;
        }
        for (String s : scopes.split(",")) {
            if (s.trim().equals(scope)) {
                return true;
            }
        }
        return false;
    }
}
