package com.relaydesk.channel.dto;

import java.time.Instant;

public record SlaMilestone(
        String label,
        Instant at,
        String state) {
}
