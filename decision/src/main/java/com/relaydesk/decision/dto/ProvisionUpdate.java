package com.relaydesk.decision.dto;

import java.util.List;
import java.util.UUID;

/** Partial update of a provisioned principal; null = unchanged. */
public record ProvisionUpdate(
        String displayName,
        String role,
        Boolean enabled,
        List<UUID> orgIds,
        List<UUID> teamIds) {
}
