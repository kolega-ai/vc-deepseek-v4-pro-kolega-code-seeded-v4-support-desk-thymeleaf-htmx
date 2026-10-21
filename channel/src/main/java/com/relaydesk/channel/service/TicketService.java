package com.relaydesk.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.domain.Attachment;
import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.Queue;
import com.relaydesk.channel.domain.SlaPolicy;
import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketMessage;
import com.relaydesk.channel.domain.TicketSequence;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.TicketCreateForm;
import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.error.ResourceNotFoundException;
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
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ticket lifecycle: creation (with decision limits + SLA deadlines + sequence refs),
 * replies, internal notes, status transitions (state machine enforced), claims,
 * reassignments, priority changes, deletion. All mutations audit + notify + publish
 * public-field-only webhooks.
 */
@Service
@Transactional
public class TicketService {

    private static final List<TicketStatus> OPEN_STATUSES = List.of(
            TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER,
            TicketStatus.PENDING_INTERNAL, TicketStatus.ESCALATED);

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final CategoryRepository categories;
    private final SlaPolicyRepository slaPolicies;
    private final TeamRepository teams;
    private final QueueRepository queues;
    private final UserAccountRepository users;
    private final TicketSequenceRepository sequences;
    private final AttachmentRepository attachments;
    private final EscalationRepository escalations;
    private final AuditService audit;
    private final NotificationService notifications;
    private final WebhookService webhooks;
    private final SlaService sla;
    private final FileStorageService storage;
    private final CoreClient core;
    private final ObjectMapper mapper;

    public TicketService(TicketRepository tickets, TicketMessageRepository messages,
                         CategoryRepository categories, SlaPolicyRepository slaPolicies,
                         TeamRepository teams, QueueRepository queues, UserAccountRepository users,
                         TicketSequenceRepository sequences, AttachmentRepository attachments,
                         EscalationRepository escalations, AuditService audit,
                         NotificationService notifications, WebhookService webhooks,
                         SlaService sla, FileStorageService storage, CoreClient core,
                         ObjectMapper mapper) {
        this.tickets = tickets;
        this.messages = messages;
        this.categories = categories;
        this.slaPolicies = slaPolicies;
        this.teams = teams;
        this.queues = queues;
        this.users = users;
        this.sequences = sequences;
        this.attachments = attachments;
        this.escalations = escalations;
        this.audit = audit;
        this.notifications = notifications;
        this.webhooks = webhooks;
        this.sla = sla;
        this.storage = storage;
        this.core = core;
        this.mapper = mapper;
    }

