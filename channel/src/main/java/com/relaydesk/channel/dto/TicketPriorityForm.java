package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import jakarta.validation.constraints.NotNull;

public record TicketPriorityForm(
        @NotNull Priority priority) {
}
