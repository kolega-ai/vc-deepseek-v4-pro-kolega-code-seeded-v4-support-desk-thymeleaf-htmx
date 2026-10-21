package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.SlaStatus;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.SlaReportFilter;
import com.relaydesk.channel.dto.SlaStats;
import com.relaydesk.channel.dto.TeamSlaRow;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.dto.TicketSummaryRow;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.repo.UserTeamRepository;
import com.relaydesk.shared.Role;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SLA evaluation and reporting. Deadlines are computed server-side from the active policy for
 * the ticket's priority — never accepted from the client.
 */
@Service
@Transactional
public class SlaService {

    private static final List<TicketStatus> ACTIVE_STATUSES = List.of(
            TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER,
            TicketStatus.PENDING_INTERNAL, TicketStatus.ESCALATED);

    private final TicketRepository tickets;
    private final TeamRepository teams;
    private final UserAccountRepository users;
    private final UserTeamRepository userTeams;
    private final TicketQueryService ticketQuery;
    private final AuditService audit;
    private final NotificationService notifications;

    public SlaService(TicketRepository tickets, TeamRepository teams,
                      UserAccountRepository users, UserTeamRepository userTeams,
                      TicketQueryService ticketQuery, AuditService audit,
                      NotificationService notifications) {
        this.tickets = tickets;
        this.teams = teams;
        this.users = users;
        this.userTeams = userTeams;
        this.ticketQuery = ticketQuery;
        this.audit = audit;
        this.notifications = notifications;
    }

    /**
     * Recomputes the SLA status of an active ticket. When the status changes to BREACHED the
     * assignee (or the ticket team's lead) is notified and a system audit event is written.
     */
    public void evaluate(Ticket t) {
        if (t.getStatus() == TicketStatus.RESOLVED || t.getStatus() == TicketStatus.CLOSED) {
            return;
        }
        Instant now = Instant.now();
        SlaStatus before = t.getSlaStatus();
        SlaStatus computed;
        if (now.isAfter(t.getSlaResolutionDeadline())) {
            computed = SlaStatus.BREACHED;
        } else {
            long total = Duration.between(t.getCreatedAt(), t.getSlaResolutionDeadline()).toMillis();
            long remaining = Duration.between(now, t.getSlaResolutionDeadline()).toMillis();
            computed = (total > 0 && remaining <= total / 4) ? SlaStatus.AT_RISK : SlaStatus.ON_TRACK;
        }
        if (computed != before) {
            t.setSlaStatus(computed);
            tickets.save(t);
            if (computed == SlaStatus.BREACHED) {
                UUID recipient = t.getAssigneeId() != null ? t.getAssigneeId() : findTeamLead(t.getTeamId());
                if (recipient != null) {
                    notifications.notifyUser(recipient, "sla", "SLA breached: " + t.getRef(),
                            "The resolution deadline for this ticket has passed.",
                            "/desk/tickets/" + t.getId());
                }
                audit.record(RequestPrincipal.system(), "sla.breached", "ticket",
                        t.getId().toString(), t.getRef(), before.name(), "BREACHED");
            }
        }
    }

    public SlaStats stats(SlaReportFilter f) {
        SlaReportFilter filter = f == null ? new SlaReportFilter(null, null, null) : f;
        Specification<Ticket> active = activeSpec(filter);
        long totalActive = tickets.count(active);
        long breached = tickets.count(active.and((root, q, cb) ->
                cb.equal(root.get("slaStatus"), SlaStatus.BREACHED)));
        long atRisk = tickets.count(active.and((root, q, cb) ->
                cb.equal(root.get("slaStatus"), SlaStatus.AT_RISK)));
        long onTrack = totalActive - breached - atRisk;

        List<Ticket> activeList = tickets.findAll(active);
        double avgResponseHours = avgHours(activeList, Ticket::getFirstResponseAt, Ticket::getCreatedAt);

        Specification<Ticket> resolved = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isNotNull(root.get("resolvedAt")));
            if (filter.teamId() != null) {
                ps.add(cb.equal(root.get("teamId"), filter.teamId()));
            }
            if (filter.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("resolvedAt"),
                        filter.from().atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            if (filter.to() != null) {
                ps.add(cb.lessThan(root.get("resolvedAt"),
                        filter.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<Ticket> resolvedList = tickets.findAll(resolved);
        double avgResolutionHours = avgHours(resolvedList, Ticket::getResolvedAt, Ticket::getCreatedAt);
        double breachRatePercent = totalActive == 0 ? 0 : (double) breached * 100 / totalActive;

        Map<UUID, String> teamNames = teams.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        List<TeamSlaRow> byTeam = activeList.stream()
                .filter(t -> t.getTeamId() != null)
                .collect(Collectors.groupingBy(Ticket::getTeamId))
                .entrySet().stream()
                .map(e -> {
                    List<Ticket> ts = e.getValue();
                    long b = ts.stream().filter(t -> t.getSlaStatus() == SlaStatus.BREACHED).count();
                    return new TeamSlaRow(teamNames.getOrDefault(e.getKey(), "Unknown"),
                            ts.size(), b, avgHours(ts, Ticket::getFirstResponseAt, Ticket::getCreatedAt));
                })
                .sorted(Comparator.comparing(TeamSlaRow::teamName))
                .toList();

        return new SlaStats(totalActive, breached, atRisk, onTrack,
                avgResponseHours, avgResolutionHours, breachRatePercent, byTeam);
    }

    public Page<TicketSummaryRow> overdue(RequestPrincipal p, Pageable pageable) {
        TicketFilter f = new TicketFilter(null, null, null, null, null, null, true,
                null, null, null, "created");
        return ticketQuery.query(p, f, pageable);
    }

    // ------------------------------------------------------------------ helpers

    private Specification<Ticket> activeSpec(SlaReportFilter filter) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(root.get("status").in(ACTIVE_STATUSES));
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
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private double avgHours(List<Ticket> list, Function<Ticket, Instant> end, Function<Ticket, Instant> start) {
        return list.stream()
                .filter(t -> end.apply(t) != null && start.apply(t) != null)
                .mapToDouble(t -> Duration.between(start.apply(t), end.apply(t)).toMillis() / 3_600_000.0)
                .average()
                .orElse(0.0);
    }

    private UUID findTeamLead(UUID teamId) {
        if (teamId == null) {
            return null;
        }
        List<UUID> memberIds = userTeams.findByTeamId(teamId).stream()
                .map(m -> m.getUserId()).toList();
        if (memberIds.isEmpty()) {
            return null;
        }
        List<UserAccount> leads = users.findAll((root, q, cb) ->
                cb.equal(root.get("role"), Role.TEAM_LEAD));
        return leads.stream().filter(l -> memberIds.contains(l.getId()))
                .map(UserAccount::getId).findFirst().orElse(null);
    }
}
