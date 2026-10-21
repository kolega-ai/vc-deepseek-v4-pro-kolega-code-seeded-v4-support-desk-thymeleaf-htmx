package com.relaydesk.channel.web;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/** Customer directory management (organizations & contacts via the external customer core). */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminOrgController {

    private final CoreClient core;

    public AdminOrgController(CoreClient core) {
        this.core = core;
    }

    @GetMapping("/admin/orgs")
    public String orgs(@CurrentUser RequestPrincipal principal,
                       @RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("page", core.listOrgs(principal, q, Math.max(0, page), 15));
        model.addAttribute("q", q);
        return "admin/admin-orgs";
    }

    @GetMapping("/admin/orgs/{id}")
    public String detail(@CurrentUser RequestPrincipal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("org", core.org(principal, id));
        return "admin/admin-org-detail";
    }

    @PostMapping("/admin/orgs")
    public String create(@CurrentUser RequestPrincipal principal,
                         @RequestParam String name,
                         @RequestParam(name = "plan", required = false) String plan,
                         @RequestParam(name = "address", required = false) String address,
                         RedirectAttributes redirectAttributes) {
        var org = core.createOrg(principal, name, plan, address);
        redirectAttributes.addFlashAttribute("flashSuccess", "Organization created: " + org.name());
        return "redirect:/admin/orgs/" + org.id();
    }

    @PostMapping("/admin/orgs/{id}")
    public String update(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @RequestParam String name,
                         @RequestParam(name = "plan", required = false) String plan,
                         @RequestParam(name = "address", required = false) String address,
                         @RequestParam(name = "status", required = false) String status,
                         RedirectAttributes redirectAttributes) {
        core.updateOrg(principal, id, name, plan, address, status);
        redirectAttributes.addFlashAttribute("flashSuccess", "Organization updated");
        return "redirect:/admin/orgs/" + id;
    }

    @PostMapping("/admin/orgs/{id}/contacts")
    public String createContact(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                                @RequestParam String firstName,
                                @RequestParam String lastName,
                                @RequestParam String email,
                                @RequestParam(name = "phone", required = false) String phone,
                                @RequestParam(name = "title", required = false) String title,
                                RedirectAttributes redirectAttributes) {
        core.createContact(principal, id, firstName, lastName, email, phone, title);
        redirectAttributes.addFlashAttribute("flashSuccess", "Contact added");
        return "redirect:/admin/orgs/" + id;
    }

    @PostMapping("/admin/contacts/{id}")
    public String updateContact(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                                @RequestParam String firstName,
                                @RequestParam String lastName,
                                @RequestParam String email,
                                @RequestParam(name = "phone", required = false) String phone,
                                @RequestParam(name = "title", required = false) String title,
                                @RequestParam(name = "active", defaultValue = "false") boolean active,
                                RedirectAttributes redirectAttributes,
                                jakarta.servlet.http.HttpServletRequest request) {
        core.updateContact(principal, id, firstName, lastName, email, phone, title, active);
        redirectAttributes.addFlashAttribute("flashSuccess", "Contact updated");
        String referer = request.getHeader("Referer");
        String path = referer == null ? null
                : referer.replaceFirst("^https?://[^/]*", "");
        return "redirect:" + (path == null || path.isBlank() ? "/admin/orgs" : path);
    }
}
