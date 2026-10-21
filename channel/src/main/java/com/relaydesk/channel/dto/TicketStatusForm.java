package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TicketStatusForm(
        @NotNull TicketStatus targetStatus,
        @Size(max = 1000) String comment) {
}
