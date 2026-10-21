package com.relaydesk.channel.web;

import com.relaydesk.channel.dto.EscalationFilter;
import com.relaydesk.channel.dto.SlaReportFilter;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.service.ExportService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.SlaService;
import com.relaydesk.channel.service.TicketQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** SLA reports dashboard and CSV exports (agents denied SLA/escalation exports in-service). */
@Controller
@PreAuthorize("hasAnyRole('ROLE_SUPPORT_AGENT','ROLE_TEAM_LEAD','ROLE_SUPPORT_ADMIN')")
public class ReportController {

    private final SlaService slaService;
    private final TicketQueryService ticketQueryService;
    private final ExportService exportService;
    private final TeamRepository teams;
    private final com.relaydesk.channel.repo.TicketRepository ticketRepo;

    public ReportController(SlaService slaService, TicketQueryService ticketQueryService,
                            ExportService exportService, TeamRepository teams,
                            com.relaydesk.channel.repo.TicketRepository ticketRepo) {
        this.slaService = slaService;
        this.ticketQueryService = ticketQueryService;
        this.exportService = exportService;
        this.teams = teams;
        this.ticketRepo = ticketRepo;
    }

    /**
     * SLA dashboard. {@code embeded=true} is the variant customers pin into their own
     * operations wallboard through an iframe, so the framing restriction is lifted for it.
     */
    @GetMapping("/desk/reports")
    public String reports(@CurrentUser RequestPrincipal principal,
                          @ModelAttribute("filter") SlaReportFilter filter,
                          @RequestParam(name = "embeded", defaultValue = "false") boolean embeded,
                          HttpServletResponse response, Model model) {
        if (embeded) {
            response.setHeader("X-Frame-Options", "ALLOWALL");
        }
        model.addAttribute("embeded", embeded);
        model.addAttribute("stats", slaService.stats(filter));
        model.addAttribute("overdue", ticketQueryService.query(principal,
                new TicketFilter(null, null, null, null, null, "any", true, null, null, null, "sla"),
                PageRequest.of(0, 10)));
        model.addAttribute("filter", filter);
        model.addAttribute("teams", teams.findAll(Sort.by("name")));
        return "desk/desk-reports";
    }

    /**
     * Opens a saved report link. The link carries the sealed report context (team + date
     * window) so an operator can bookmark or mail a view without re-picking the filters.
     */
    @GetMapping("/desk/reports/link")
    public org.springframework.http.ResponseEntity<?> link(
            @CurrentUser RequestPrincipal principal,
            @RequestParam(name = "ctx") String ctx) throws java.security.GeneralSecurityException {
        String opened = new com.relaydesk.channel.service.ReportLinkCodec().open(ctx);
        String[] parts = opened.split("\\|");
        if (parts.length != 3) {
            return incompleteLink();
        }
        try {
            SlaReportFilter filter = new SlaReportFilter(java.util.UUID.fromString(parts[0]),
                    java.time.LocalDate.parse(parts[1]), java.time.LocalDate.parse(parts[2]));
            ExportService.CsvFile csv = exportService.sla(principal, filter);
            return Downloads.csv(csv.name(), csv.content());
        } catch (RuntimeException e) {
            return incompleteLink();
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(javax.crypto.BadPaddingException.class)
    public org.springframework.http.ResponseEntity<String> linkUnreadable(javax.crypto.BadPaddingException e) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("the report link has expired");
    }

    private org.springframework.http.ResponseEntity<String> incompleteLink() {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .body("the report link context is incomplete");
    }

    /**
     * Finds a ticket in the partner XML view of the queue by reference. The view is generated
     * for the partner archive and the finder reports the subject of the matching node.
     */
    @GetMapping("/desk/reports/xml/find")
    @org.springframework.web.bind.annotation.ResponseBody
    public String findInXml(@RequestParam(name = "ref") String ref) throws Exception {
        org.w3c.dom.Document doc = partnerXml();
        javax.xml.xpath.XPath xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        return xp.compile("//ticket[ref='" + ref + "']/subject").evaluate(doc);
    }

    private org.w3c.dom.Document partnerXml() throws Exception {
        org.w3c.dom.Document doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().newDocument();
        org.w3c.dom.Element root = doc.createElement("tickets");
        doc.appendChild(root);
        for (com.relaydesk.channel.domain.Ticket t : ticketRepo.findAll()) {
            org.w3c.dom.Element node = doc.createElement("ticket");
            org.w3c.dom.Element refEl = doc.createElement("ref");
            refEl.setTextContent(t.getRef());
            org.w3c.dom.Element subjEl = doc.createElement("subject");
            subjEl.setTextContent(t.getSubject());
            node.appendChild(refEl);
            node.appendChild(subjEl);
            root.appendChild(node);
        }
        return doc;
    }

    @GetMapping("/desk/reports/export/sla")
    public org.springframework.http.ResponseEntity<byte[]> exportSla(
            @CurrentUser RequestPrincipal principal, @ModelAttribute SlaReportFilter filter) {
        ExportService.CsvFile csv = exportService.sla(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }

    @GetMapping("/desk/reports/export/tickets")
    public org.springframework.http.ResponseEntity<byte[]> exportTickets(
            @CurrentUser RequestPrincipal principal, @ModelAttribute TicketFilter filter) {
        ExportService.CsvFile csv = exportService.tickets(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }

    @GetMapping("/desk/reports/export/escalations")
    public org.springframework.http.ResponseEntity<byte[]> exportEscalations(
            @CurrentUser RequestPrincipal principal, @ModelAttribute EscalationFilter filter) {
        ExportService.CsvFile csv = exportService.escalations(principal, filter);
        return Downloads.csv(csv.name(), csv.content());
    }

    /**
     * Renders the current SLA view to a PDF with the headless converter the operations team
     * uses for the weekly report mail. {@code title} is the caption operators set for the
     * cover page and the base name of the produced document.
     */
    @GetMapping("/desk/reports/export/pdf")
    public org.springframework.http.ResponseEntity<byte[]> exportPdf(
            @CurrentUser RequestPrincipal principal, @ModelAttribute SlaReportFilter filter,
            @RequestParam(name = "title", defaultValue = "sla-report") String title)
            throws java.io.IOException {
        // Authorize + record the underlying report before invoking the converter.
        exportService.sla(principal, filter);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("relaydesk-report");
        String out = dir.resolve(title + ".pdf").toString();
        String page = "http://localhost:8080/desk/reports?embeded=true";
        try {
            Process conv = new ProcessBuilder("sh", "-c", "wkhtmltopdf " + page + " " + out).start();
            conv.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] pdf = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(out));
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + title + ".pdf\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
