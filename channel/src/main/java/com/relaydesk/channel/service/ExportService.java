package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Escalation;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.dto.AuditFilter;
import com.relaydesk.channel.dto.AuditRow;
import com.relaydesk.channel.dto.EscalationFilter;
import com.relaydesk.channel.dto.EscalationRow;
import com.relaydesk.channel.dto.SlaReportFilter;
import com.relaydesk.channel.dto.SlaStats;
import com.relaydesk.channel.dto.TeamSlaRow;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.dto.TicketSummaryRow;
import com.relaydesk.channel.repo.EscalationRepository;
import com.relaydesk.channel.repo.TicketMessageRepository;
import com.relaydesk.channel.repo.TicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CSV exports. Every export is a privileged action and writes ONE audit event naming the
 * resource class and the row count. Internal notes and escalation reasons never appear in
 * exports (ticket exports are built from summary rows only).
 */
@Service
public class ExportService {

    public record CsvFile(String name, String content) {
    }

    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 500;

    private final TicketQueryService tickets;
    private final SlaService sla;
    private final EscalationService escalations;
    private final AuditService audit;
    private final TicketRepository ticketRepo;
    private final EscalationRepository escalationRepo;
    private final TicketMessageRepository messages;

    public ExportService(TicketQueryService tickets, SlaService sla,
                         EscalationService escalations, AuditService audit,
                         TicketRepository ticketRepo, EscalationRepository escalationRepo,
                         TicketMessageRepository messages) {
        this.tickets = tickets;
        this.sla = sla;
        this.escalations = escalations;
        this.audit = audit;
        this.ticketRepo = ticketRepo;
        this.escalationRepo = escalationRepo;
        this.messages = messages;
    }

    public CsvFile tickets(RequestPrincipal p, TicketFilter f) {
        List<TicketSummaryRow> rows = allTickets(p, f);
        Set<UUID> ids = rows.stream().map(TicketSummaryRow::id).collect(Collectors.toSet());
        Map<UUID, Instant> resolvedAt = ids.isEmpty() ? Map.of()
                : ticketRepo.findAllById(ids).stream()
                        .filter(t -> t.getResolvedAt() != null)
                        .collect(Collectors.toMap(Ticket::getId, Ticket::getResolvedAt));
        List<String> header = List.of("ref", "subject", "org_name", "status", "priority",
                "sla_status", "category", "team", "queue", "assignee", "requester_email",
                "created_at", "updated_at", "resolved_at", "sla_resolution_deadline");
        List<List<String>> body = rows.stream()
                .map(r -> List.of(
                        r.ref(), r.subject(), r.orgName(), str(r.status()), str(r.priority()),
                        str(r.slaStatus()), r.categoryName(), r.teamName(), r.queueName(),
                        r.assigneeName(), r.requesterEmail(),
                        ts(r.createdAt()), ts(r.updatedAt()), ts(resolvedAt.get(r.id())),
                        ts(r.slaResolutionDeadline())))
                .collect(Collectors.toList());
        audit.record(p, "export.tickets", "export", null, fileName("tickets"), null,
                "count=" + rows.size());
        return new CsvFile(fileName("tickets"), csv(header, body));
    }

