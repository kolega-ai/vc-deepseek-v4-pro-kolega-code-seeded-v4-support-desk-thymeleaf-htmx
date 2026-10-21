package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.error.IllegalTransitionException;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ticket state machine — the explicit allowed edge list from the design contract.
 * Server-side enforcement only; every status is reachable through these edges.
 * Same-identity transitions (from == to) are no-ops and allowed.
 */
public class TicketTransitions {

    private static final Map<TicketStatus, Set<TicketStatus>> EDGES = new EnumMap<>(TicketStatus.class);

    static {
        edge(TicketStatus.NEW, TicketStatus.OPEN);
        edge(TicketStatus.NEW, TicketStatus.CLOSED);
        edge(TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER);
        edge(TicketStatus.OPEN, TicketStatus.PENDING_INTERNAL);
        edge(TicketStatus.OPEN, TicketStatus.ESCALATED);
        edge(TicketStatus.OPEN, TicketStatus.RESOLVED);
        edge(TicketStatus.PENDING_CUSTOMER, TicketStatus.OPEN);
        edge(TicketStatus.PENDING_CUSTOMER, TicketStatus.RESOLVED);
        edge(TicketStatus.PENDING_CUSTOMER, TicketStatus.ESCALATED);
        edge(TicketStatus.PENDING_INTERNAL, TicketStatus.OPEN);
        edge(TicketStatus.PENDING_INTERNAL, TicketStatus.RESOLVED);
        edge(TicketStatus.PENDING_INTERNAL, TicketStatus.ESCALATED);
        edge(TicketStatus.ESCALATED, TicketStatus.OPEN);
        edge(TicketStatus.ESCALATED, TicketStatus.RESOLVED);
        edge(TicketStatus.RESOLVED, TicketStatus.CLOSED);
        edge(TicketStatus.RESOLVED, TicketStatus.OPEN);
        edge(TicketStatus.CLOSED, TicketStatus.OPEN);
    }

    private TicketTransitions() {
    }

    private static void edge(TicketStatus from, TicketStatus to) {
        EDGES.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    public static void require(TicketStatus from, TicketStatus to) {
        if (from == to) {
            return; // no-op
        }
        Set<TicketStatus> allowed = EDGES.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalTransitionException("illegal transition " + from + " -> " + to);
        }
    }
}
