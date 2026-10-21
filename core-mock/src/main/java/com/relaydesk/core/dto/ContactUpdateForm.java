package com.relaydesk.core.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PUT /api/contacts/{id} body (SUPPORT_ADMIN only). */
public record ContactUpdateForm(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 120) String title,
        @NotNull Boolean active) {
}
