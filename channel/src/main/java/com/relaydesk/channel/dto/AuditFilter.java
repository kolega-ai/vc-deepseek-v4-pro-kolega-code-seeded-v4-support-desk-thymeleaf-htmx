package com.relaydesk.channel.dto;

import java.time.LocalDate;

public record AuditFilter(
        String actorEmail,
        String action,
        String resourceType,
        String resourceRef,
        LocalDate from,
        LocalDate to) {

    public AuditFilter {
        if (actorEmail != null && actorEmail.isBlank()) {
            actorEmail = null;
        }
        if (action != null && action.isBlank()) {
            action = null;
        }
        if (resourceType != null && resourceType.isBlank()) {
            resourceType = null;
        }
        if (resourceRef != null && resourceRef.isBlank()) {
            resourceRef = null;
        }
    }
}
