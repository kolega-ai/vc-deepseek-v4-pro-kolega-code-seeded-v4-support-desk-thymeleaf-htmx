package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.KbStatus;

import java.time.Instant;
import java.util.UUID;

public record KbRow(
        UUID id,
        String slug,
        String title,
        KbStatus status,
        boolean internalOnly,
        String authorName,
        String categoryName,
        String tags,
        Instant publishedAt,
        Instant updatedAt,
        long viewCount) {
}
