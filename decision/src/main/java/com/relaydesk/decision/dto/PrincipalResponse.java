package com.relaydesk.decision.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The resolved principal, built exclusively from decision's own database after the signed
 * token verified. Serialized with exactly these field names.
 */
public record PrincipalResponse(
        UUID userId,
        String email,
        String displayName,
        String role,
        boolean enabled,
        List<UUID> orgIds,
        List<UUID> teamIds,
        Map<String, Integer> limits,
        String sessionId) {
}
