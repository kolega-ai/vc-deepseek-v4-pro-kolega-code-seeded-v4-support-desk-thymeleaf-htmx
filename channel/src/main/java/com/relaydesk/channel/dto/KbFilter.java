package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.KbStatus;

import java.util.UUID;

public record KbFilter(
        KbStatus status,
        String q,
        UUID authorId) {

    public KbFilter {
        if (q != null && q.isBlank()) {
            q = null;
        }
    }
}
