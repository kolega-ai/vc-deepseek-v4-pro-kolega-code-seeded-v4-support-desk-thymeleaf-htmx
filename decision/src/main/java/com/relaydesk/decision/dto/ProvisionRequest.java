package com.relaydesk.decision.dto;

import java.util.List;
import java.util.UUID;

/**
 * Provisioning request. A privileged mutation authenticated by the channel service key —
 * the request-time authorization path (validate / scope-token) never trusts client identity,
 * but provisioning is the explicit channel-initiated write path for it.
 */
public record ProvisionRequest(
        UUID userId,
        String email,
        String displayName,
        String role,
        List<UUID> orgIds,
        List<UUID> teamIds,
        boolean enabled) {
}
