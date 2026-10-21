package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full ticket view. Messages are pre-filtered by the caller's role: customers never receive
 * internal notes, and the booleans below encode the server-side decision on which actions
 * the acting principal may take on this ticket.
 */
public record TicketDetailView(
        UUID id,
        String ref,
        String subject,
        String description,
        UUID orgId,
        String orgName,
        TicketStatus status,
        Priority priority,
        SlaStatus slaStatus,
        String categoryName,
        String teamName,
        String queueName,
        String assigneeName,
        String requesterName,
        String requesterEmail,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        Instant closedAt,
        Instant slaResponseDeadline,
        Instant slaResolutionDeadline,
        Instant firstResponseAt,
        String slaPolicyName,
        List<MessageRow> messages,
        List<AttachmentRow> attachments,
        EscalationRow escalation,
        List<AuditRow> audit,
        List<SlaMilestone> slaTimeline,
        boolean canReply,
        boolean canNote,
        boolean canStatus,
        boolean canClaim,
        boolean canAssign,
        boolean canEscalate) {
}
