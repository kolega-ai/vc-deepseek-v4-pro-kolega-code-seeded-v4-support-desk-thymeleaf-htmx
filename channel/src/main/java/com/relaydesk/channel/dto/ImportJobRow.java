package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.ImportKind;
import com.relaydesk.channel.domain.ImportStatus;

import java.time.Instant;
import java.util.UUID;

public record ImportJobRow(
        UUID id,
        ImportKind kind,
        String fileName,
        ImportStatus status,
        int rowsTotal,
        int rowsOk,
        int rowsFailed,
        Instant createdAt,
        Instant finishedAt) {
}
