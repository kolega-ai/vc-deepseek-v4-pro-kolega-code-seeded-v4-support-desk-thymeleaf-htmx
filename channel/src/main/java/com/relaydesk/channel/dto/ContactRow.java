package com.relaydesk.channel.dto;

import java.util.UUID;

public record ContactRow(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String title,
        boolean active) {
}
