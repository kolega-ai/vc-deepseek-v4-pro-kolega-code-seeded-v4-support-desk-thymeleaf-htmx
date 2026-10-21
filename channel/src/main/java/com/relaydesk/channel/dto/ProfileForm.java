package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileForm(
        @NotBlank @Size(max = 120) String displayName,
        @Size(max = 100) String currentPassword,
        @Size(max = 100) String newPassword) {
}
