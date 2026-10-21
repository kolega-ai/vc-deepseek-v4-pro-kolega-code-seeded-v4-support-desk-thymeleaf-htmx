package com.relaydesk.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relaydesk.channel.domain.Escalation;
import com.relaydesk.channel.domain.EscalationStatus;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.domain.UserTeam;
import com.relaydesk.channel.dto.EscalateForm;
import com.relaydesk.channel.dto.EscalationDetailView;
import com.relaydesk.channel.dto.EscalationFilter;
import com.relaydesk.channel.dto.EscalationResolveForm;
import com.relaydesk.channel.dto.EscalationRow;
import com.relaydesk.channel.dto.TicketDetailView;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.EscalationRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.repo.UserTeamRepository;
import com.relaydesk.shared.Role;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Escalations: internal-only workflow. Reasons, resolutions and SLA-exception content never
 * appear in customer portals, exports, webhooks or customer notifications.
 */
@Service
@Transactional
public class EscalationService {

    private final EscalationRepository escalations;
    private final TicketRepository tickets;
    private final UserAccountRepository users;
    private final UserTeamRepository userTeams;
    private final TeamRepository teams;
    private final TicketQueryService ticketQuery;
    private final AuditService audit;
    private final NotificationService notifications;
    private final WebhookService webhooks;
    private final ObjectMapper mapper;

    public EscalationService(EscalationRepository escalations, TicketRepository tickets,
                             UserAccountRepository users, UserTeamRepository userTeams,
                             TeamRepository teams, TicketQueryService ticketQuery,
                             AuditService audit, NotificationService notifications,
                             WebhookService webhooks, ObjectMapper mapper) {
        this.escalations = escalations;
        this.tickets = tickets;
        this.users = users;
        this.userTeams = userTeams;
        this.teams = teams;
        this.ticketQuery = ticketQuery;
        this.audit = audit;
        this.notifications = notifications;
        this.webhooks = webhooks;
        this.mapper = mapper;
    }

    public Escalation create(RequestPrincipal p, UUID ticketId, EscalateForm form) {
        Ticket t = tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("ticket not found"));
        AccessGuard.requireTicketWorkable(p, t);
        if (!(t.getStatus() == TicketStatus.OPEN || t.getStatus() == TicketStatus.PENDING_CUSTOMER
                || t.getStatus() == TicketStatus.PENDING_INTERNAL)) {
            throw new IllegalStateException("ticket cannot be escalated from status " + t.getStatus());
        }
        if (t.getTeamId() == null) {
            throw new IllegalStateException("ticket has no team");
        }
        TicketStatus before = t.getStatus();
        TicketTransitions.require(before, TicketStatus.ESCALATED);
        t.setStatus(TicketStatus.ESCALATED);
        tickets.save(t);
        audit.record(p, "ticket.status_changed", "ticket", t.getId().toString(), t.getRef(),
                before.name(), "ESCALATED");

