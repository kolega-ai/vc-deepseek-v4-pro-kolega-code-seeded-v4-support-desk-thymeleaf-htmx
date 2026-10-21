package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record QueueForm(
        @NotBlank @Size(max = 100) String name,
        @NotNull UUID teamId,
        @Size(max = 500) String description) {
}
