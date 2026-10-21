package com.relaydesk.decision.dto;

import java.util.UUID;

/** Echo of the provisioned principal returned by the provisioning endpoints. */
public record ProvisionedUser(
        UUID userId,
        String email,
        String displayName,
        String role,
        boolean enabled) {
}
