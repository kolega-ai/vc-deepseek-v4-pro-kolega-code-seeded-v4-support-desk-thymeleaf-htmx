package com.relaydesk.channel.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageRow(
        UUID id,
        String authorName,
        String authorRole,
        String body,
        boolean internal,
        Instant createdAt) {
}
