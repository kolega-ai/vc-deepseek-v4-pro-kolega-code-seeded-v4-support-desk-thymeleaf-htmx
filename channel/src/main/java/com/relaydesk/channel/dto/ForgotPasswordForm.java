package com.relaydesk.channel.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordForm(
        @NotBlank @Email String email) {
}
