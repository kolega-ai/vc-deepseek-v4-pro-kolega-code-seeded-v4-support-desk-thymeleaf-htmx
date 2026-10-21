package com.relaydesk.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.SlaPolicy;
import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketMessage;
import com.relaydesk.channel.domain.TicketSequence;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.dto.TicketCreateForm;
import com.relaydesk.channel.error.IllegalTransitionException;
import com.relaydesk.channel.repo.AttachmentRepository;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.EscalationRepository;
import com.relaydesk.channel.repo.QueueRepository;
import com.relaydesk.channel.repo.SlaPolicyRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.TicketMessageRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.TicketSequenceRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.shared.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketServiceTest {

    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TicketMessageRepository messages = mock(TicketMessageRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final SlaPolicyRepository slaPolicies = mock(SlaPolicyRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final QueueRepository queues = mock(QueueRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TicketSequenceRepository sequences = mock(TicketSequenceRepository.class);
    private final AttachmentRepository attachments = mock(AttachmentRepository.class);
    private final EscalationRepository escalations = mock(EscalationRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final WebhookService webhooks = mock(WebhookService.class);
    private final SlaService sla = mock(SlaService.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final CoreClient core = mock(CoreClient.class);

    private TicketService service() {
        return new TicketService(tickets, messages, categories, slaPolicies, teams, queues,
                users, sequences, attachments, escalations, audit, notifications, webhooks,
                sla, storage, core, new ObjectMapper());
    }

    private RequestPrincipal admin() {
        return new RequestPrincipal(UUID.randomUUID(), "admin@x", "Admin",
                Role.SUPPORT_ADMIN, Set.of(), Set.of(), Map.of(), "s", "t");
    }

    private RequestPrincipal customer(UUID org) {
        return new RequestPrincipal(UUID.randomUUID(), "c@x", "C",
                Role.CUSTOMER_USER, Set.of(org), Set.of(), Map.of(), "s", "t");
    }

    private Ticket ticket(TicketStatus status) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setRef("TCK-1001");
        t.setOrgId(UUID.randomUUID());
        t.setOrgName("Acme");
        t.setSubject("s");
        t.setDescription("d");
        t.setCategoryId(UUID.randomUUID());
        t.setPriority(Priority.MEDIUM);
        t.setStatus(status);
        t.setRequesterId(UUID.randomUUID());
        t.setSlaPolicyId(UUID.randomUUID());
        t.setSlaResponseDeadline(Instant.now().plusSeconds(3600));
        t.setSlaResolutionDeadline(Instant.now().plusSeconds(7200));
        t.setSlaStatus(SlaStatus.ON_TRACK);
        return t;
    }

    @Test
    void changeStatusRejectsIllegalTransition() {
        Ticket t = ticket(TicketStatus.NEW);
        when(tickets.findById(t.getId())).thenReturn(Optional.of(t));
        assertThrows(IllegalTransitionException.class,
                () -> service().changeStatus(admin(), t.getId(), TicketStatus.RESOLVED, null));
        verify(tickets, never()).save(any());
    }

    @Test
    void replyReopensClosedTicket() {
        Ticket t = ticket(TicketStatus.CLOSED);
        when(tickets.findById(t.getId())).thenReturn(Optional.of(t));
        TicketMessage saved = new TicketMessage();
        saved.setId(UUID.randomUUID());
        when(messages.save(any())).thenReturn(saved);
        service().reply(admin(), t.getId(), "reopening");
        assertEquals(TicketStatus.OPEN, t.getStatus());
        verify(tickets).save(t);
    }

    @Test
    void internalNoteDeniedForCustomer() {
        Ticket t = ticket(TicketStatus.OPEN);
        when(tickets.findById(t.getId())).thenReturn(Optional.of(t));
        assertThrows(AccessDeniedException.class,
                () -> service().internalNote(customer(t.getOrgId()), t.getId(), "note"));
        verify(messages, never()).save(any());
    }

    @Test
    void createAssignsSequenceRefAndNewStatus() {
        UUID org = UUID.randomUUID();
        Category cat = new Category();
        cat.setId(UUID.randomUUID());
        cat.setActive(true);
        when(categories.findById(any())).thenReturn(Optional.of(cat));
        SlaPolicy policy = new SlaPolicy();
        policy.setId(UUID.randomUUID());
        policy.setPriority(Priority.MEDIUM);
        policy.setResponseMinutes(240);
        policy.setResolutionMinutes(1440);
        when(slaPolicies.findFirstByPriorityAndActiveTrue(any())).thenReturn(Optional.of(policy));
        TicketSequence seq = new TicketSequence();
        seq.setNextValue(1001);
        when(sequences.lockSequence()).thenReturn(Optional.of(seq));
        when(tickets.count(any(Specification.class))).thenReturn(0L);
        when(core.listOrgs(any(), any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
        // Emulate JPA @PrePersist id assignment
        when(tickets.save(any())).thenAnswer(inv -> {
            Ticket arg = inv.getArgument(0);
            if (arg.getId() == null) {
                arg.setId(UUID.randomUUID());
            }
            return arg;
        });

        Ticket created = service().create(customer(org),
                new TicketCreateForm("subj", "desc", cat.getId(), null, null));

        assertEquals(TicketStatus.NEW, created.getStatus());
        assertEquals("TCK-1001", created.getRef());
        assertEquals(Priority.MEDIUM, created.getPriority());
        assertEquals(org, created.getOrgId());
        assertEquals(1002, seq.getNextValue());
        verify(sequences).save(seq);
    }

    @Test
    void createEnforcesOpenTicketLimit() {
        UUID org = UUID.randomUUID();
        Category cat = new Category();
        cat.setId(UUID.randomUUID());
        cat.setActive(true);
        when(categories.findById(any())).thenReturn(Optional.of(cat));
        when(tickets.count(any(Specification.class))).thenReturn(10L);
        assertThrows(IllegalStateException.class,
                () -> service().create(customer(org),
                        new TicketCreateForm("s", "d", cat.getId(), null, null)));
    }

    @Test
    void claimRequiresAgentOrAdmin() {
        Ticket t = ticket(TicketStatus.NEW);
        when(tickets.findById(t.getId())).thenReturn(Optional.of(t));
        assertThrows(AccessDeniedException.class,
                () -> service().claim(customer(t.getOrgId()), t.getId()));
        verify(tickets, never()).save(any());
    }

    @Test
    void deleteRequiresAdmin() {
        Ticket t = ticket(TicketStatus.OPEN);
        when(tickets.findById(t.getId())).thenReturn(Optional.of(t));
        assertThrows(AccessDeniedException.class,
                () -> service().delete(customer(t.getOrgId()), t.getId()));
        verify(tickets, never()).delete((Ticket) any());
    }
}
