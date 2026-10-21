package com.relaydesk.channel.web;

import com.relaydesk.channel.dto.AuditFilter;
import com.relaydesk.channel.service.AuditService;
import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

/** Admin audit log viewer (append-only — no mutation endpoints exist). */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/admin/audit")
    public String audit(@CurrentUser RequestPrincipal principal,
                        @ModelAttribute("filter") AuditFilter filter,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        Model model) {
        model.addAttribute("page", auditService.query(filter, PageRequest.of(Math.max(0, page), 30)));
        model.addAttribute("filter", filter);
        return "admin/admin-audit";
    }

    @GetMapping("/admin/audit/fragment")
    public String fragment(@ModelAttribute("filter") AuditFilter filter,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           Model model) {
        model.addAttribute("page", auditService.query(filter, PageRequest.of(Math.max(0, page), 30)));
        model.addAttribute("filter", filter);
        return "fragments :: auditRows";
    }
}
