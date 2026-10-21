package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Allow-listed form for ticket creation; security-relevant fields (status, org, assignee) are not bindable. */
public record TicketCreateForm(
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 8000) String description,
        @NotNull UUID categoryId,
        Priority priority,
        UUID contactId) {
}
