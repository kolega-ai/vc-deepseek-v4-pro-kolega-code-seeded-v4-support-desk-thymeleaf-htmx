package com.relaydesk.channel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Precision #4: bounded, whitelisted sort column — unknown values fall back to createdAt
 * (injection attempts included), and the direction rule (ASC only for subject) holds.
 */
class TicketQueryServiceSortTest {

    @Test
    void whitelistMapsKnownColumns() {
        assertEquals("createdAt", TicketQueryService.whitelistColumn("created"));
        assertEquals("updatedAt", TicketQueryService.whitelistColumn("updated"));
        assertEquals("priority", TicketQueryService.whitelistColumn("priority"));
        assertEquals("slaResolutionDeadline", TicketQueryService.whitelistColumn("sla"));
        assertEquals("subject", TicketQueryService.whitelistColumn("subject"));
    }

    @Test
    void unknownSortFallsBackToCreatedAt() {
        assertEquals("createdAt", TicketQueryService.whitelistColumn("x'; DROP TABLE tickets;--"));
        assertEquals("createdAt", TicketQueryService.whitelistColumn("randomcolumn"));
        assertEquals("createdAt", TicketQueryService.whitelistColumn(null));
    }

    @Test
    void subjectSortsAscendingOthersDescending() {
        assertTrue(TicketQueryService.resolveSort("subject").getOrderFor("subject").isAscending());
        assertFalse(TicketQueryService.resolveSort("created").getOrderFor("createdAt").isAscending());
        assertFalse(TicketQueryService.resolveSort("sla").getOrderFor("slaResolutionDeadline").isAscending());
        assertFalse(TicketQueryService.resolveSort("x'; DROP").getOrderFor("createdAt").isAscending());
    }
}
