package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WebhookForm(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 500) String url,
        @NotBlank @Size(max = 200) String secret,
        List<@NotBlank @Size(max = 50) String> events) {
}
