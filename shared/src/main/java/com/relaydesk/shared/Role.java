package com.relaydesk.shared;

/**
 * Roles shared by all three tiers. Decision is the authoritative source of a user's role;
 * channel uses these only for display and as a cached copy of the decision value.
 */
public enum Role {
    SUPPORT_ADMIN,
    SUPPORT_AGENT,
    TEAM_LEAD,
    CUSTOMER_USER
}
