package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.error.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import static com.relaydesk.channel.domain.TicketStatus.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TicketTransitionsTest {

    @Test
    void validEdgesPass() {
        TicketStatus[][] edges = {
                {NEW, OPEN}, {NEW, CLOSED},
                {OPEN, PENDING_CUSTOMER}, {OPEN, PENDING_INTERNAL}, {OPEN, ESCALATED}, {OPEN, RESOLVED},
                {PENDING_CUSTOMER, OPEN}, {PENDING_CUSTOMER, RESOLVED}, {PENDING_CUSTOMER, ESCALATED},
                {PENDING_INTERNAL, OPEN}, {PENDING_INTERNAL, RESOLVED}, {PENDING_INTERNAL, ESCALATED},
                {ESCALATED, OPEN}, {ESCALATED, RESOLVED},
                {RESOLVED, CLOSED}, {RESOLVED, OPEN},
                {CLOSED, OPEN}};
        for (TicketStatus[] edge : edges) {
            assertDoesNotThrow(() -> TicketTransitions.require(edge[0], edge[1]),
                    edge[0] + " -> " + edge[1]);
        }
    }

    @Test
    void sameIdentityIsNoOp() {
        assertDoesNotThrow(() -> TicketTransitions.require(OPEN, OPEN));
        assertDoesNotThrow(() -> TicketTransitions.require(CLOSED, CLOSED));
    }

    @Test
    void invalidTransitionsThrow() {
        assertThrows(IllegalTransitionException.class,
                () -> TicketTransitions.require(NEW, RESOLVED));
        assertThrows(IllegalTransitionException.class,
                () -> TicketTransitions.require(CLOSED, RESOLVED));
        assertThrows(IllegalTransitionException.class,
                () -> TicketTransitions.require(RESOLVED, NEW));
        assertThrows(IllegalTransitionException.class,
                () -> TicketTransitions.require(NEW, ESCALATED));
        assertThrows(IllegalTransitionException.class,
                () -> TicketTransitions.require(OPEN, CLOSED));
    }

    @Test
    void illegalTransitionIsAnIllegalArgumentException() {
        // Contract: violations surface as IllegalArgumentException("illegal transition ...")
        assertThrows(IllegalArgumentException.class,
                () -> TicketTransitions.require(PENDING_CUSTOMER, CLOSED));
    }
}
