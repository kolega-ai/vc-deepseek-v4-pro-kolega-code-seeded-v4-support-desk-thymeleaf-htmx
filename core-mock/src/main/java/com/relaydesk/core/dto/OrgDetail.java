package com.relaydesk.core.dto;

import java.util.List;
import java.util.UUID;

/** Organization detail JSON (incl. entitlements + contacts). */
public record OrgDetail(UUID id, String name, String slug, String plan, String status, String address,
                        List<EntitlementDto> entitlements, List<ContactDto> contacts) {
}
