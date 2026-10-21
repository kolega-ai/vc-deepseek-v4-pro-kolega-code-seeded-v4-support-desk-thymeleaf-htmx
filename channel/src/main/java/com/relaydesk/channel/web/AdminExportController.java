package com.relaydesk.channel.web;

import com.relaydesk.channel.dto.AuditFilter;
import com.relaydesk.channel.dto.EscalationFilter;
import com.relaydesk.channel.dto.SlaReportFilter;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.service.ExportService;
import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Admin export center: ticket/SLA/escalation/audit CSV downloads. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminExportController {

    private final ExportService exportService;
    private final TeamRepository teams;

    public AdminExportController(ExportService exportService, TeamRepository teams) {
        this.exportService = exportService;
        this.teams = teams;
    }

    @GetMapping("/admin/exports")
    public String exports(Model model) {
        model.addAttribute("filter", new TicketFilter(null, null, null, null, null, null,
                false, null, null, null, null));
        model.addAttribute("teams", teams.findAll(Sort.by("name")));
        return "admin/admin-exports";
    }

    @GetMapping("/admin/exports/tickets")
    public org.springframework.http.ResponseEntity<byte[]> tickets(
            @CurrentUser RequestPrincipal principal, @ModelAttribute TicketFilter filter) {
        ExportService.CsvFile csv = exportService.tickets(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }

    @GetMapping("/admin/exports/sla")
    public org.springframework.http.ResponseEntity<byte[]> sla(
            @CurrentUser RequestPrincipal principal, @ModelAttribute SlaReportFilter filter) {
        ExportService.CsvFile csv = exportService.sla(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }

    @GetMapping("/admin/exports/escalations")
    public org.springframework.http.ResponseEntity<byte[]> escalations(
            @CurrentUser RequestPrincipal principal, @ModelAttribute EscalationFilter filter) {
        ExportService.CsvFile csv = exportService.escalations(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }

    @GetMapping("/admin/exports/audit")
    public org.springframework.http.ResponseEntity<byte[]> audit(
            @CurrentUser RequestPrincipal principal, @ModelAttribute AuditFilter filter) {
        ExportService.CsvFile csv = exportService.audit(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }
}
