package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EscalateForm(
        @NotBlank @Size(max = 2000) String reason,
        Boolean slaException,
        @Size(max = 2000) String slaExceptionReason) {

    public EscalateForm {
        slaException = slaException != null && slaException;
    }
}
