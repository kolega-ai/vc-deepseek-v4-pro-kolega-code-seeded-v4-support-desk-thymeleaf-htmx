package com.relaydesk.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/orgs body (SUPPORT_ADMIN only). */
public record OrgCreateForm(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 50) String plan,
        @Size(max = 500) String address) {
}
