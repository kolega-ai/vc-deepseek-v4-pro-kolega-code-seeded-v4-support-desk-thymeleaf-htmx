package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Attachment;
import com.relaydesk.channel.domain.AttachmentCategory;
import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.Escalation;
import com.relaydesk.channel.domain.EscalationStatus;
import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.domain.Queue;
import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketMessage;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.AttachmentRow;
import com.relaydesk.channel.dto.AuditRow;
import com.relaydesk.channel.dto.CountBucket;
import com.relaydesk.channel.dto.DashboardStats;
import com.relaydesk.channel.dto.EscalationRow;
import com.relaydesk.channel.dto.MessageRow;
import com.relaydesk.channel.dto.SlaMilestone;
import com.relaydesk.channel.dto.TicketDetailView;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.dto.TicketSummaryRow;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.AttachmentRepository;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.EscalationRepository;
import com.relaydesk.channel.repo.KnowledgeArticleRepository;
import com.relaydesk.channel.repo.QueueRepository;
import com.relaydesk.channel.repo.SlaPolicyRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.TicketMessageRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side ticket queries and dashboards.
 */
@Service
public class TicketQueryService {

    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER,
            TicketStatus.PENDING_INTERNAL, TicketStatus.ESCALATED);

    /**
     * Precision #4: bounded, whitelisted sort column + parameterised filter values. The sort
     * column is selected from this fixed whitelist map (unknown values fall back to createdAt);
     * every filter value is bound as a JPA parameter — never string-built SQL.
     */
    static final Map<String, String> SORT_COLUMNS = Map.of(
            "created", "createdAt",
            "updated", "updatedAt",
            "priority", "priority",
            "sla", "slaResolutionDeadline",
            "subject", "subject");

    private final TicketRepository tickets;
    private final TeamRepository teams;
    private final QueueRepository queues;
    private final CategoryRepository categories;
    private final SlaPolicyRepository slaPolicies;
    private final UserAccountRepository users;
    private final TicketMessageRepository messages;
    private final AttachmentRepository attachments;
    private final EscalationRepository escalations;
    private final KnowledgeArticleRepository kb;
    private final AuditService audit;
    private final NotificationService notifications;

    public TicketQueryService(TicketRepository tickets, TeamRepository teams,
                              QueueRepository queues, CategoryRepository categories,
                              SlaPolicyRepository slaPolicies, UserAccountRepository users,
                              TicketMessageRepository messages, AttachmentRepository attachments,
                              EscalationRepository escalations, KnowledgeArticleRepository kb,
                              AuditService audit, NotificationService notifications) {
        this.tickets = tickets;
        this.teams = teams;
        this.queues = queues;
        this.categories = categories;
        this.slaPolicies = slaPolicies;
        this.users = users;
        this.messages = messages;
        this.attachments = attachments;
        this.escalations = escalations;
        this.kb = kb;
        this.audit = audit;
        this.notifications = notifications;
    }

    static String whitelistColumn(String sort) {
        return SORT_COLUMNS.getOrDefault(sort == null ? "created" : sort, "createdAt");
    }

    static Sort resolveSort(String sort) {
        String column = whitelistColumn(sort);
        Sort.Direction direction = "subject".equals(column) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, column);
    }

    /**
     * Precision #1: the ownership/scope check lives in this shared service, one call frame
     * above the controller — controllers never inline access checks.
     */
    public TicketDetailView getTicketDetail(RequestPrincipal p, UUID id) {
        Ticket t = tickets.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ticket not found"));
        AccessGuard.requireTicketVisible(p, t);
        boolean customer = !p.isStaff();

        Set<UUID> userIds = new java.util.HashSet<>();
        userIds.add(t.getRequesterId());
        if (t.getAssigneeId() != null) {
            userIds.add(t.getAssigneeId());
        }
        Map<UUID, UserAccount> accountMap = userMap(userIds);

        String categoryName = categories.findById(t.getCategoryId()).map(Category::getName).orElse(null);
        String teamName = t.getTeamId() == null ? null
                : teams.findById(t.getTeamId()).map(Team::getName).orElse(null);
        String queueName = t.getQueueId() == null ? null
                : queues.findById(t.getQueueId()).map(Queue::getName).orElse(null);
        String slaPolicyName = slaPolicies.findById(t.getSlaPolicyId())
                .map(pol -> pol.getName()).orElse(null);

        UserAccount requester = accountMap.get(t.getRequesterId());
        UserAccount assignee = t.getAssigneeId() == null ? null : accountMap.get(t.getAssigneeId());

        List<TicketMessage> thread = messages.findByTicketIdOrderByCreatedAtAsc(t.getId());
        if (customer) {
            thread = thread.stream().filter(m -> !m.isInternal()).toList();
        }
        Set<UUID> authorIds = thread.stream().map(TicketMessage::getAuthorId).collect(Collectors.toSet());
        Map<UUID, UserAccount> authors = userMap(authorIds);
        List<MessageRow> messageRows = thread.stream()
                .map(m -> {
                    UserAccount a = authors.get(m.getAuthorId());
                    return new MessageRow(m.getId(),
                            a == null ? null : a.getDisplayName(),
                            a == null ? null : a.getRole().name(),
                            m.getBody(), m.isInternal(), m.getCreatedAt());
                })
                .toList();

        List<Attachment> atts = attachments.findByTicketIdOrderByCreatedAtAsc(t.getId());
        if (customer) {
            atts = atts.stream().filter(a -> a.getCategory() == AttachmentCategory.CUSTOMER_ATTACHMENT).toList();
        }
        Set<UUID> uploaderIds = atts.stream().map(Attachment::getUploaderId).collect(Collectors.toSet());
        Map<UUID, UserAccount> uploaders = userMap(uploaderIds);
        List<AttachmentRow> attachmentRows = atts.stream()
                .map(a -> {
                    UserAccount u = uploaders.get(a.getUploaderId());
                    return new AttachmentRow(a.getId(), a.getOriginalName(), a.getContentType(),
                            a.getSizeBytes(), a.getCategory(),
                            u == null ? null : u.getDisplayName(), a.getCreatedAt());
                })
                .toList();

        EscalationRow escalationRow = null;
        if (!customer) {
            escalationRow = escalations.findByTicketId(t.getId())
                    .map(e -> toEscalationRow(e, t, accountMap, teamName))
                    .orElse(null);
        }

        List<AuditRow> auditRows = customer ? List.of() : audit.forTicket(t.getId());
        List<SlaMilestone> timeline = buildTimeline(t);

        boolean workable = p.isAdmin()
                || (p.isStaff() && (t.getTeamId() == null || p.teamIds().contains(t.getTeamId())));
        boolean canReply = p.isStaff()
                ? workable && t.getStatus() != TicketStatus.CLOSED
                : t.getStatus() != TicketStatus.CLOSED;
        boolean canClaim = p.role() == com.relaydesk.shared.Role.SUPPORT_AGENT
                && (t.getTeamId() == null || p.teamIds().contains(t.getTeamId()))
                && t.getAssigneeId() == null;
        boolean canAssign = p.isAdmin()
                || (p.isLead() && (t.getTeamId() == null || p.teamIds().contains(t.getTeamId())));
        boolean canEscalate = workable && Arrays.asList(
                TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER,
                TicketStatus.PENDING_INTERNAL).contains(t.getStatus());

        audit.recordRead(p, "ticket", 1);

        return new TicketDetailView(
                t.getId(), t.getRef(), t.getSubject(), t.getDescription(),
                t.getOrgId(), t.getOrgName(), t.getStatus(), t.getPriority(), t.getSlaStatus(),
                categoryName, teamName, queueName,
                assignee == null ? null : assignee.getDisplayName(),
                requester == null ? null : requester.getDisplayName(),
                requester == null ? null : requester.getEmail(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getResolvedAt(), t.getClosedAt(),
                t.getSlaResponseDeadline(), t.getSlaResolutionDeadline(), t.getFirstResponseAt(),
                slaPolicyName, messageRows, attachmentRows, escalationRow, auditRows, timeline,
                canReply, workable && p.isStaff(), workable && p.isStaff(), canClaim, canAssign,
                canEscalate);
    }

    public Page<TicketSummaryRow> query(RequestPrincipal p, TicketFilter f, Pageable pageable) {
        TicketFilter filter = f == null
                ? new TicketFilter(null, null, null, null, null, null, false, null, null, null, null)
                : f;
        Specification<Ticket> spec = filterSpec(p, filter);
        PageRequest pr = PageRequest.of(Math.max(0, pageable.getPageNumber()),
                Math.max(1, pageable.getPageSize()), resolveSort(filter.sort()));
        Page<Ticket> page = tickets.findAll(spec, pr);

        Set<UUID> teamIds = page.getContent().stream().map(Ticket::getTeamId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> queueIds = page.getContent().stream().map(Ticket::getQueueId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> categoryIds = page.getContent().stream().map(Ticket::getCategoryId)
                .collect(Collectors.toSet());
        Set<UUID> userIds = page.getContent().stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getAssigneeId(), t.getRequesterId()))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> teamNames = teamNames(teamIds);
        Map<UUID, String> queueNames = queueNames(queueIds);
        Map<UUID, String> categoryNames = categoryNames(categoryIds);
        Map<UUID, UserAccount> userMap = userMap(userIds);

        List<TicketSummaryRow> rows = page.getContent().stream()
                .map(t -> toSummaryRow(t, teamNames, queueNames, categoryNames, userMap))
                .toList();
        audit.recordRead(p, "ticket.list", rows.size());
        return new PageImpl<>(rows, pr, page.getTotalElements());
    }

    public List<TicketSummaryRow> recent(RequestPrincipal p, int limit) {
        TicketFilter f = new TicketFilter(null, null, null, null, null, null, false,
                null, null, null, "created");
        return query(p, f, PageRequest.of(0, Math.max(1, limit))).getContent();
    }

    public DashboardStats dashboard(RequestPrincipal p) {
        Specification<Ticket> scope = scopeSpec(p);
        boolean customer = !p.isStaff();
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();

        long openTickets = tickets.count(scope.and(statusInSpec(ACTIVE_STATUSES)));
        long newTickets = customer ? 0 : tickets.count(scope.and(statusSpec(TicketStatus.NEW)));
        long overdueTickets = customer ? 0 : tickets.count(scope.and(statusInSpec(ACTIVE_STATUSES)
                .and((root, q, cb) -> cb.equal(root.get("slaStatus"), SlaStatus.BREACHED))));
        long escalatedTickets = customer ? 0
                : tickets.count(scope.and(statusSpec(TicketStatus.ESCALATED)));
        long resolvedToday = tickets.count(scope.and((root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("resolvedAt"), startOfDay)));
        long myOpenTickets = (customer || p.userId() == null) ? 0
                : tickets.count(scope.and((root, q, cb) -> cb.equal(root.get("assigneeId"), p.userId()))
                        .and(statusInSpec(ACTIVE_STATUSES)));
        long pendingEscalations = 0;
        if (p.isLead() && !p.teamIds().isEmpty()) {
            pendingEscalations = escalations.count((root, q, cb) -> cb.and(
                    root.get("teamId").in(p.teamIds()),
                    root.get("status").in(EscalationStatus.REQUESTED,
                            EscalationStatus.ACCEPTED, EscalationStatus.IN_REVIEW)));
        }
        long totalTickets = tickets.count(scope);
        long kbPublished = kb.count((root, q, cb) -> cb.and(
                cb.equal(root.get("status"), KbStatus.PUBLISHED),
                cb.isFalse(root.get("internalOnly"))));
        List<CountBucket> buckets = Arrays.stream(TicketStatus.values())
                .map(st -> new CountBucket(st.name(), tickets.count(scope.and(statusSpec(st)))))
                .toList();
        long unread = notifications.unread(p);
        return new DashboardStats(openTickets, newTickets, overdueTickets, escalatedTickets,
                resolvedToday, myOpenTickets, unread, pendingEscalations, totalTickets,
                kbPublished, buckets);
    }

    // ------------------------------------------------------------------ specs & mapping

    private Specification<Ticket> filterSpec(RequestPrincipal p, TicketFilter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f.status() != null) {
                ps.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.priority() != null) {
                ps.add(cb.equal(root.get("priority"), f.priority()));
            }
            if (f.categoryId() != null) {
                ps.add(cb.equal(root.get("categoryId"), f.categoryId()));
            }
            if (f.teamId() != null) {
                ps.add(cb.equal(root.get("teamId"), f.teamId()));
            }
            if (f.queueId() != null) {
                ps.add(cb.equal(root.get("queueId"), f.queueId()));
            }
            if ("unassigned".equals(f.assignee())) {
                ps.add(cb.isNull(root.get("assigneeId")));
            } else if ("mine".equals(f.assignee()) && p.userId() != null) {
                ps.add(cb.equal(root.get("assigneeId"), p.userId()));
            }
            if (Boolean.TRUE.equals(f.overdueOnly())) {
                ps.add(cb.equal(root.get("slaStatus"), SlaStatus.BREACHED));
                ps.add(cb.lessThan(root.get("slaResolutionDeadline"), Instant.now()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        f.from().atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"),
                        f.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (f.q() != null) {
                String like = "%" + f.q().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("subject")), like),
                        cb.like(cb.lower(root.get("description")), like)));
            }
            ps.add(scopePredicate(root, cb, p));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Predicate scopePredicate(Root<Ticket> root, jakarta.persistence.criteria.CriteriaBuilder cb,
                                     RequestPrincipal p) {
        if (p.isAdmin()) {
            return cb.conjunction();
        }
        if (p.isStaff()) {
            Predicate teamScope = p.teamIds().isEmpty()
                    ? cb.disjunction() : root.get("teamId").in(p.teamIds());
            Predicate unassigned = cb.isNull(root.get("teamId"));
            Predicate mine = p.userId() == null
                    ? cb.disjunction() : cb.equal(root.get("assigneeId"), p.userId());
            return cb.or(teamScope, unassigned, mine);
        }
        return p.orgIds().isEmpty() ? cb.disjunction() : root.get("orgId").in(p.orgIds());
    }

    private Specification<Ticket> scopeSpec(RequestPrincipal p) {
        return (root, query, cb) -> scopePredicate(root, cb, p);
    }

    private Specification<Ticket> statusSpec(TicketStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private Specification<Ticket> statusInSpec(List<TicketStatus> statuses) {
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    private List<SlaMilestone> buildTimeline(Ticket t) {
        List<SlaMilestone> timeline = new ArrayList<>();
        timeline.add(new SlaMilestone("Created", t.getCreatedAt(), "created"));
        if (t.getFirstResponseAt() != null) {
            boolean breached = t.getFirstResponseAt().isAfter(t.getSlaResponseDeadline());
            timeline.add(new SlaMilestone("First response", t.getFirstResponseAt(),
                    breached ? "breached" : "met"));
        }
        String resolutionState = t.getResolvedAt() == null
                ? (t.getSlaStatus() == SlaStatus.BREACHED ? "breached" : "due") : "met";
        timeline.add(new SlaMilestone("Resolution due", t.getSlaResolutionDeadline(), resolutionState));
        if (t.getResolvedAt() != null) {
            timeline.add(new SlaMilestone("Resolved", t.getResolvedAt(), "resolved"));
        }
        if (t.getClosedAt() != null) {
            timeline.add(new SlaMilestone("Closed", t.getClosedAt(), "closed"));
        }
        return timeline;
    }

    private TicketSummaryRow toSummaryRow(Ticket t, Map<UUID, String> teamNames,
                                          Map<UUID, String> queueNames,
                                          Map<UUID, String> categoryNames,
                                          Map<UUID, UserAccount> userMap) {
        UserAccount assignee = t.getAssigneeId() == null ? null : userMap.get(t.getAssigneeId());
        UserAccount requester = userMap.get(t.getRequesterId());
        return new TicketSummaryRow(
                t.getId(), t.getRef(), t.getSubject(), t.getOrgName(),
                t.getStatus(), t.getPriority(), t.getSlaStatus(),
                categoryNames.get(t.getCategoryId()),
                teamNames.get(t.getTeamId()), queueNames.get(t.getQueueId()),
                assignee == null ? null
                        : (assignee.getDisplayName() != null ? assignee.getDisplayName() : assignee.getEmail()),
                requester == null ? null : requester.getEmail(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getSlaResolutionDeadline(),
                t.getSlaStatus() == SlaStatus.BREACHED);
    }

    private EscalationRow toEscalationRow(Escalation e, Ticket t, Map<UUID, UserAccount> userMap,
                                          String teamName) {
        UserAccount requester = userMap.get(e.getRequesterId());
        UserAccount assignee = e.getAssigneeId() == null ? null : userMap.get(e.getAssigneeId());
        return new EscalationRow(e.getId(), e.getTicketId(), t.getRef(), t.getSubject(),
                requester == null ? null : requester.getDisplayName(),
                assignee == null ? null : assignee.getDisplayName(),
                teamName, e.getStatus(), e.getReason(), e.isSlaException(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private Map<UUID, UserAccount> userMap(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(UserAccount::getId, u -> u));
    }

    private Map<UUID, String> teamNames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return teams.findAllById(ids).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
    }

    private Map<UUID, String> queueNames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return queues.findAllById(ids).stream()
                .collect(Collectors.toMap(Queue::getId, Queue::getName));
    }

    private Map<UUID, String> categoryNames(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categories.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }
}
