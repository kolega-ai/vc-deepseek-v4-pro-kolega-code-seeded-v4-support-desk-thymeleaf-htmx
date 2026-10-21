package com.relaydesk.channel.dto;

import java.time.LocalDate;
import java.util.UUID;

public record SlaReportFilter(
        UUID teamId,
        LocalDate from,
        LocalDate to) {
}
