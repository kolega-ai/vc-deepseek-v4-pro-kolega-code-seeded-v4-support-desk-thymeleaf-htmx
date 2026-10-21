package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.EmailStatus;

import java.time.Instant;

public record OutboxRow(
        Long id,
        String toAddress,
        String subject,
        EmailStatus status,
        Instant sentAt,
        Instant createdAt) {
}
