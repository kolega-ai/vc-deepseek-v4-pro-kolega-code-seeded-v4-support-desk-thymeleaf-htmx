package com.relaydesk.channel.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyRow(
        UUID id,
        String name,
        String prefix,
        String ownerEmail,
        String scopes,
        boolean enabled,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant createdAt) {
}
