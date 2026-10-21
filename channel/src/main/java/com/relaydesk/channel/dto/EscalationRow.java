package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.EscalationStatus;

import java.time.Instant;
import java.util.UUID;

public record EscalationRow(
        UUID id,
        UUID ticketId,
        String ticketRef,
        String ticketSubject,
        String requesterName,
        String assigneeName,
        String teamName,
        EscalationStatus status,
        String reason,
        boolean slaException,
        Instant createdAt,
        Instant updatedAt) {
}
