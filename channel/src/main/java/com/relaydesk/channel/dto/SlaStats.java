package com.relaydesk.channel.dto;

import java.util.List;

public record SlaStats(
        long totalActive,
        long breached,
        long atRisk,
        long onTrack,
        double avgResponseHours,
        double avgResolutionHours,
        double breachRatePercent,
        List<TeamSlaRow> byTeam) {
}
