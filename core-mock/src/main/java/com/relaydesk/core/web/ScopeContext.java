package com.relaydesk.core.web;

import com.relaydesk.shared.Role;

import java.util.List;
import java.util.UUID;

/**
 * Identity for the current request, taken exclusively from the verified scope token
 * (stored by {@code ScopeTokenFilter} as the {@code scopeContext} request attribute).
 *
 * <p>Any user id, role, or scope fields present in the request body, headers, or query
 * string are ignored by construction — this record is the only identity source downstream
 * code may consult.
 */
public record ScopeContext(UUID uid, String role, List<UUID> orgIds) {

    public boolean isStaff() {
        return Role.SUPPORT_ADMIN.name().equals(role)
                || Role.SUPPORT_AGENT.name().equals(role)
                || Role.TEAM_LEAD.name().equals(role);
    }

    public boolean isAdmin() {
        return Role.SUPPORT_ADMIN.name().equals(role);
    }

    public boolean isCustomer() {
        return Role.CUSTOMER_USER.name().equals(role);
    }
}
