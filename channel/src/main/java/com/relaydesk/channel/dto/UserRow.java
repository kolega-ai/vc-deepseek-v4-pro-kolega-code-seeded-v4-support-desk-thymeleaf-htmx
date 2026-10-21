package com.relaydesk.channel.dto;

import com.relaydesk.shared.Role;

import java.time.Instant;
import java.util.UUID;

public record UserRow(
        UUID id,
        String email,
        String displayName,
        Role role,
        boolean enabled,
        String teamNames,
        String orgName,
        Instant createdAt) {
}
