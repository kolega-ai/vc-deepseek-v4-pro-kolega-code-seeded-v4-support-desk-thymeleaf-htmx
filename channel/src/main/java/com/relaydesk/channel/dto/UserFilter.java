package com.relaydesk.channel.dto;

import com.relaydesk.shared.Role;

public record UserFilter(
        String q,
        Role role,
        Boolean enabledOnly) {

    public UserFilter {
        enabledOnly = enabledOnly != null && enabledOnly;
        if (q != null && q.isBlank()) {
            q = null;
        }
    }
}
