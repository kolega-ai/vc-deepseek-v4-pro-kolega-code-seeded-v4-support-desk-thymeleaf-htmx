package com.relaydesk.channel.dto;

import java.util.List;

/** Aggregated numbers for the role-specific dashboards. */
public record DashboardStats(
        long openTickets,
        long newTickets,
        long overdueTickets,
        long escalatedTickets,
        long resolvedToday,
        long myOpenTickets,
        long unreadNotifications,
        long pendingEscalations,
        long totalTickets,
        long kbPublished,
        List<CountBucket> statusBuckets) {
}
