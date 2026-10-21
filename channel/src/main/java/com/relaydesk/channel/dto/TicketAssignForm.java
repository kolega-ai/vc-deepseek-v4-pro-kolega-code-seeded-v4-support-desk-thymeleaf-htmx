package com.relaydesk.channel.dto;

import java.util.UUID;

/** Reassignment form; either field may be set. Team changes must go through managed-team checks. */
public record TicketAssignForm(
        UUID assigneeId,
        UUID teamId) {
}
