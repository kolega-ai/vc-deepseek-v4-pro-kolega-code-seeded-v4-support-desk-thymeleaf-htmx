package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.error.IllegalTransitionException;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Knowledge-base article state machine — explicit allowed edges enforced server-side. */
public class KbTransitions {

    private static final Map<KbStatus, Set<KbStatus>> EDGES = new EnumMap<>(KbStatus.class);

    static {
        edge(KbStatus.DRAFT, KbStatus.IN_REVIEW);
        edge(KbStatus.IN_REVIEW, KbStatus.PUBLISHED);
        edge(KbStatus.IN_REVIEW, KbStatus.DRAFT);
        edge(KbStatus.PUBLISHED, KbStatus.ARCHIVED);
        edge(KbStatus.ARCHIVED, KbStatus.DRAFT);
    }

    private KbTransitions() {
    }

    private static void edge(KbStatus from, KbStatus to) {
        EDGES.computeIfAbsent(from, k -> new HashSet<>()).add(to);
    }

    public static void require(KbStatus from, KbStatus to) {
        if (from == to) {
            return; // no-op
        }
        Set<KbStatus> allowed = EDGES.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new IllegalTransitionException("illegal transition " + from + " -> " + to);
        }
    }
}
