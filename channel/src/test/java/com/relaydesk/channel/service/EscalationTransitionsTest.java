package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.EscalationStatus;
import com.relaydesk.channel.error.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import static com.relaydesk.channel.domain.EscalationStatus.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EscalationTransitionsTest {

    @Test
    void validEdgesPass() {
        EscalationStatus[][] edges = {
                {REQUESTED, ACCEPTED}, {REQUESTED, REJECTED},
                {ACCEPTED, IN_REVIEW}, {ACCEPTED, RESOLVED}, {ACCEPTED, REJECTED},
                {IN_REVIEW, RESOLVED}, {IN_REVIEW, REJECTED}};
        for (EscalationStatus[] edge : edges) {
            assertDoesNotThrow(() -> EscalationTransitions.require(edge[0], edge[1]),
                    edge[0] + " -> " + edge[1]);
        }
    }

    @Test
    void invalidTransitionsThrow() {
        assertThrows(IllegalTransitionException.class,
                () -> EscalationTransitions.require(REQUESTED, RESOLVED));
        assertThrows(IllegalTransitionException.class,
                () -> EscalationTransitions.require(REJECTED, ACCEPTED));
        assertThrows(IllegalTransitionException.class,
                () -> EscalationTransitions.require(RESOLVED, IN_REVIEW));
        assertThrows(IllegalTransitionException.class,
                () -> EscalationTransitions.require(IN_REVIEW, REQUESTED));
    }
}
