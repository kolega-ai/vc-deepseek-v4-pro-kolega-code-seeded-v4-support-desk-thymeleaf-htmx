package com.relaydesk.channel.dto;

import java.util.List;

public record TeamSlaRow(
        String teamName,
        long active,
        long breached,
        double avgResponseHours) {
}
