package com.relaydesk.channel.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationRow(
        UUID id,
        String type,
        String title,
        String message,
        String link,
        boolean read,
        Instant createdAt) {
}
