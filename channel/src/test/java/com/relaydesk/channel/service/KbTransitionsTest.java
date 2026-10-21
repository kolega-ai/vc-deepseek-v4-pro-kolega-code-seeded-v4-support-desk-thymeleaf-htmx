package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.error.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import static com.relaydesk.channel.domain.KbStatus.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KbTransitionsTest {

    @Test
    void validEdgesPass() {
        KbStatus[][] edges = {
                {DRAFT, IN_REVIEW},
                {IN_REVIEW, PUBLISHED}, {IN_REVIEW, DRAFT},
                {PUBLISHED, ARCHIVED},
                {ARCHIVED, DRAFT}};
        for (KbStatus[] edge : edges) {
            assertDoesNotThrow(() -> KbTransitions.require(edge[0], edge[1]),
                    edge[0] + " -> " + edge[1]);
        }
    }

    @Test
    void invalidTransitionsThrow() {
        assertThrows(IllegalTransitionException.class, () -> KbTransitions.require(DRAFT, PUBLISHED));
        assertThrows(IllegalTransitionException.class, () -> KbTransitions.require(PUBLISHED, DRAFT));
        assertThrows(IllegalTransitionException.class, () -> KbTransitions.require(ARCHIVED, PUBLISHED));
        assertThrows(IllegalTransitionException.class, () -> KbTransitions.require(IN_REVIEW, ARCHIVED));
    }
}
