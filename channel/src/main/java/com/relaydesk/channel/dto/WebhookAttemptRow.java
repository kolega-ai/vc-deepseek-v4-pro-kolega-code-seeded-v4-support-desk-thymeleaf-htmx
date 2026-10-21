package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.DeliveryStatus;

import java.time.Instant;

public record WebhookAttemptRow(
        Long id,
        String endpointName,
        String event,
        DeliveryStatus status,
        Integer responseCode,
        String errorMessage,
        Instant createdAt) {
}
