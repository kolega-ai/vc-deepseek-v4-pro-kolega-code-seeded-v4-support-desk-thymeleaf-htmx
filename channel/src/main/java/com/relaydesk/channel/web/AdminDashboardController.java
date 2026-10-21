package com.relaydesk.channel.web;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.service.EmailOutboxService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import com.relaydesk.channel.service.WebhookService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Admin overview: ticket stats, tier health checks, outbox and webhook delivery status. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminDashboardController {

    /** Cheap tier health probe result for the admin dashboard. */
    public record SystemStatus(boolean decisionOk, boolean coreOk, String message) {
    }

    private final TicketQueryService ticketQueryService;
    private final DecisionClient decision;
    private final CoreClient core;
    private final EmailOutboxService outbox;
    private final WebhookService webhooks;

    public AdminDashboardController(TicketQueryService ticketQueryService, DecisionClient decision,
                                    CoreClient core, EmailOutboxService outbox,
                                    WebhookService webhooks) {
        this.ticketQueryService = ticketQueryService;
        this.decision = decision;
        this.core = core;
        this.outbox = outbox;
        this.webhooks = webhooks;
    }

    @GetMapping("/admin")
    public String dashboard(@CurrentUser RequestPrincipal principal, Model model) {
        model.addAttribute("stats", ticketQueryService.dashboard(principal));
        model.addAttribute("system", health(principal));
        model.addAttribute("outbox", outbox.recent(PageRequest.of(0, 8)));
        model.addAttribute("attempts", webhooks.attempts(PageRequest.of(0, 8)));
        return "admin/admin-dashboard";
    }

    private SystemStatus health(RequestPrincipal principal) {
        boolean decisionOk = false;
        boolean coreOk = false;
        String message = null;
        try {
            decision.validate(principal.sessionToken());
            decisionOk = true;
        } catch (Exception e) {
            message = "decision unreachable";
        }
        try {
            core.listOrgs(principal, null, 0, 5);
            coreOk = true;
        } catch (Exception e) {
            message = message == null ? "customer core unreachable" : message + "; customer core unreachable";
        }
        return new SystemStatus(decisionOk, coreOk, message);
    }
}
