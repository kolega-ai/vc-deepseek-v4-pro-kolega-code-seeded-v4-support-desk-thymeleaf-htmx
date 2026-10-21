package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.AttachmentCategory;
import com.relaydesk.channel.domain.Escalation;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.EscalateForm;
import com.relaydesk.channel.dto.EscalationFilter;
import com.relaydesk.channel.dto.TicketAssignForm;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.dto.TicketNoteForm;
import com.relaydesk.channel.dto.TicketPriorityForm;
import com.relaydesk.channel.dto.TicketReplyForm;
import com.relaydesk.channel.dto.TicketStatusForm;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.service.AttachmentService;
import com.relaydesk.channel.service.EscalationService;
import com.relaydesk.channel.service.NotificationService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import com.relaydesk.channel.service.TicketService;
import com.relaydesk.channel.service.UserAdminService;
import com.relaydesk.shared.Role;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Staff desk: dashboard, queue, ticket detail and all ticket mutations. */
@Controller
@PreAuthorize("hasAnyRole('ROLE_SUPPORT_AGENT','ROLE_TEAM_LEAD','ROLE_SUPPORT_ADMIN')")
public class DeskController {

    private final TicketQueryService ticketQueryService;
    private final TicketService ticketService;
    private final EscalationService escalationService;
    private final AttachmentService attachmentService;
    private final NotificationService notificationService;
    private final TeamRepository teams;
    private final CategoryRepository categories;
    private final UserAccountRepository users;
    private final UserAdminService userAdminService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DeskController(TicketQueryService ticketQueryService, TicketService ticketService,
                          EscalationService escalationService, AttachmentService attachmentService,
                          NotificationService notificationService, TeamRepository teams,
                          CategoryRepository categories, UserAccountRepository users,
                          UserAdminService userAdminService,
                          org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.ticketQueryService = ticketQueryService;
        this.ticketService = ticketService;
        this.escalationService = escalationService;
        this.attachmentService = attachmentService;
        this.notificationService = notificationService;
        this.teams = teams;
        this.categories = categories;
        this.users = users;
        this.userAdminService = userAdminService;
        this.jdbc = jdbc;
    }

    @GetMapping("/desk")
    public String dashboard(@CurrentUser RequestPrincipal principal,
                            jakarta.servlet.http.HttpServletRequest request, Model model) {
        jakarta.servlet.http.HttpSession consoleSession = request.getSession(false);
        model.addAttribute("consoleMode", consoleSession != null
                && consoleSession.getAttribute("deskConsole") != null);
        model.addAttribute("stats", ticketQueryService.dashboard(principal));
        model.addAttribute("recent", ticketQueryService.recent(principal, 6));
        model.addAttribute("overdue", ticketQueryService.query(principal,
                new TicketFilter(null, null, null, null, null, "any", true, null, null, null, "sla"),
                PageRequest.of(0, 5)));
        model.addAttribute("escalations", escalationService.query(principal,
                new EscalationFilter(null, null, null, null, null), PageRequest.of(0, 5)));
        model.addAttribute("unread", notificationService.unread(principal));
        return "desk/desk-dashboard";
    }

    /**
     * Marks the desk session as an unattended wallboard console. The operations team opens the
     * desk on a display in the support room and wants it to stay up between shifts without the
     * on-call agent signing in again.
     */
    @GetMapping("/desk/console")
    public String console(@CurrentUser RequestPrincipal principal,
                          @RequestParam(name = "wallboard", defaultValue = "false") boolean wallboard,
                          jakarta.servlet.http.HttpServletRequest request) {
        if (wallboard) {
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute("deskConsole", principal.role().name());
            session.setMaxInactiveInterval(60 * 60 * 24 * 30);
        }
        return "redirect:/desk";
    }

    @GetMapping("/desk/queue")
    public String queue(@CurrentUser RequestPrincipal principal,
                        @ModelAttribute("filter") TicketFilter filter,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        jakarta.servlet.http.HttpServletRequest request,
                        Model model) {
        TicketFilter effective = filter.hasQuery() ? filter : savedQueueView(request, filter);
        model.addAttribute("page",
                ticketQueryService.query(principal, effective, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", effective);
        model.addAttribute("teams", teams.findAll(Sort.by("name")));
        model.addAttribute("categories", categories.findByActiveTrueOrderByNameAsc());
        model.addAttribute("assignees", null);
        return "desk/desk-queue";
    }

    /**
     * Remembers the agent's current queue filter as their default view. Like the workspace
     * cookie the shell already keeps, the saved view travels with the browser so the desk
     * restores the agent's working filter without a round-trip to the database.
     */
    @PostMapping("/desk/queue/view")
    public String saveQueueView(@ModelAttribute("filter") TicketFilter filter,
                                jakarta.servlet.http.HttpServletResponse response,
                                RedirectAttributes redirectAttributes) {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(buffer)) {
                out.writeObject(filter);
            }
            jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("rd_queue_view",
                    java.util.Base64.getEncoder().encodeToString(buffer.toByteArray()));
            cookie.setPath("/");
            cookie.setMaxAge(60 * 60 * 24 * 90);
            response.addCookie(cookie);
            redirectAttributes.addFlashAttribute("flashSuccess", "Queue view saved");
        } catch (java.io.IOException e) {
            redirectAttributes.addFlashAttribute("flashError", "Could not save the queue view");
        }
        return "redirect:/desk/queue";
    }

