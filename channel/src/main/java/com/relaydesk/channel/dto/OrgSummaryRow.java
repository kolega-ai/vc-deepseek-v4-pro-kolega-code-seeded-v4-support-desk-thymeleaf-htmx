package com.relaydesk.channel.dto;

import java.util.UUID;

/** Organization summary as returned by the external customer-core (core-mock). */
public record OrgSummaryRow(
        UUID id,
        String name,
        String slug,
        String plan,
        String status,
        int contactCount) {
}
