package com.relaydesk.channel.dto;

import java.time.Instant;
import java.util.List;

public record EscalationDetailView(
        EscalationRow row,
        String slaExceptionReason,
        String resolution,
        Instant resolvedAt,
        TicketDetailView ticket,
        List<AuditRow> audit,
        boolean canDecide) {
}