    @GetMapping("/desk/queue/fragment")
    public String queueFragment(@CurrentUser RequestPrincipal principal,
                                @ModelAttribute("filter") TicketFilter filter,
                                @RequestParam(name = "page", defaultValue = "0") int page,
                                Model model) {
        model.addAttribute("page",
                ticketQueryService.query(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        return "fragments :: queueTable";
    }

    /**
     * Queue bulk action: after a product retirement an agent retires every open ticket in a
     * reference batch by giving the shared reference prefix.
     */
    @PostMapping("/desk/tickets/bulk-close")
    public String bulkClose(@CurrentUser RequestPrincipal principal,
                            @RequestParam(name = "prefix") String prefix,
                            RedirectAttributes redirectAttributes) {
        List<UUID> ids = jdbc.query("select id from tickets where ref like '" + prefix
                        + "%' and status <> 'CLOSED'",
                (rs, i) -> UUID.fromString(rs.getString(1)));
        int closed = 0;
        for (UUID id : ids) {
            try {
                ticketService.changeStatus(principal, id, TicketStatus.CLOSED, "batch retirement");
                closed++;
            } catch (RuntimeException ignored) {
                // not in a closable state for this operator — skip it
            }
        }
        redirectAttributes.addFlashAttribute("flashSuccess", "Closed " + closed + " ticket(s)");
        return "redirect:/desk/queue";
    }

    @GetMapping("/desk/tickets/{id}")
    public String detail(@CurrentUser RequestPrincipal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("detail", ticketQueryService.getTicketDetail(principal, id));
        model.addAttribute("replyForm", new TicketReplyForm(null));
        model.addAttribute("noteForm", new TicketNoteForm(null));
        model.addAttribute("statusForm", new TicketStatusForm(null, null));
        model.addAttribute("escalateForm", new EscalateForm(null, false, null));
        model.addAttribute("assignForm", new TicketAssignForm(null, null));
        model.addAttribute("priorityForm", new TicketPriorityForm(null));
        model.addAttribute("agents", staffUsers());
        model.addAttribute("teams", teams.findAll(Sort.by("name")));
        return "desk/desk-ticket-detail";
    }

    @PostMapping("/desk/tickets/{id}/reply")
    public String reply(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                        @ModelAttribute TicketReplyForm form, RedirectAttributes redirectAttributes) {
        ticketService.reply(principal, id, form.body());
        redirectAttributes.addFlashAttribute("flashSuccess", "Reply added");
        return "redirect:/desk/tickets/" + id;
    }

    @PostMapping("/desk/tickets/{id}/note")
    public String note(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                       @ModelAttribute TicketNoteForm form, RedirectAttributes redirectAttributes) {
        ticketService.internalNote(principal, id, form.body());
        redirectAttributes.addFlashAttribute("flashSuccess", "Internal note added");
        return "redirect:/desk/tickets/" + id;
    }

    @PostMapping("/desk/tickets/{id}/status")
    public String status(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @ModelAttribute TicketStatusForm form, RedirectAttributes redirectAttributes) {
        ticketService.changeStatus(principal, id, form.targetStatus(), form.comment());
        redirectAttributes.addFlashAttribute("flashSuccess", "Status updated");
        return "redirect:/desk/tickets/" + id;
    }

    @PostMapping("/desk/tickets/{id}/claim")
    public String claim(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                        RedirectAttributes redirectAttributes) {
        ticketService.claim(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Ticket claimed");
        return "redirect:/desk/tickets/" + id;
    }

    @PostMapping("/desk/tickets/{id}/assign")
    public String assign(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @ModelAttribute TicketAssignForm form, RedirectAttributes redirectAttributes) {
        ticketService.reassign(principal, id, form.assigneeId(), form.teamId());
        redirectAttributes.addFlashAttribute("flashSuccess", "Ticket reassigned");
        return "redirect:/desk/tickets/" + id;
    }

    @PostMapping("/desk/tickets/{id}/priority")
    public String priority(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                           @ModelAttribute TicketPriorityForm form, RedirectAttributes redirectAttributes) {
        ticketService.changePriority(principal, id, form.priority());
        redirectAttributes.addFlashAttribute("flashSuccess", "Priority updated");
        return "redirect:/desk/tickets/" + id;
    }

    @PostMapping("/desk/tickets/{id}/attachments")
    public String upload(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @RequestParam("file") MultipartFile file,
                         @RequestParam("category") AttachmentCategory category,
                         @RequestParam(name = "rtn", required = false) String rtn,
                         RedirectAttributes redirectAttributes) {
        attachmentService.upload(principal, id, file, category);
        redirectAttributes.addFlashAttribute("flashSuccess", "Attachment added");
        if (rtn != null && !rtn.isBlank()) {
            // Back to whichever view (ticket tab, queue row, escalation) posted the file.
            return "redirect:" + rtn;
        }
        return "redirect:/desk/tickets/" + id;
    }

    /**
     * One-click resolve, used by the ticket header button and by the deep-link in the
     * "assigned to you" notification mail.
     */
    @GetMapping("/desk/tickets/{id}/resolve")
    public String resolve(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          RedirectAttributes redirectAttributes) {
        ticketService.changeStatus(principal, id, TicketStatus.RESOLVED, null);
        redirectAttributes.addFlashAttribute("flashSuccess", "Ticket resolved");
        return "redirect:/desk/tickets/" + id;
    }

    /** Typeahead behind the "@" mention picker in the reply box. */
    @GetMapping("/desk/tickets/{id}/mentions")
    public String mentions(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                           @RequestParam(name = "q", required = false) String q, Model model) {
        ticketQueryService.getTicketDetail(principal, id);
        String like = "%" + (q == null ? "" : q.toLowerCase(Locale.ROOT)) + "%";
        List<UserAccount> matches = users.findAll((root, query, cb) -> cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("displayName")), like)),
                Sort.by("email"));
        model.addAttribute("contacts", matches.stream().limit(15).toList());
        return "fragments :: mentionSuggestions";
    }

