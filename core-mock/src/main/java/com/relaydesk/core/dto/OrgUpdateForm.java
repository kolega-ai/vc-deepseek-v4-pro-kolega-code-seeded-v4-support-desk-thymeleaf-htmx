package com.relaydesk.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT /api/orgs/{id} body (SUPPORT_ADMIN only). */
public record OrgUpdateForm(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 50) String plan,
        @Size(max = 500) String address,
        @NotBlank @Size(max = 20) String status) {
}
