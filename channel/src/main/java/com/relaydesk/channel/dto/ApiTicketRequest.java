package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** JSON API request body for ticket creation. The org of a customer-owned key is forced server-side. */
public record ApiTicketRequest(
        UUID orgId,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 8000) String description,
        @NotNull UUID categoryId,
        Priority priority,
        @Email @Size(max = 255) String contactEmail) {
}
