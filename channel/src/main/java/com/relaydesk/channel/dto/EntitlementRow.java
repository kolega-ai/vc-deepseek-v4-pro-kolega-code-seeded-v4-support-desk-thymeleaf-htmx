package com.relaydesk.channel.dto;

public record EntitlementRow(
        Long id,
        String product,
        int seats,
        String status) {
}
