package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record TicketSummaryRow(
        UUID id,
        String ref,
        String subject,
        String orgName,
        TicketStatus status,
        Priority priority,
        SlaStatus slaStatus,
        String categoryName,
        String teamName,
        String queueName,
        String assigneeName,
        String requesterEmail,
        Instant createdAt,
        Instant updatedAt,
        Instant slaResolutionDeadline,
        boolean overdue) {
}
