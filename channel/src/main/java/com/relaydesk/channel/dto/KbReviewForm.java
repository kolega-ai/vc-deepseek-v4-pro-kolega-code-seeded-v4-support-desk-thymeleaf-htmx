package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KbReviewForm(
        @NotBlank @Size(max = 2000) String reason) {
}