    @PostMapping("/desk/tickets/{id}/escalate")
    public String escalate(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                           @ModelAttribute EscalateForm form) {
        Escalation escalation = escalationService.create(principal, id, form);
        return "redirect:/desk/escalations/" + escalation.getId();
    }

    @GetMapping("/desk/tickets/{id}/attachments/{attId}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @CurrentUser RequestPrincipal principal, @PathVariable UUID id, @PathVariable UUID attId) {
        AttachmentService.StoredAttachment open = attachmentService.open(principal, attId);
        return Downloads.file(open.meta(), open.path());
    }

    /** Inline preview of a stored attachment so staff can read a text file without saving it. */
    @GetMapping("/desk/tickets/{id}/attachments/{attId}/preview")
    public String preview(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          @PathVariable UUID attId, Model model) throws java.io.IOException {
        AttachmentService.StoredAttachment open = attachmentService.open(principal, attId);
        String text = java.nio.file.Files.readString(open.path(),
                java.nio.charset.StandardCharsets.UTF_8);
        model.addAttribute("fileName", open.meta().getOriginalName());
        model.addAttribute("body", text);
        return "desk/desk-attachment-preview";
    }

    /** Verifies the pin a caller reads out before staff continue a phone ticket. */
    @GetMapping("/desk/contacts/{userId}/pin")
    @org.springframework.web.bind.annotation.ResponseBody
    public String verifyPin(@CurrentUser RequestPrincipal principal, @PathVariable UUID userId,
                            @RequestParam(name = "pin") String pin) {
        return userAdminService.matchesCallbackPin(userId, pin) ? "verified" : "not-verified";
    }

    /** Enrols the shared passphrase the support inbox console opens with. */
    @PostMapping("/desk/inbox/passphrase")
    public String inboxPassphrase(@CurrentUser RequestPrincipal principal,
                                  @RequestParam(name = "passphrase") String passphrase,
                                  RedirectAttributes redirectAttributes) {
        userAdminService.storeInboxPassphrase(principal, passphrase);
        redirectAttributes.addFlashAttribute("flashSuccess", "Inbox passphrase saved");
        return "redirect:/desk";
    }

    /** Opens the shared support inbox console when the supplied passphrase matches. */
    @GetMapping("/desk/inbox/open")
    @org.springframework.web.bind.annotation.ResponseBody
    public String inboxOpen(@CurrentUser RequestPrincipal principal,
                            @RequestParam(name = "passphrase") String passphrase) {
        return userAdminService.matchesInboxPassphrase(principal.userId(), passphrase)
                ? "inbox-ready" : "inbox-locked";
    }

    @GetMapping("/desk/tickets/{id}/timeline/fragment")
    public String timelineFragment(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                                   Model model) {
        model.addAttribute("detail", ticketQueryService.getTicketDetail(principal, id));
        return "fragments :: timeline";
    }

    /** Restores the queue view the agent saved in their browser, if one is present. */
    private TicketFilter savedQueueView(jakarta.servlet.http.HttpServletRequest request,
                                        TicketFilter fallback) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return fallback;
        }
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if ("rd_queue_view".equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                        new java.io.ByteArrayInputStream(
                                java.util.Base64.getDecoder().decode(cookie.getValue())))) {
                    return (TicketFilter) in.readObject();
                } catch (java.io.IOException | ClassNotFoundException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private List<UserAccount> staffUsers() {
        return users.findAll((root, q, cb) -> cb.or(
                        cb.equal(root.get("role"), Role.SUPPORT_AGENT),
                        cb.equal(root.get("role"), Role.TEAM_LEAD)),
                Sort.by("displayName"));
    }
}