        Escalation e = new Escalation();
        e.setTicketId(ticketId);
        e.setRequesterId(p.userId());
        e.setTeamId(t.getTeamId());
        e.setReason(form.reason());
        e.setSlaException(Boolean.TRUE.equals(form.slaException()));
        e.setSlaExceptionReason(form.slaExceptionReason());
        e.setStatus(EscalationStatus.REQUESTED);
        e.setAssigneeId(findLeadForTeam(t.getTeamId()));
        escalations.save(e);
        audit.record(p, "escalation.created", "escalation", e.getId().toString(), t.getRef(),
                null, e.getReason());
        if (e.getAssigneeId() != null) {
            notifications.notifyUser(e.getAssigneeId(), "escalation",
                    "New escalation for " + t.getRef(),
                    "A ticket in your team has been escalated.",
                    "/desk/escalations/" + e.getId());
        }
        // No webhook: escalations are internal.
        return e;
    }

    public EscalationDetailView detail(RequestPrincipal p, UUID id) {
        Escalation e = load(id);
        AccessGuard.requireEscalationVisible(p, e);
        TicketDetailView ticket = ticketQuery.getTicketDetail(p, e.getTicketId());
        boolean canDecide = (p.isAdmin() || (p.isLead() && p.teamIds().contains(e.getTeamId())))
                && (e.getStatus() == EscalationStatus.REQUESTED
                        || e.getStatus() == EscalationStatus.ACCEPTED
                        || e.getStatus() == EscalationStatus.IN_REVIEW);
        Map<UUID, String> teamNames = teams.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        EscalationRow row = toRow(e, teamNames);
        return new EscalationDetailView(row, e.getSlaExceptionReason(), e.getResolution(),
                e.getResolvedAt(), ticket, ticket.audit(), canDecide);
    }

    public Page<EscalationRow> query(RequestPrincipal p, EscalationFilter f, Pageable pageable) {
        if (!p.isStaff()) {
            throw new AccessDeniedException("escalations are internal");
        }
        EscalationFilter filter = f == null
                ? new EscalationFilter(null, null, null, null, null) : f;
        Specification<Escalation> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (filter.status() != null) {
                ps.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.teamId() != null) {
                ps.add(cb.equal(root.get("teamId"), filter.teamId()));
            }
            if (filter.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        filter.from().atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (filter.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"),
                        filter.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (filter.q() != null) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<Ticket> t = sub.from(Ticket.class);
                sub.select(t.get("id")).where(cb.like(cb.lower(t.get("subject")),
                        "%" + filter.q().toLowerCase(Locale.ROOT) + "%"));
                ps.add(root.get("ticketId").in(sub));
            }
            if (p.isAdmin()) {
                // all escalations
            } else if (p.isLead()) {
                ps.add(p.teamIds().isEmpty() ? cb.disjunction() : root.get("teamId").in(p.teamIds()));
            } else {
                ps.add(cb.equal(root.get("requesterId"), p.userId()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Escalation> page = escalations.findAll(spec, pr);

        Set<UUID> ticketIds = page.getContent().stream().map(Escalation::getTicketId).collect(Collectors.toSet());
        Map<UUID, Ticket> ticketMap = ticketIds.isEmpty() ? Map.of()
                : tickets.findAllById(ticketIds).stream().collect(Collectors.toMap(Ticket::getId, t -> t));
        Map<UUID, String> teamNames = teams.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        List<EscalationRow> rows = page.getContent().stream()
                .map(e -> toRow(e, ticketMap, teamNames))
                .toList();
        audit.recordRead(p, "escalation.list", rows.size());
        return new PageImpl<>(rows, pr, page.getTotalElements());
    }

    public Escalation accept(RequestPrincipal p, UUID id) {
        Escalation e = load(id);
        requireDecide(p, e);
        EscalationStatus before = e.getStatus();
        EscalationTransitions.require(before, EscalationStatus.ACCEPTED);
        e.setStatus(EscalationStatus.ACCEPTED);
        escalations.save(e);
        audit.record(p, "escalation.accepted", "escalation", id.toString(), ticketRef(e),
                before.name(), "ACCEPTED");
        notifications.notifyUser(e.getRequesterId(), "escalation", "Escalation accepted",
                "Your escalation is being handled.", "/desk/escalations/" + id);
        return e;
    }

    public Escalation reject(RequestPrincipal p, UUID id, String reason) {
        Escalation e = load(id);
        requireDecide(p, e);
        EscalationStatus before = e.getStatus();
        EscalationTransitions.require(before, EscalationStatus.REJECTED);
        e.setStatus(EscalationStatus.REJECTED);
        e.setResolution(reason);
        escalations.save(e);
        Ticket t = tickets.findById(e.getTicketId()).orElse(null);
        if (t != null && t.getStatus() == TicketStatus.ESCALATED) {
            TicketTransitions.require(t.getStatus(), TicketStatus.OPEN);
            t.setStatus(TicketStatus.OPEN);
            tickets.save(t);
            audit.record(p, "ticket.status_changed", "ticket", t.getId().toString(), t.getRef(),
                    "ESCALATED", "OPEN");
        }
        audit.record(p, "escalation.rejected", "escalation", id.toString(), ticketRef(e),
                before.name(), "REJECTED");
        notifications.notifyUser(e.getRequesterId(), "escalation", "Escalation rejected",
                "Your escalation was rejected and the ticket was returned to the queue.",
                "/desk/escalations/" + id);
        return e;
    }

    public Escalation startReview(RequestPrincipal p, UUID id) {
        Escalation e = load(id);
        requireDecide(p, e);
        EscalationStatus before = e.getStatus();
        EscalationTransitions.require(before, EscalationStatus.IN_REVIEW);
        e.setStatus(EscalationStatus.IN_REVIEW);
        escalations.save(e);
        audit.record(p, "escalation.review_started", "escalation", id.toString(), ticketRef(e),
                before.name(), "IN_REVIEW");
        notifications.notifyUser(e.getRequesterId(), "escalation", "Escalation in review",
                "Your escalation is being reviewed.", "/desk/escalations/" + id);
        return e;
    }

    public Escalation resolve(RequestPrincipal p, UUID id, EscalationResolveForm form) {
        Escalation e = load(id);
        requireDecide(p, e);
        EscalationStatus before = e.getStatus();
        EscalationTransitions.require(before, EscalationStatus.RESOLVED);
        e.setStatus(EscalationStatus.RESOLVED);
        e.setResolution(form.resolution());
        e.setResolvedAt(Instant.now());
        escalations.save(e);

        Ticket t = tickets.findById(e.getTicketId()).orElse(null);
        if (t != null && t.getStatus() == TicketStatus.ESCALATED) {
            if (Boolean.TRUE.equals(form.resolveTicket())) {
                TicketTransitions.require(t.getStatus(), TicketStatus.RESOLVED);
                t.setStatus(TicketStatus.RESOLVED);
                if (t.getResolvedAt() == null) {
                    t.setResolvedAt(Instant.now());
                }
            } else {
                TicketTransitions.require(t.getStatus(), TicketStatus.OPEN);
                t.setStatus(TicketStatus.OPEN);
            }
            tickets.save(t);
            audit.record(p, "ticket.status_changed", "ticket", t.getId().toString(), t.getRef(),
                    "ESCALATED", t.getStatus().name());
            webhooks.publish("ticket.status_changed", ticketPayload("ticket.status_changed", t, p));
        }
        audit.record(p, "escalation.resolved", "escalation", id.toString(), ticketRef(e),
                before.name(), "RESOLVED");
        notifications.notifyUser(e.getRequesterId(), "escalation", "Escalation resolved",
                "Your escalation has been resolved.", "/desk/escalations/" + id);
        if (t != null) {
            // Public ticket fields only — never the escalation resolution text.
            webhooks.publish("escalation.resolved", ticketPayload("escalation.resolved", t, p));
        }
        return e;
    }

    /**
     * Withdraws an escalation: the escalation record is removed and the ticket goes back to
     * the open queue. Used when the agent who raised it has since solved the ticket
     * themselves and no longer needs the lead to pick it up.
     */
    public void withdraw(RequestPrincipal p, UUID id, String reason) {
        Escalation e = load(id);
        AccessGuard.requireEscalationVisible(p, e);
        String ref = ticketRef(e);
        Ticket t = tickets.findById(e.getTicketId()).orElse(null);
        UUID assigneeId = e.getAssigneeId();
        escalations.delete(e);
        if (t != null && t.getStatus() == TicketStatus.ESCALATED) {
            t.setStatus(TicketStatus.OPEN);
            tickets.save(t);
            audit.record(p, "ticket.status_changed", "ticket", t.getId().toString(), t.getRef(),
                    "ESCALATED", "OPEN");
        }
        audit.record(p, "escalation.withdrawn", "escalation", id.toString(), ref,
                "escalation", reason);
        if (assigneeId != null) {
            notifications.notifyUser(assigneeId, "escalation", "Escalation withdrawn",
                    "An escalation assigned to you was withdrawn by the requester.",
                    "/desk/escalations");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Escalation load(UUID id) {
        return escalations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("escalation not found"));
    }

    private void requireDecide(RequestPrincipal p, Escalation e) {
        if (!p.isAdmin() && !(p.isLead() && p.teamIds().contains(e.getTeamId()))) {
            throw new AccessDeniedException("only the team lead or an admin can decide escalations");
        }
    }

    private String ticketRef(Escalation e) {
        return tickets.findById(e.getTicketId()).map(Ticket::getRef).orElse(null);
    }

    private UUID findLeadForTeam(UUID teamId) {
        List<UserTeam> memberships = userTeams.findByTeamId(teamId);
        Set<UUID> memberIds = memberships.stream().map(UserTeam::getUserId).collect(Collectors.toSet());
        List<UserAccount> leads = users.findAll((root, q, cb) ->
                cb.equal(root.get("role"), Role.TEAM_LEAD));
        return leads.stream().filter(l -> memberIds.contains(l.getId()))
                .map(UserAccount::getId).findFirst()
                .orElseGet(() -> leads.stream().map(UserAccount::getId).findFirst().orElse(null));
    }

    private EscalationRow toRow(Escalation e, Map<UUID, String> teamNames) {
        Ticket t = tickets.findById(e.getTicketId()).orElse(null);
        return toRow(e, t == null ? Map.of() : Map.of(e.getTicketId(), t), teamNames);
    }

    private EscalationRow toRow(Escalation e, Map<UUID, Ticket> ticketMap, Map<UUID, String> teamNames) {
        Ticket t = ticketMap.get(e.getTicketId());
        Map<UUID, UserAccount> accountMap = accounts(e.getRequesterId(), e.getAssigneeId());
        UserAccount requester = accountMap.get(e.getRequesterId());
        UserAccount assignee = e.getAssigneeId() == null ? null : accountMap.get(e.getAssigneeId());
        return new EscalationRow(e.getId(), e.getTicketId(),
                t == null ? null : t.getRef(), t == null ? null : t.getSubject(),
                requester == null ? null : requester.getDisplayName(),
                assignee == null ? null : assignee.getDisplayName(),
                teamNames.get(e.getTeamId()), e.getStatus(), e.getReason(), e.isSlaException(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private Map<UUID, UserAccount> accounts(UUID... ids) {
        Set<UUID> distinct = java.util.Arrays.stream(ids)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(distinct).stream()
                .collect(Collectors.toMap(UserAccount::getId, u -> u));
    }

    private String ticketPayload(String event, Ticket t, RequestPrincipal p) {
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
