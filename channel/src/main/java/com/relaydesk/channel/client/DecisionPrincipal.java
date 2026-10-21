package com.relaydesk.channel.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire DTO mirrored from decision's {@code POST /internal/auth/validate} response
 * (flat shape: orgIds/teamIds/limits at the top level).
 * Role/scope/limits come exclusively from decision — nothing in this record is ever
 * taken from request headers, paths, queries or bodies.
 */
public record DecisionPrincipal(
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
