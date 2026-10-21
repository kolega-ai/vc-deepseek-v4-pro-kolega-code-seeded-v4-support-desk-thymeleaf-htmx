package com.relaydesk.channel.web;

import com.relaydesk.channel.dto.EscalationFilter;
import com.relaydesk.channel.dto.EscalationRejectForm;
import com.relaydesk.channel.dto.EscalationResolveForm;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.service.EscalationService;
import com.relaydesk.channel.service.RequestPrincipal;
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

import java.util.UUID;

/** Escalation queue and decisions (accept / review / reject / resolve). */
@Controller
@PreAuthorize("hasAnyRole('ROLE_SUPPORT_AGENT','ROLE_TEAM_LEAD','ROLE_SUPPORT_ADMIN')")
public class EscalationController {

    private final EscalationService escalationService;
    private final TeamRepository teams;

    public EscalationController(EscalationService escalationService, TeamRepository teams) {
        this.escalationService = escalationService;
        this.teams = teams;
    }

    @GetMapping("/desk/escalations")
    public String list(@CurrentUser RequestPrincipal principal,
                       @ModelAttribute("filter") EscalationFilter filter,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("page",
                escalationService.query(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        model.addAttribute("teams", teams.findAll(Sort.by("name")));
        return "desk/desk-escalations";
    }

    @GetMapping("/desk/escalations/fragment")
    public String fragment(@CurrentUser RequestPrincipal principal,
                           @ModelAttribute("filter") EscalationFilter filter,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           Model model) {
        model.addAttribute("page",
                escalationService.query(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        return "fragments :: escalationRows";
    }

    @GetMapping("/desk/escalations/{id}")
    public String detail(@CurrentUser RequestPrincipal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("detail", escalationService.detail(principal, id));
        model.addAttribute("resolveForm", new EscalationResolveForm(null, false));
        model.addAttribute("rejectForm", new EscalationRejectForm(null));
        return "desk/desk-escalation-detail";
    }

    @PostMapping("/desk/escalations/{id}/accept")
    public String accept(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         RedirectAttributes redirectAttributes) {
        escalationService.accept(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Escalation accepted");
        return "redirect:/desk/escalations/" + id;
    }

    @PostMapping("/desk/escalations/{id}/review")
    public String review(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         RedirectAttributes redirectAttributes) {
        escalationService.startReview(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Escalation moved to review");
        return "redirect:/desk/escalations/" + id;
    }

    @PostMapping("/desk/escalations/{id}/reject")
    public String reject(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @ModelAttribute EscalationRejectForm form,
                         RedirectAttributes redirectAttributes) {
        escalationService.reject(principal, id, form.reason());
        redirectAttributes.addFlashAttribute("flashSuccess", "Escalation rejected — ticket returned to queue");
        return "redirect:/desk/escalations/" + id;
    }

    @PostMapping("/desk/escalations/{id}/withdraw")
    public String withdraw(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                           @ModelAttribute EscalationRejectForm form,
                           RedirectAttributes redirectAttributes) {
        escalationService.withdraw(principal, id, form.reason());
        redirectAttributes.addFlashAttribute("flashSuccess",
                "Escalation withdrawn — ticket returned to the queue");
        return "redirect:/desk/escalations";
    }

    @PostMapping("/desk/escalations/{id}/resolve")
    public String resolve(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          @ModelAttribute EscalationResolveForm form,
                          RedirectAttributes redirectAttributes) {
        escalationService.resolve(principal, id, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Escalation resolved");
        return "redirect:/desk/escalations/" + id;
    }
}
