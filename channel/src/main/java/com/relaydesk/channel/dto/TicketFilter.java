package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.TicketStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Ticket queue filter. All values are bound parameters in queries; the sort column is
 * validated against a fixed whitelist in TicketQueryService (precision baseline #4).
 */
public record TicketFilter(
        TicketStatus status,
        Priority priority,
        UUID categoryId,
        UUID teamId,
        UUID queueId,
        String assignee,          // "unassigned" | "mine" | "any"
        Boolean overdueOnly,      // boxed: unchecked checkboxes do not send a parameter
        LocalDate from,
        LocalDate to,
        String q,
        String sort) implements java.io.Serializable {

    public TicketFilter {
        if (assignee == null || assignee.isBlank()) {
            assignee = "any";
        }
        if (sort == null || sort.isBlank()) {
            sort = "created";
        }
        if (q != null && q.isBlank()) {
            q = null;
        }
        overdueOnly = overdueOnly != null && overdueOnly;
    }

    public boolean hasQuery() {
        return status != null || priority != null || categoryId != null || teamId != null
                || queueId != null || overdueOnly || from != null || to != null || q != null;
    }
}
