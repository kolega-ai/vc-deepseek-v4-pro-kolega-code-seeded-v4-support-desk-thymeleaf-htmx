package com.relaydesk.channel.dto;

import java.util.List;
import java.util.UUID;

public record OrgDetailRow(
        UUID id,
        String name,
        String slug,
        String plan,
        String status,
        String address,
        List<EntitlementRow> entitlements,
        List<ContactRow> contacts) {
}
