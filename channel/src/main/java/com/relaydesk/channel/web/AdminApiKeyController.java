package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.ApiKeyForm;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.service.ApiKeyCreated;
import com.relaydesk.channel.service.ApiKeyService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.shared.Role;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/** Admin API keys: create (plaintext shown once via flash), list and revoke. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserAccountRepository users;

    public AdminApiKeyController(ApiKeyService apiKeyService, UserAccountRepository users) {
        this.apiKeyService = apiKeyService;
        this.users = users;
    }

    @GetMapping("/admin/api-keys")
    public String keys(Model model) {
        model.addAttribute("keys", apiKeyService.keys(PageRequest.of(0, 50)));
        model.addAttribute("users", staffUsers());
        model.addAttribute("apiKeyForm", new ApiKeyForm(null, null, List.of(), null));
        // Do not clobber the one-time flash attribute that carries the newly created key.
        if (!model.containsAttribute("newKey")) {
            model.addAttribute("newKey", null);
        }
        return "admin/admin-api-keys";
    }

    @PostMapping("/admin/api-keys")
    public String create(@CurrentUser RequestPrincipal principal,
                         @Valid @ModelAttribute("apiKeyForm") ApiKeyForm form,
                         RedirectAttributes redirectAttributes) {
        ApiKeyCreated created = apiKeyService.create(principal, form);
        redirectAttributes.addFlashAttribute("flashSuccess",
                "API key created — copy it now, it will not be shown again");
        redirectAttributes.addFlashAttribute("newKey", created.plaintext());
        return "redirect:/admin/api-keys";
    }

    @PostMapping("/admin/api-keys/{id}/revoke")
    public String revoke(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         RedirectAttributes redirectAttributes) {
        apiKeyService.revoke(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "API key revoked");
        return "redirect:/admin/api-keys";
    }

    private List<UserAccount> staffUsers() {
        return users.findAll((root, q, cb) -> cb.or(
                        cb.equal(root.get("role"), Role.SUPPORT_AGENT),
                        cb.equal(root.get("role"), Role.TEAM_LEAD),
                        cb.equal(root.get("role"), Role.SUPPORT_ADMIN)),
                Sort.by("email"));
    }
}
