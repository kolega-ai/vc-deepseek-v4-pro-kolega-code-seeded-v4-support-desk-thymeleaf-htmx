package com.relaydesk.channel.dto;

import java.time.Instant;

public record AuditRow(
        Long id,
        Instant occurredAt,
        String actorEmail,
        String action,
        String resourceType,
        String resourceRef,
        String beforeValue,
        String afterValue,
        String authzBasis) {
}