    /** Customer portal path: the org comes from the principal's org scope. */
    public Ticket create(RequestPrincipal p, TicketCreateForm form) {
        UUID orgId = p.orgIds().stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("no organization scope for ticket creation"));
        return create(p, form, orgId);
    }

    /** API/staff path: the caller resolves the org; customer owners are forced to their scope org. */
    public Ticket create(RequestPrincipal p, TicketCreateForm form, UUID orgId) {
        boolean staff = p.isStaff();
        if (orgId == null) {
            throw new IllegalArgumentException("orgId required");
        }
        if (!staff && !p.orgIds().contains(orgId)) {
            throw new AccessDeniedException("organization not in scope");
        }
        Category category = categories.findById(form.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("category not found"));
        if (!category.isActive()) {
            throw new IllegalStateException("category is inactive");
        }
        Priority priority = form.priority() == null ? Priority.MEDIUM : form.priority();

        if (!staff) {
            long open = tickets.count((root, q, cb) -> cb.and(
                    cb.equal(root.get("orgId"), orgId),
                    root.get("status").in(OPEN_STATUSES)));
            int limit = p.limit("max_open_tickets", 10);
            if (open >= limit) {
                throw new IllegalStateException("open ticket limit reached");
            }
        }

        SlaPolicy policy = slaPolicies.findFirstByPriorityAndActiveTrue(priority)
                .orElseGet(() -> slaPolicies.findFirstByPriorityAndActiveTrue(Priority.MEDIUM)
                        .orElseThrow(() -> new IllegalStateException("no SLA policy configured")));

        Instant now = Instant.now();
        Ticket t = new Ticket();
        t.setRef(nextRef());
        t.setOrgId(orgId);
        t.setOrgName(resolveOrgName(p, orgId, staff));
        t.setSubject(form.subject());
        t.setDescription(form.description());
        t.setCategoryId(category.getId());
        t.setPriority(priority);
        t.setStatus(TicketStatus.NEW);
        t.setTeamId(category.getDefaultTeamId());
        t.setQueueId(category.getDefaultTeamId() == null ? null
                : queues.findByTeamId(category.getDefaultTeamId()).stream()
                        .findFirst().map(Queue::getId).orElse(null));
        t.setRequesterId(p.userId());
        t.setSlaPolicyId(policy.getId());
        t.setSlaResponseDeadline(now.plus(Duration.ofMinutes(policy.getResponseMinutes())));
        t.setSlaResolutionDeadline(now.plus(Duration.ofMinutes(policy.getResolutionMinutes())));
        t.setSlaStatus(SlaStatus.ON_TRACK);
        tickets.save(t);

        audit.record(p, "ticket.created", "ticket", t.getId().toString(), t.getRef(),
                null, t.getRef() + " status=" + t.getStatus() + " priority=" + priority);
        webhooks.publish("ticket.created", publicPayload("ticket.created", t, p));
        return t;
    }

    public TicketMessage reply(RequestPrincipal p, UUID ticketId, String body) {
        Ticket t = load(ticketId);
        if (p.isStaff()) {
            AccessGuard.requireTicketWorkable(p, t);
        } else {
            AccessGuard.requireTicketVisible(p, t);
        }
        boolean staffAuthor = p.isStaff();
        TicketStatus before = t.getStatus();

        if (before == TicketStatus.CLOSED) {
            TicketTransitions.require(before, TicketStatus.OPEN);
            t.setStatus(TicketStatus.OPEN);
            audit.record(p, "ticket.reopened", "ticket", t.getId().toString(), t.getRef(),
                    "CLOSED", "OPEN");
        }
        if (staffAuthor && t.getStatus() == TicketStatus.PENDING_CUSTOMER) {
            TicketTransitions.require(t.getStatus(), TicketStatus.OPEN);
            t.setStatus(TicketStatus.OPEN);
        } else if (!staffAuthor && t.getStatus() == TicketStatus.PENDING_CUSTOMER) {
            markCustomerReplied(t);
        }
        if (staffAuthor && t.getFirstResponseAt() == null) {
            t.setFirstResponseAt(Instant.now());
        }

        TicketMessage m = new TicketMessage();
        m.setTicketId(ticketId);
        m.setAuthorId(p.userId());
        m.setBody(body);
        m.setInternal(false);
        tickets.save(t);
        messages.save(m);

        audit.record(p, "ticket.replied", "ticket", t.getId().toString(), t.getRef(),
                before.name(), t.getStatus().name());
        webhooks.publish("ticket.replied", publicPayload("ticket.replied", t, p));
        if (staffAuthor) {
            notifications.notifyUser(t.getRequesterId(), "ticket", "New reply on " + t.getRef(),
                    "A support agent replied to your ticket.", "/portal/tickets/" + t.getId());
        } else if (t.getAssigneeId() != null) {
            notifications.notifyUser(t.getAssigneeId(), "ticket", "Customer replied on " + t.getRef(),
                    "The customer replied to the ticket.", "/desk/tickets/" + t.getId());
        }
        return m;
    }

    public TicketMessage internalNote(RequestPrincipal p, UUID ticketId, String body) {
        Ticket t = load(ticketId);
        AccessGuard.requireTicketWorkable(p, t);
        TicketMessage m = new TicketMessage();
        m.setTicketId(ticketId);
        m.setAuthorId(p.userId());
        m.setBody(body);
        m.setInternal(true);
        messages.save(m);
        // The note body is sensitive internal content — the audit trail records only that a
        // note was added (and by whom), never the body itself. No webhook, no customer notice.
        audit.record(p, "ticket.note_added", "ticket", t.getId().toString(), t.getRef(),
                null, "internal note added by " + p.displayName() + " (" + body.length() + " chars)");
        return m;
    }

    public Ticket changeStatus(RequestPrincipal p, UUID ticketId, TicketStatus target, String comment) {
        Ticket t = load(ticketId);
        AccessGuard.requireTicketWorkable(p, t);
        TicketStatus before = t.getStatus();
        TicketTransitions.require(before, target);
        t.setStatus(target);
        if (target == TicketStatus.RESOLVED && t.getResolvedAt() == null) {
            t.setResolvedAt(Instant.now());
        }
        if (target == TicketStatus.CLOSED) {
            t.setClosedAt(Instant.now());
        }
        if (target == TicketStatus.OPEN && (before == TicketStatus.RESOLVED || before == TicketStatus.CLOSED)) {
            audit.record(p, "ticket.reopened", "ticket", t.getId().toString(), t.getRef(),
                    before.name(), "OPEN");
        }
        if (target == TicketStatus.RESOLVED) {
            sla.evaluate(t);
        }
        tickets.save(t);
        audit.record(p, "ticket.status_changed", "ticket", t.getId().toString(), t.getRef(),
                before.name(), target.name());
        webhooks.publish("ticket.status_changed", publicPayload("ticket.status_changed", t, p));
        String note = (comment == null || comment.isBlank()) ? "" : " — " + comment;
        notifications.notifyUser(t.getRequesterId(), "ticket",
                "Ticket " + t.getRef() + " updated",
                "Your ticket status is now " + target + note, "/portal/tickets/" + t.getId());
        return t;
    }

    public Ticket claim(RequestPrincipal p, UUID ticketId) {
        if (!(p.role() == Role.SUPPORT_AGENT || p.isAdmin())) {
            throw new AccessDeniedException("only support agents can claim tickets");
        }
        Ticket t = load(ticketId);
        AccessGuard.requireTicketWorkable(p, t);
        if (t.getAssigneeId() != null) {
            throw new IllegalStateException("ticket already assigned");
        }
        TicketStatus before = t.getStatus();
        if (before == TicketStatus.NEW) {
            TicketTransitions.require(before, TicketStatus.OPEN);
            t.setStatus(TicketStatus.OPEN);
        }
        t.setAssigneeId(p.userId());
        tickets.save(t);
        audit.record(p, "ticket.claimed", "ticket", t.getId().toString(), t.getRef(),
                before.name(), t.getStatus().name() + " assignee=" + p.userId());
        return t;
    }

    public Ticket reassign(RequestPrincipal p, UUID ticketId, UUID assigneeId, UUID teamId) {
        if (!p.isStaff()) {
            throw new AccessDeniedException("customers cannot reassign tickets");
        }
        Ticket t = load(ticketId);
        UUID effectiveTeam = t.getTeamId() != null ? t.getTeamId() : teamId;
        if (!p.isAdmin()) {
            if (effectiveTeam == null) {
                throw new AccessDeniedException("lead cannot reassign a team-less ticket");
            }
            AccessGuard.requireManagedTeam(p, effectiveTeam);
        }
        if (assigneeId == null && teamId == null) {
            throw new IllegalStateException("assignee or team required");
        }
        String before = "team=" + t.getTeamId() + " assignee=" + t.getAssigneeId();
        if (assigneeId != null) {
            UserAccount assignee = users.findById(assigneeId)
                    .orElseThrow(() -> new ResourceNotFoundException("assignee not found"));
            if (!(assignee.getRole() == Role.SUPPORT_AGENT || assignee.getRole() == Role.TEAM_LEAD)) {
                throw new IllegalStateException("assignee must be an agent or team lead");
            }
            t.setAssigneeId(assigneeId);
        }
        if (teamId != null) {
            teams.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("team not found"));
            if (!teamId.equals(t.getTeamId())) {
                t.setTeamId(teamId);
                t.setQueueId(queues.findByTeamId(teamId).stream()
                        .findFirst().map(Queue::getId).orElse(null));
            }
        }
        tickets.save(t);
        audit.record(p, "ticket.reassigned", "ticket", t.getId().toString(), t.getRef(),
                before, "team=" + t.getTeamId() + " assignee=" + t.getAssigneeId());
        if (assigneeId != null && !assigneeId.equals(p.userId())) {
            notifications.notifyUser(assigneeId, "ticket", "Ticket " + t.getRef() + " assigned to you",
                    "You have been assigned this ticket.", "/desk/tickets/" + t.getId());
        }
        return t;
    }

    public Ticket changePriority(RequestPrincipal p, UUID ticketId, Priority priority) {
        Ticket t = load(ticketId);
        if (!p.isAdmin()) {
            AccessGuard.requireManagedTeam(p, t.getTeamId());
        }
        Priority before = t.getPriority();
        t.setPriority(priority);
        SlaPolicy policy = slaPolicies.findFirstByPriorityAndActiveTrue(priority).orElse(null);
        if (policy != null) {
            Instant now = Instant.now();
            t.setSlaPolicyId(policy.getId());
            t.setSlaResponseDeadline(now.plus(Duration.ofMinutes(policy.getResponseMinutes())));
            t.setSlaResolutionDeadline(now.plus(Duration.ofMinutes(policy.getResolutionMinutes())));
        }
        tickets.save(t);
        audit.record(p, "ticket.priority_changed", "ticket", t.getId().toString(), t.getRef(),
                before.name(), priority.name());
        webhooks.publish("ticket.status_changed", publicPayload("ticket.status_changed", t, p));
        return t;
    }

    public void delete(RequestPrincipal p, UUID ticketId) {
        if (!p.isAdmin()) {
            throw new AccessDeniedException("admin only");
        }
        Ticket t = load(ticketId);
        String ref = t.getRef();
        for (Attachment a : attachments.findByTicketIdOrderByCreatedAtAsc(ticketId)) {
            storage.deleteQuietly(a.getStoredName());
        }
        escalations.findByTicketIdOrderByCreatedAtAsc(ticketId).forEach(escalations::delete);
        tickets.delete(t);
        audit.record(p, "ticket.deleted", "ticket", ticketId.toString(), ref, ref, "deleted");
    }

    /** PENDING_CUSTOMER → OPEN on customer reply (per the state machine). */
    public void markCustomerReplied(Ticket t) {
        if (t.getStatus() == TicketStatus.PENDING_CUSTOMER) {
            TicketTransitions.require(t.getStatus(), TicketStatus.OPEN);
            t.setStatus(TicketStatus.OPEN);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Ticket load(UUID ticketId) {
        return tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("ticket not found"));
    }

    private String nextRef() {
        TicketSequence seq = sequences.lockSequence().orElseGet(() -> {
            TicketSequence s = new TicketSequence();
            s.setNextValue(1001);
            return sequences.save(s);
        });
        long next = seq.getNextValue();
        seq.setNextValue(next + 1);
        sequences.save(seq);
        return "TCK-" + next;
    }

    private String resolveOrgName(RequestPrincipal p, UUID orgId, boolean staff) {
        try {
            if (staff) {
                return core.org(p, orgId).name();
            }
            return core.listOrgs(p, null, 0, 50).getContent().stream()
                    .filter(o -> o.id().equals(orgId))
                    .map(o -> o.name())
                    .findFirst()
                    .orElseGet(() -> fallbackOrgName(orgId));
        } catch (CoreClientException e) {
            return fallbackOrgName(orgId); // graceful: the org name snapshot must not block creation
        }
    }

    private String fallbackOrgName(UUID orgId) {
        return "Organization " + orgId.toString().substring(0, 8);
    }

    /** Public-fields-only webhook payload: never internal notes or escalation content. */
    private String publicPayload(String event, Ticket t, RequestPrincipal p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("event", event);
        n.put("occurredAt", Instant.now().toString());
        n.put("ref", t.getRef());
        n.put("subject", t.getSubject());
        n.put("status", t.getStatus().name());
        n.put("priority", t.getPriority().name());
        n.put("orgName", t.getOrgName());
        n.put("actor", p.displayName() == null ? p.email() : p.displayName());
        return n.toString();
    }
}
