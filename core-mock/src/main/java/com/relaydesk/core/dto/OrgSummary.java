package com.relaydesk.core.dto;

import java.util.UUID;

/** Organization list-row JSON. */
public record OrgSummary(UUID id, String name, String slug, String plan, String status, int contactCount) {
}
