package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.AttachmentCategory;

import java.time.Instant;
import java.util.UUID;

public record AttachmentRow(
        UUID id,
        String originalName,
        String contentType,
        long sizeBytes,
        AttachmentCategory category,
        String uploaderName,
        Instant createdAt) {
}
