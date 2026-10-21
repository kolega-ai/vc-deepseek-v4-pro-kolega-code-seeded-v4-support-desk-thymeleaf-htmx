package com.relaydesk.channel.web;

import com.relaydesk.channel.dto.WebhookForm;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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

/** Admin webhook endpoint management and delivery-attempt monitoring. */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminWebhookController {

    private final WebhookService webhookService;

    public AdminWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping("/admin/webhooks")
    public String webhooks(Model model) {
        model.addAttribute("endpoints", webhookService.endpoints(PageRequest.of(0, 50)).getContent());
        model.addAttribute("attempts", webhookService.attempts(PageRequest.of(0, 20)).getContent());
        model.addAttribute("webhookForm", new WebhookForm(null, null, null, List.of()));
        return "admin/admin-webhooks";
    }

    @GetMapping("/admin/webhooks/fragment")
    public String fragment(Model model) {
        model.addAttribute("attempts", webhookService.attempts(PageRequest.of(0, 20)).getContent());
        return "fragments :: webhookAttempts";
    }

    @PostMapping("/admin/webhooks")
    public String create(@CurrentUser RequestPrincipal principal,
                         @Valid @ModelAttribute("webhookForm") WebhookForm form,
                         RedirectAttributes redirectAttributes) {
        webhookService.create(principal, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Webhook endpoint created");
        return "redirect:/admin/webhooks";
    }

    @PostMapping("/admin/webhooks/{id}")
    public String update(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @Valid @ModelAttribute("webhookForm") WebhookForm form,
                         RedirectAttributes redirectAttributes) {
        webhookService.update(principal, id, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Webhook endpoint updated");
        return "redirect:/admin/webhooks";
    }

    @PostMapping("/admin/webhooks/{id}/delete")
    public String delete(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         RedirectAttributes redirectAttributes) {
        webhookService.delete(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Webhook endpoint deleted");
        return "redirect:/admin/webhooks";
    }

    @PostMapping("/admin/webhooks/{id}/test")
    public String test(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                       RedirectAttributes redirectAttributes) {
        webhookService.fireTest(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Test event queued for delivery");
        return "redirect:/admin/webhooks";
    }
}
