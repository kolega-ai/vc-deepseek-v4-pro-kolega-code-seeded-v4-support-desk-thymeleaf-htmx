package com.relaydesk.channel.web;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.dto.InviteForm;
import com.relaydesk.channel.dto.UserFilter;
import com.relaydesk.channel.dto.UserForm;
import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.service.CreatedUser;
import com.relaydesk.channel.service.InviteCreated;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.UserAdminService;
import com.relaydesk.shared.Role;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/** Admin user management: staff accounts, roles, enable/disable and customer invites. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final UserAdminService userAdminService;
    private final TeamRepository teams;
    private final CoreClient core;

    public AdminUserController(UserAdminService userAdminService, TeamRepository teams,
                               CoreClient core) {
        this.userAdminService = userAdminService;
        this.teams = teams;
        this.core = core;
    }

    @GetMapping("/admin/users")
    public String users(@CurrentUser RequestPrincipal principal,
                        @ModelAttribute("filter") UserFilter filter,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        Model model) {
        model.addAttribute("page",
                userAdminService.query(filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        model.addAttribute("roles", Role.values());
        model.addAttribute("teams", teams.findAll(Sort.by("name")));
        model.addAttribute("userForm", new UserForm(null, null, null, List.of(), true));
        model.addAttribute("inviteForm", new InviteForm(null, null, null));
        model.addAttribute("orgs", orgs(principal));
        return "admin/admin-users";
    }

    @GetMapping("/admin/users/fragment")
    public String usersFragment(@ModelAttribute("filter") UserFilter filter,
                                @RequestParam(name = "page", defaultValue = "0") int page,
                                Model model) {
        model.addAttribute("page",
                userAdminService.query(filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        return "fragments :: userRows";
    }

    @PostMapping("/admin/users")
    public String create(@CurrentUser RequestPrincipal principal,
                         @Valid @ModelAttribute("userForm") UserForm form,
                         RedirectAttributes redirectAttributes) {
        CreatedUser created = userAdminService.createSupportUser(principal, form);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "User created — initial password: " + created.initialPassword() + " (shown once)");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/role")
    public String role(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                       @RequestParam Role role, RedirectAttributes redirectAttributes) {
        userAdminService.updateRole(principal, id, role);
        redirectAttributes.addFlashAttribute("flashSuccess", "Role updated");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/enabled")
    public String enabled(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          @RequestParam boolean enabled, RedirectAttributes redirectAttributes) {
        userAdminService.setEnabled(principal, id, enabled);
        redirectAttributes.addFlashAttribute("flashSuccess",
                enabled ? "User enabled" : "User disabled — sessions stop working immediately");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/invite")
    public String invite(@CurrentUser RequestPrincipal principal,
                         @Valid @ModelAttribute("inviteForm") InviteForm form,
                         RedirectAttributes redirectAttributes) {
        InviteCreated created = userAdminService.createInvite(principal, form);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "Invite created — link (mocked email): " + created.link());
        return "redirect:/admin/users";
    }

    @GetMapping("/admin/invites")
    public String invites(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("page", userAdminService.invites(PageRequest.of(Math.max(0, page), 15)));
        return "admin/admin-invites";
    }

    private List<?> orgs(RequestPrincipal principal) {
        try {
            return core.listOrgs(principal, null, 0, 50).getContent();
        } catch (CoreClientException e) {
            log.warn("core unavailable while loading orgs for user admin", e);
            return List.of();
        }
    }
}
