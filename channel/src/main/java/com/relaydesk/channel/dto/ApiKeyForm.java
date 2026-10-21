package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ApiKeyForm(
        @NotBlank @Size(max = 100) String name,
        @NotNull UUID ownerId,
        List<@NotBlank @Size(max = 50) String> scopes,
        LocalDate expiresAt) {
}
