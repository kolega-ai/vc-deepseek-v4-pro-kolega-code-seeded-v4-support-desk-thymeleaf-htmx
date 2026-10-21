package com.relaydesk.channel.web;

import com.relaydesk.channel.service.NotificationService;
import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.UUID;

/** Notification bell dropdown (htmx fragments) — any authenticated user. */
@Controller
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications/fragment")
    public String list(@CurrentUser RequestPrincipal principal, Model model) {
        return populate(principal, model);
    }

    @PostMapping("/notifications/{id}/read")
    public String markRead(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                           Model model) {
        notificationService.markRead(principal, id);
        return populate(principal, model);
    }

    @PostMapping("/notifications/read-all")
    public String markAllRead(@CurrentUser RequestPrincipal principal, Model model) {
        notificationService.markAllRead(principal);
        return populate(principal, model);
    }

    private String populate(RequestPrincipal principal, Model model) {
        model.addAttribute("page", notificationService.forUser(principal, PageRequest.of(0, 10)));
        model.addAttribute("unread", notificationService.unread(principal));
        return "fragments :: notificationList";
    }
}
