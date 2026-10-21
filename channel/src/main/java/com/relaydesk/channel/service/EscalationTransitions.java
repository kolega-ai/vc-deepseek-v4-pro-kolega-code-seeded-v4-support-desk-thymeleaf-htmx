package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.EscalationStatus;
import com.relaydesk.channel.error.IllegalTransitionException;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Escalation state machine — explicit allowed edges enforced server-side. */
public class EscalationTransitions {

    private static final Map<EscalationStatus, Set<EscalationStatus>> EDGES = new EnumMap<>(EscalationStatus.class);

    static {
        edge(EscalationStatus.REQUESTED, EscalationStatus.ACCEPTED);
        edge(EscalationStatus.REQUESTED, EscalationStatus.REJECTED);
        edge(EscalationStatus.ACCEPTED, EscalationStatus.IN_REVIEW);
        edge(EscalationStatus.ACCEPTED, EscalationStatus.RESOLVED);
        edge(EscalationStatus.ACCEPTED, EscalationStatus.REJECTED);
        edge(EscalationStatus.IN_REVIEW, EscalationStatus.RESOLVED);
        edge(EscalationStatus.IN_REVIEW, EscalationStatus.REJECTED);
    }

    private EscalationTransitions() {
    }

    private static void edge(EscalationStatus from, EscalationStatus to) {
        EDGES.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    public static void require(EscalationStatus from, EscalationStatus to) {
        if (from == to) {
            return; // no-op
        }
        Set<EscalationStatus> allowed = EDGES.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalTransitionException("illegal transition " + from + " -> " + to);
        }
    }
}
