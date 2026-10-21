package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.dto.TicketSummaryRow;
import com.relaydesk.channel.repo.EscalationRepository;
import com.relaydesk.channel.repo.TicketMessageRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.shared.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportServiceTest {

    @Test
    void csvEscapingHandlesCommasQuotesNewlines() {
        String encoded = CsvSupport.encode(List.of("a,b", "he said \"hi\"", "line1\nline2", "plain"));
        assertEquals("\"a,b\",\"he said \"\"hi\"\"\",\"line1\nline2\",plain", encoded);
    }

    @Test
    void customerExportContainsOnlyPublicColumns() {
        TicketQueryService query = mock(TicketQueryService.class);
        SlaService sla = mock(SlaService.class);
        EscalationService escalations = mock(EscalationService.class);
        AuditService audit = mock(AuditService.class);
        TicketRepository ticketRepo = mock(TicketRepository.class);
        EscalationRepository escalationRepo = mock(EscalationRepository.class);
        TicketMessageRepository messages = mock(TicketMessageRepository.class);

        TicketSummaryRow row = new TicketSummaryRow(
                UUID.randomUUID(), "TCK-1001", "Billing question", "Acme Corp",
                TicketStatus.OPEN, Priority.MEDIUM, SlaStatus.ON_TRACK,
                "Billing & Payments", "Billing", "Billing queue", "Devon",
                "jordan.lee@acme.test",
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z"),
                Instant.parse("2026-09-03T10:00:00Z"), false);
        when(query.query(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 500), 1));
        when(ticketRepo.findAllById(any())).thenReturn(List.of());
        when(messages.countByTicketIdAndInternalFalse(any())).thenReturn(2L);

        ExportService svc = new ExportService(query, sla, escalations, audit,
                ticketRepo, escalationRepo, messages);
        RequestPrincipal customer = new RequestPrincipal(UUID.randomUUID(), "c@acme.test", "Customer",
                Role.CUSTOMER_USER, Set.of(UUID.randomUUID()), Set.of(), Map.of(), "s", "t");

        ExportService.CsvFile out = svc.customerTickets(customer);

        assertTrue(out.name().startsWith("customer-tickets-"));
        assertTrue(out.content().contains("TCK-1001"));
        assertTrue(out.content().contains("Billing question"));
        assertTrue(out.content().contains("public_reply_count"));
        // No internal-note/message-body column exists in the customer export at all.
        assertFalse(out.content().contains("NOTE_BODY_MARKER"));
        assertFalse(out.content().contains("internal"));
        // One audit event per export request.
        verify(audit).record(eq(customer), eq("export.customer_tickets"), any(), any(), any(),
                any(), eq("count=1"));
    }

    @Test
    void slaAndEscalationExportsRequireLeadOrAdmin() {
        TicketQueryService query = mock(TicketQueryService.class);
        SlaService sla = mock(SlaService.class);
        EscalationService escalations = mock(EscalationService.class);
        AuditService audit = mock(AuditService.class);
        TicketRepository ticketRepo = mock(TicketRepository.class);
        EscalationRepository escalationRepo = mock(EscalationRepository.class);
        TicketMessageRepository messages = mock(TicketMessageRepository.class);
        ExportService svc = new ExportService(query, sla, escalations, audit,
                ticketRepo, escalationRepo, messages);

        RequestPrincipal agent = new RequestPrincipal(UUID.randomUUID(), "a@x", "Agent",
                Role.SUPPORT_AGENT, Set.of(), Set.of(UUID.randomUUID()), Map.of(), "s", "t");
        RequestPrincipal lead = new RequestPrincipal(UUID.randomUUID(), "l@x", "Lead",
                Role.TEAM_LEAD, Set.of(), Set.of(UUID.randomUUID()), Map.of(), "s", "t");
        RequestPrincipal admin = new RequestPrincipal(UUID.randomUUID(), "m@x", "Admin",
                Role.SUPPORT_ADMIN, Set.of(), Set.of(), Map.of(), "s", "t");

        when(sla.stats(any())).thenReturn(new com.relaydesk.channel.dto.SlaStats(
                0, 0, 0, 0, 0, 0, 0, List.of()));
        when(query.query(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0));
        when(escalations.query(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0));

        org.springframework.security.access.AccessDeniedException denied =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.security.access.AccessDeniedException.class,
                        () -> svc.sla(agent, new com.relaydesk.channel.dto.SlaReportFilter(null, null, null)));
        assertEquals("team lead or admin only", denied.getMessage());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> svc.sla(lead,
                new com.relaydesk.channel.dto.SlaReportFilter(null, null, null)));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> svc.escalations(admin,
                new com.relaydesk.channel.dto.EscalationFilter(null, null, null, null, null)));
    }
}
