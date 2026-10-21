package com.relaydesk.channel.web;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.domain.AttachmentCategory;
import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.dto.ProfileForm;
import com.relaydesk.channel.dto.TicketCreateForm;
import com.relaydesk.channel.dto.TicketFilter;
import com.relaydesk.channel.dto.TicketReplyForm;
import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.service.AttachmentService;
import com.relaydesk.channel.service.ExportService;
import com.relaydesk.channel.service.KbService;
import com.relaydesk.channel.service.NotificationService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.TicketQueryService;
import com.relaydesk.channel.service.TicketService;
import com.relaydesk.channel.service.UserAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
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
import java.util.UUID;

/** Customer portal: dashboard, tickets (create/reply/attachments/export), profile. */
@Controller
@PreAuthorize("hasRole('ROLE_CUSTOMER_USER')")
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);

    private final TicketQueryService ticketQueryService;
    private final TicketService ticketService;
    private final AttachmentService attachmentService;
    private final NotificationService notificationService;
    private final KbService kbService;
    private final ExportService exportService;
    private final UserAdminService userAdminService;
    private final CategoryRepository categories;
    private final CoreClient core;

    public PortalController(TicketQueryService ticketQueryService, TicketService ticketService,
                            AttachmentService attachmentService, NotificationService notificationService,
                            KbService kbService, ExportService exportService,
                            UserAdminService userAdminService, CategoryRepository categories,
                            CoreClient core) {
        this.ticketQueryService = ticketQueryService;
        this.ticketService = ticketService;
        this.attachmentService = attachmentService;
        this.notificationService = notificationService;
        this.kbService = kbService;
        this.exportService = exportService;
        this.userAdminService = userAdminService;
        this.categories = categories;
        this.core = core;
    }

    @GetMapping("/portal")
    public String dashboard(@CurrentUser RequestPrincipal principal, Model model) {
        model.addAttribute("stats", ticketQueryService.dashboard(principal));
        model.addAttribute("recent", ticketQueryService.recent(principal, 6));
        model.addAttribute("unread", notificationService.unread(principal));
        model.addAttribute("kb", kbService.publicSearch(null, PageRequest.of(0, 4)));
        return "portal/portal-dashboard";
    }

    @GetMapping("/portal/tickets")
    public String tickets(@CurrentUser RequestPrincipal principal,
                          @ModelAttribute("filter") TicketFilter filter,
                          @RequestParam(name = "page", defaultValue = "0") int page,
                          Model model) {
        model.addAttribute("page",
                ticketQueryService.query(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        model.addAttribute("categories", categories.findByActiveTrueOrderByNameAsc());
        return "portal/portal-tickets";
    }

    @GetMapping("/portal/tickets/fragment")
    public String ticketsFragment(@CurrentUser RequestPrincipal principal,
                                  @ModelAttribute("filter") TicketFilter filter,
                                  @RequestParam(name = "page", defaultValue = "0") int page,
                                  Model model) {
        model.addAttribute("page",
                ticketQueryService.query(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        return "fragments :: portalTicketRows";
    }

    @GetMapping("/portal/tickets/new")
    public String newTicket(@CurrentUser RequestPrincipal principal, Model model) {
        model.addAttribute("form", new TicketCreateForm(null, null, null, null, null));
        model.addAttribute("categories", categories.findByActiveTrueOrderByNameAsc());
        UUID orgId = principal.orgIds().stream().findFirst().orElse(null);
        try {
            model.addAttribute("contacts", orgId == null ? List.of()
                    : core.org(principal, orgId).contacts());
        } catch (CoreClientException e) {
            log.warn("core unavailable while loading contacts for new-ticket form", e);
            model.addAttribute("contacts", List.of());
        }
        model.addAttribute("slaHint", null);
        return "portal/portal-ticket-new";
    }

    @PostMapping("/portal/tickets")
    public String create(@CurrentUser RequestPrincipal principal,
                         @ModelAttribute TicketCreateForm form,
                         @RequestParam(name = "file", required = false) MultipartFile file,
                         RedirectAttributes redirectAttributes) {
        Ticket ticket;
        try {
            ticket = ticketService.create(principal, form);
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/portal/tickets/new";
        }
        if (file != null && !file.isEmpty()) {
            attachmentService.upload(principal, ticket.getId(), file,
                    AttachmentCategory.CUSTOMER_ATTACHMENT);
        }
        redirectAttributes.addFlashAttribute("flashSuccess", "Ticket " + ticket.getRef() + " created");
        return "redirect:/portal/tickets/" + ticket.getId();
    }

    @GetMapping("/portal/tickets/{id}")
    public String detail(@CurrentUser RequestPrincipal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("detail", ticketQueryService.getTicketDetail(principal, id));
        return "portal/portal-ticket-detail";
    }

    @PostMapping("/portal/tickets/{id}/reply")
    public String reply(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                        @ModelAttribute TicketReplyForm form, RedirectAttributes redirectAttributes) {
        ticketService.reply(principal, id, form.body());
        redirectAttributes.addFlashAttribute("flashSuccess", "Reply added");
        return "redirect:/portal/tickets/" + id;
    }

    @PostMapping("/portal/tickets/{id}/attachments")
    public String upload(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @RequestParam("file") MultipartFile file,
                         RedirectAttributes redirectAttributes) {
        attachmentService.upload(principal, id, file, AttachmentCategory.CUSTOMER_ATTACHMENT);
        redirectAttributes.addFlashAttribute("flashSuccess", "Attachment added");
        return "redirect:/portal/tickets/" + id;
    }

    @GetMapping("/portal/tickets/{id}/attachments/{attId}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @CurrentUser RequestPrincipal principal, @PathVariable UUID id, @PathVariable UUID attId) {
        AttachmentService.StoredAttachment open = attachmentService.open(principal, attId);
        return Downloads.file(open.meta(), open.path());
    }

    @GetMapping("/portal/tickets/export")
    public org.springframework.http.ResponseEntity<byte[]> export(@CurrentUser RequestPrincipal principal) {
        ExportService.CsvFile csv = exportService.customerTickets(principal);
        return Downloads.csv(csv.name(), csv.content());
    }

    @GetMapping("/portal/profile")
    public String profile(@CurrentUser RequestPrincipal principal, Model model) {
        model.addAttribute("form", new ProfileForm(principal.displayName(), null, null));
        return "portal/portal-profile";
    }

    @PostMapping("/portal/profile")
    public String profileUpdate(@CurrentUser RequestPrincipal principal,
                                @ModelAttribute ProfileForm form,
                                RedirectAttributes redirectAttributes) {
        userAdminService.updateProfile(principal, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Profile updated");
        return "redirect:/portal/profile";
    }

    /** Enrols the reusable pin the phone support team asks a caller to read out. */
    @GetMapping("/portal/profile/callback-pin")
    public String callbackPin(@CurrentUser RequestPrincipal principal,
                              @RequestParam(name = "pin") String pin,
                              RedirectAttributes redirectAttributes) {
        userAdminService.storeCallbackPin(principal, principal.userId(), pin);
        redirectAttributes.addFlashAttribute("flashSuccess", "Call-back pin saved");
        return "redirect:/portal/profile";
    }
}
