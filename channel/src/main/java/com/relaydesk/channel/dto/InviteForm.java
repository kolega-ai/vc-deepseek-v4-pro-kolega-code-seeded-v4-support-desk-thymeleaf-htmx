package com.relaydesk.channel.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InviteForm(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull UUID orgId) {
}
