package com.relaydesk.channel.dto;

import com.relaydesk.shared.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UserForm(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 120) String displayName,
        @NotNull Role role,
        List<UUID> teamIds,
        Boolean enabled) {

    public UserForm {
        enabled = enabled != null && enabled;
    }
}
