package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.EscalationStatus;

import java.time.LocalDate;
import java.util.UUID;

public record EscalationFilter(
        EscalationStatus status,
        UUID teamId,
        String q,
        LocalDate from,
        LocalDate to) {

    public EscalationFilter {
        if (q != null && q.isBlank()) {
            q = null;
        }
    }
}
