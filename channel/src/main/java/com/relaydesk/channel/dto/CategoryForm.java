package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CategoryForm(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        UUID defaultTeamId) {
}