    public CsvFile sla(RequestPrincipal p, SlaReportFilter f) {
        requireLeadOrAdmin(p);
        SlaStats stats = sla.stats(f);
        List<TicketSummaryRow> breached = allTickets(p, new TicketFilter(null, null, null,
                f.teamId(), null, null, true, f.from(), f.to(), null, "created"));
        StringBuilder sb = new StringBuilder();
        sb.append(CsvSupport.encode(List.of("metric", "value"))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("total_active", String.valueOf(stats.totalActive())))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("breached", String.valueOf(stats.breached())))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("at_risk", String.valueOf(stats.atRisk())))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("on_track", String.valueOf(stats.onTrack())))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("avg_response_hours", String.valueOf(stats.avgResponseHours())))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("avg_resolution_hours", String.valueOf(stats.avgResolutionHours())))).append("\r\n");
        sb.append(CsvSupport.encode(List.of("breach_rate_percent", String.valueOf(stats.breachRatePercent())))).append("\r\n");
        sb.append("\r\n");
        sb.append(CsvSupport.encode(List.of("team", "active", "breached", "avg_response_hours"))).append("\r\n");
        for (TeamSlaRow t : stats.byTeam()) {
            sb.append(CsvSupport.encode(List.of(t.teamName(), String.valueOf(t.active()),
                    String.valueOf(t.breached()), String.valueOf(t.avgResponseHours())))).append("\r\n");
        }
        sb.append("\r\n");
        sb.append(CsvSupport.encode(List.of("ticket_ref", "ticket_subject", "org", "team", "priority",
                "created_at", "resolution_deadline", "sla_status"))).append("\r\n");
        for (TicketSummaryRow r : breached) {
            sb.append(CsvSupport.encode(List.of(r.ref(), r.subject(), nullToEmpty(r.orgName()),
                    nullToEmpty(r.teamName()), str(r.priority()), ts(r.createdAt()),
                    ts(r.slaResolutionDeadline()), str(r.slaStatus())))).append("\r\n");
        }
        audit.record(p, "export.sla", "export", null, fileName("sla"), null,
                "count=" + breached.size());
        return new CsvFile(fileName("sla"), sb.toString());
    }

    public CsvFile escalations(RequestPrincipal p, EscalationFilter f) {
        requireLeadOrAdmin(p);
        List<EscalationRow> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            Page<EscalationRow> chunk = escalations.query(p, f, PageRequest.of(page, PAGE_SIZE));
            rows.addAll(chunk.getContent());
            if (chunk.isLast()) {
                break;
            }
            page++;
        }
        Set<UUID> escalationIds = rows.stream().map(EscalationRow::id).collect(Collectors.toSet());
        Map<UUID, Escalation> entities = escalationIds.isEmpty() ? Map.of()
                : escalationRepo.findAllById(escalationIds).stream()
                        .collect(Collectors.toMap(Escalation::getId, e -> e));
        List<String> header = List.of("ticket_ref", "ticket_subject", "team", "requester",
                "assignee", "status", "sla_exception", "created_at", "updated_at", "resolved_at",
                "resolution");
        List<List<String>> body = rows.stream()
                .map(r -> {
                    Escalation e = entities.get(r.id());
                    return List.of(r.ticketRef(), r.ticketSubject(), r.teamName(), r.requesterName(),
                            r.assigneeName(), str(r.status()), String.valueOf(r.slaException()),
                            ts(r.createdAt()), ts(r.updatedAt()),
                            ts(e == null ? null : e.getResolvedAt()),
                            e == null ? "" : String.valueOf(e.getResolution()));
                })
                .collect(Collectors.toList());
        audit.record(p, "export.escalations", "export", null, fileName("escalations"), null,
                "count=" + rows.size());
        return new CsvFile(fileName("escalations"), csv(header, body));
    }

    public CsvFile audit(RequestPrincipal admin, AuditFilter f) {
        if (admin == null || !admin.isAdmin()) {
            throw new AccessDeniedException("admin only");
        }
        List<AuditRow> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            Page<AuditRow> chunk = audit.query(f, PageRequest.of(page, PAGE_SIZE));
            rows.addAll(chunk.getContent());
            if (chunk.isLast()) {
                break;
            }
            page++;
        }
        List<String> header = List.of("id", "occurred_at", "actor_email", "action",
                "resource_type", "resource_ref", "before", "after", "authz_basis");
        List<List<String>> body = rows.stream()
                .map(r -> List.of(String.valueOf(r.id()), ts(r.occurredAt()),
                        nullToEmpty(r.actorEmail()), r.action(), r.resourceType(),
                        nullToEmpty(r.resourceRef()), nullToEmpty(r.beforeValue()),
                        nullToEmpty(r.afterValue()), nullToEmpty(r.authzBasis())))
                .collect(Collectors.toList());
        audit.record(admin, "export.audit", "export", null, fileName("audit"), null,
                "count=" + rows.size());
        return new CsvFile(fileName("audit"), csv(header, body));
    }

    public CsvFile customerTickets(RequestPrincipal customer) {
        List<TicketSummaryRow> rows = allTickets(customer,
                new TicketFilter(null, null, null, null, null, null, false,
                        null, null, null, "created"));
        Set<UUID> ids = rows.stream().map(TicketSummaryRow::id).collect(Collectors.toSet());
        Map<UUID, Ticket> entities = ids.isEmpty() ? Map.of()
                : ticketRepo.findAllById(ids).stream()
                        .collect(Collectors.toMap(Ticket::getId, t -> t));
        List<String> header = List.of("ref", "subject", "category", "priority", "status",
                "created_at", "updated_at", "resolved_at", "public_reply_count");
        List<List<String>> body = rows.stream()
                .map(r -> {
                    Ticket t = entities.get(r.id());
                    return List.of(r.ref(), r.subject(), r.categoryName(), str(r.priority()),
                            str(r.status()), ts(r.createdAt()), ts(r.updatedAt()),
                            ts(t == null ? null : t.getResolvedAt()),
                            String.valueOf(messages.countByTicketIdAndInternalFalse(r.id())));
                })
                .collect(Collectors.toList());
        audit.record(customer, "export.customer_tickets", "export", null,
                fileName("customer-tickets"), null, "count=" + rows.size());
        return new CsvFile(fileName("customer-tickets"), csv(header, body));
    }

    // ------------------------------------------------------------------ helpers

    private List<TicketSummaryRow> allTickets(RequestPrincipal p, TicketFilter f) {
        List<TicketSummaryRow> out = new ArrayList<>();
        int page = 0;
        while (true) {
            Page<TicketSummaryRow> chunk = tickets.query(p, f, PageRequest.of(page, PAGE_SIZE));
            out.addAll(chunk.getContent());
            if (chunk.isLast()) {
                break;
            }
            page++;
        }
        return out;
    }

    private void requireLeadOrAdmin(RequestPrincipal p) {
        if (p == null || !(p.isAdmin() || p.isLead())) {
            throw new AccessDeniedException("team lead or admin only");
        }
    }

    private String fileName(String kind) {
        return kind + "-" + FILE_TS.format(Instant.now()) + ".csv";
    }

    private String csv(List<String> header, List<List<String>> body) {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvSupport.encode(header)).append("\r\n");
        for (List<String> row : body) {
            sb.append(CsvSupport.encode(row)).append("\r\n");
        }
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String ts(Instant i) {
        return i == null ? "" : i.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
