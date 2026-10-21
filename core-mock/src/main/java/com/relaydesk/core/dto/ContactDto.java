package com.relaydesk.core.dto;

import java.util.UUID;

/** Contact JSON. */
public record ContactDto(UUID id, String firstName, String lastName, String email, String phone,
                         String title, boolean active) {
}
