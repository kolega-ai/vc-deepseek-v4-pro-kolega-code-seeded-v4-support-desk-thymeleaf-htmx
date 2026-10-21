package com.relaydesk.core.dto;

/** Entitlement JSON. */
public record EntitlementDto(Long id, String product, int seats, String status) {
}
