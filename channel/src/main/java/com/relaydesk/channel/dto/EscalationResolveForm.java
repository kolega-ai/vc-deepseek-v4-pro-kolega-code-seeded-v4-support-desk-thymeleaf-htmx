package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EscalationResolveForm(
        @NotBlank @Size(max = 4000) String resolution,
        Boolean resolveTicket) {

    public EscalationResolveForm {
        resolveTicket = resolveTicket != null && resolveTicket;
    }
}
