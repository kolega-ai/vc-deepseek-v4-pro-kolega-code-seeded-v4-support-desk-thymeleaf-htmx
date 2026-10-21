package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordForm(
        @NotBlank @Size(min = 10, max = 100) String password,
        @NotBlank @Size(min = 10, max = 100) String confirmPassword) {
}
