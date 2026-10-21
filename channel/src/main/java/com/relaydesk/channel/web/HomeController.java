package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.service.KbService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Landing page and the topbar ticket lookup. */
@Controller
public class HomeController {

    private static final Pattern TICKET_REF = Pattern.compile("^TCK-\\d+$", Pattern.CASE_INSENSITIVE);

    private final KbService kbService;
    private final TicketQueryService ticketQueryService;
    private final TicketRepository tickets;

    public HomeController(KbService kbService, TicketQueryService ticketQueryService,
                          TicketRepository tickets) {
        this.kbService = kbService;
        this.ticketQueryService = ticketQueryService;
        this.tickets = tickets;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("kb", kbService.publicSearch(null, PageRequest.of(0, 6)));
        return "public/index";
    }

    /** Topbar ticket lookup: a ref goes straight to the ticket, anything else searches the queue. */
    @GetMapping("/search/ticket")
    @PreAuthorize("hasAnyRole('ROLE_SUPPORT_AGENT','ROLE_TEAM_LEAD','ROLE_SUPPORT_ADMIN')")
    public String search(@RequestParam(name = "q", required = false) String q,
                         @CurrentUser RequestPrincipal principal) {
        String query = q == null ? "" : q.trim();
        String encoded = UriUtils.encodeQueryParam(query, StandardCharsets.UTF_8);
        if (TICKET_REF.matcher(query).matches()) {
            Optional<Ticket> found = tickets.findByRefIgnoreCase(query);
            if (found.isPresent()) {
                UUID id = found.get().getId();
                try {
                    ticketQueryService.getTicketDetail(principal, id); // enforces visibility
                    return "redirect:/desk/tickets/" + id;
                } catch (org.springframework.security.access.AccessDeniedException outOfScope) {
                    // The ticket exists but is outside this user's scope: fall back to a
                    // queue search instead of confirming or denying its existence.
                    return "redirect:/desk/queue?q=" + encoded;
                }
            }
            return "redirect:/desk/queue?q=" + encoded;
        }
        return "redirect:/desk/queue?q=" + encoded;
    }
}
