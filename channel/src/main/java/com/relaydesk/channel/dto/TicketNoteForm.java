package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketNoteForm(
        @NotBlank @Size(max = 8000) String body) {
}
