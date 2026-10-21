package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.Queue;
import com.relaydesk.channel.domain.SlaPolicy;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.dto.CategoryForm;
import com.relaydesk.channel.dto.QueueForm;
import com.relaydesk.channel.dto.SlaPolicyForm;
import com.relaydesk.channel.dto.TeamForm;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.QueueRepository;
import com.relaydesk.channel.repo.SlaPolicyRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.UserTeamRepository;
import com.relaydesk.channel.service.AuditService;
import com.relaydesk.channel.service.RequestPrincipal;
import jakarta.validation.Valid;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin configuration CRUD: teams & queues, categories and SLA policies.
 * All mutations are admin-guarded, audited, and guarded against deleting referenced data.
 */
@Controller
@PreAuthorize("hasRole('ROLE_SUPPORT_ADMIN')")
public class AdminConfigController {

    /** Team with its member count (view row for admin/admin-teams). */
    public record TeamRow(UUID id, String name, String description, long memberCount) {
    }

    /** Category with its resolved default-team name (view row for admin/admin-categories). */
    public record CategoryRow(UUID id, String name, String description, UUID defaultTeamId,
                              String defaultTeamName, boolean active) {
    }

    private final TeamRepository teams;
    private final QueueRepository queues;
    private final CategoryRepository categories;
    private final SlaPolicyRepository slaPolicies;
    private final TicketRepository tickets;
    private final UserTeamRepository userTeams;
    private final AuditService audit;

    public AdminConfigController(TeamRepository teams, QueueRepository queues,
                                 CategoryRepository categories, SlaPolicyRepository slaPolicies,
                                 TicketRepository tickets, UserTeamRepository userTeams,
                                 AuditService audit) {
        this.teams = teams;
        this.queues = queues;
        this.categories = categories;
        this.slaPolicies = slaPolicies;
        this.tickets = tickets;
        this.userTeams = userTeams;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ teams & queues

    @GetMapping("/admin/teams")
    public String teamsPage(Model model) {
        List<Team> all = teams.findAll(Sort.by("name"));
        Map<UUID, Long> counts = userTeams.findAll().stream()
                .collect(Collectors.groupingBy(m -> m.getTeamId(), Collectors.counting()));
        model.addAttribute("teams", all.stream()
                .map(t -> new TeamRow(t.getId(), t.getName(), t.getDescription(),
                        counts.getOrDefault(t.getId(), 0L)))
                .toList());
        model.addAttribute("queues", queues.findAll(Sort.by("name")));
        model.addAttribute("teamForm", new TeamForm(null, null));
        model.addAttribute("queueForm", new QueueForm(null, null, null));
        return "admin/admin-teams";
    }

    @PostMapping("/admin/teams")
    public String createTeam(@CurrentUser RequestPrincipal principal,
                             @Valid @ModelAttribute("teamForm") TeamForm form,
                             RedirectAttributes redirectAttributes) {
        if (teams.existsByNameIgnoreCase(form.name())) {
            redirectAttributes.addFlashAttribute("flashError", "A team with that name already exists");
            return "redirect:/admin/teams";
        }
        Team team = new Team();
        team.setName(form.name());
        team.setDescription(form.description());
        teams.save(team);
        audit.record(principal, "team.created", "team", team.getId().toString(), team.getName(),
                null, team.getName());
        redirectAttributes.addFlashAttribute("flashSuccess", "Team created");
        return "redirect:/admin/teams";
    }

    @PostMapping("/admin/teams/{id}")
    public String updateTeam(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                             @Valid @ModelAttribute("teamForm") TeamForm form,
                             RedirectAttributes redirectAttributes) {
        Team team = teams.findById(id).orElse(null);
        if (team == null) {
            redirectAttributes.addFlashAttribute("flashError", "Team not found");
            return "redirect:/admin/teams";
        }
        if (!team.getName().equalsIgnoreCase(form.name()) && teams.existsByNameIgnoreCase(form.name())) {
            redirectAttributes.addFlashAttribute("flashError", "A team with that name already exists");
            return "redirect:/admin/teams";
        }
        String before = team.getName();
        team.setName(form.name());
        team.setDescription(form.description());
        teams.save(team);
        audit.record(principal, "team.updated", "team", id.toString(), team.getName(),
                before, team.getName());
        redirectAttributes.addFlashAttribute("flashSuccess", "Team updated");
        return "redirect:/admin/teams";
    }

    @PostMapping("/admin/teams/{id}/delete")
    public String deleteTeam(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                             RedirectAttributes redirectAttributes) {
        Team team = teams.findById(id).orElse(null);
        if (team == null) {
            redirectAttributes.addFlashAttribute("flashError", "Team not found");
            return "redirect:/admin/teams";
        }
        long referenced = tickets.count((root, q, cb) -> cb.equal(root.get("teamId"), id));
        if (!queues.findByTeamId(id).isEmpty() || referenced > 0) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Cannot delete: team still has queues or tickets");
            return "redirect:/admin/teams";
        }
        teams.delete(team);
        audit.record(principal, "team.deleted", "team", id.toString(), team.getName(),
                team.getName(), null);
        redirectAttributes.addFlashAttribute("flashSuccess", "Team deleted");
        return "redirect:/admin/teams";
    }

    @PostMapping("/admin/queues")
    public String createQueue(@CurrentUser RequestPrincipal principal,
                              @Valid @ModelAttribute("queueForm") QueueForm form,
                              RedirectAttributes redirectAttributes) {
        if (!teams.existsById(form.teamId())) {
            redirectAttributes.addFlashAttribute("flashError", "Team not found");
            return "redirect:/admin/teams";
        }
        Queue queue = new Queue();
        queue.setName(form.name());
        queue.setTeamId(form.teamId());
        queue.setDescription(form.description());
        queues.save(queue);
        audit.record(principal, "queue.created", "queue", queue.getId().toString(), queue.getName(),
                null, queue.getName());
        redirectAttributes.addFlashAttribute("flashSuccess", "Queue created");
        return "redirect:/admin/teams";
    }

    @PostMapping("/admin/queues/{id}")
    public String updateQueue(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                              @Valid @ModelAttribute("queueForm") QueueForm form,
                              RedirectAttributes redirectAttributes) {
        Queue queue = queues.findById(id).orElse(null);
        if (queue == null) {
            redirectAttributes.addFlashAttribute("flashError", "Queue not found");
            return "redirect:/admin/teams";
        }
        String before = queue.getName();
        queue.setName(form.name());
        queue.setTeamId(form.teamId());
        queue.setDescription(form.description());
        queues.save(queue);
        audit.record(principal, "queue.updated", "queue", id.toString(), queue.getName(),
                before, queue.getName());
        redirectAttributes.addFlashAttribute("flashSuccess", "Queue updated");
        return "redirect:/admin/teams";
    }

    @PostMapping("/admin/queues/{id}/delete")
    public String deleteQueue(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                              RedirectAttributes redirectAttributes) {
        Queue queue = queues.findById(id).orElse(null);
        if (queue == null) {
            redirectAttributes.addFlashAttribute("flashError", "Queue not found");
            return "redirect:/admin/teams";
        }
        long referenced = tickets.count((root, q, cb) -> cb.equal(root.get("queueId"), id));
        if (referenced > 0) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Cannot delete: queue still has tickets");
            return "redirect:/admin/teams";
        }
        queues.delete(queue);
        audit.record(principal, "queue.deleted", "queue", id.toString(), queue.getName(),
                queue.getName(), null);
        redirectAttributes.addFlashAttribute("flashSuccess", "Queue deleted");
        return "redirect:/admin/teams";
    }

    // ------------------------------------------------------------------ categories

    @GetMapping("/admin/categories")
    public String categoriesPage(Model model) {
        List<Team> allTeams = teams.findAll(Sort.by("name"));
        Map<UUID, Team> byId = allTeams.stream().collect(Collectors.toMap(Team::getId, Function.identity()));
        model.addAttribute("categories", categories.findAll(Sort.by("name")).stream()
                .map(c -> new CategoryRow(c.getId(), c.getName(), c.getDescription(),
                        c.getDefaultTeamId(),
                        c.getDefaultTeamId() == null ? null
                                : Optional.ofNullable(byId.get(c.getDefaultTeamId()))
                                        .map(Team::getName).orElse(null),
                        c.isActive()))
                .toList());
        model.addAttribute("teams", allTeams);
        model.addAttribute("categoryForm", new CategoryForm(null, null, null));
        return "admin/admin-categories";
    }

    @PostMapping("/admin/categories")
    public String createCategory(@CurrentUser RequestPrincipal principal,
                                 @Valid @ModelAttribute("categoryForm") CategoryForm form,
                                 RedirectAttributes redirectAttributes) {
        if (categories.existsByNameIgnoreCase(form.name())) {
            redirectAttributes.addFlashAttribute("flashError", "A category with that name already exists");
            return "redirect:/admin/categories";
        }
        Category category = new Category();
        category.setName(form.name());
        category.setDescription(form.description());
        category.setDefaultTeamId(form.defaultTeamId());
        category.setActive(true);
        categories.save(category);
        audit.record(principal, "category.created", "category", category.getId().toString(),
                category.getName(), null, category.getName());
        redirectAttributes.addFlashAttribute("flashSuccess", "Category created");
        return "redirect:/admin/categories";
    }

    @PostMapping("/admin/categories/{id}")
    public String updateCategory(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                                 @Valid @ModelAttribute("categoryForm") CategoryForm form,
                                 @RequestParam(name = "active", required = false) Boolean active,
                                 RedirectAttributes redirectAttributes) {
        Category category = categories.findById(id).orElse(null);
        if (category == null) {
            redirectAttributes.addFlashAttribute("flashError", "Category not found");
            return "redirect:/admin/categories";
        }
        String before = category.getName();
        category.setName(form.name());
        category.setDescription(form.description());
        category.setDefaultTeamId(form.defaultTeamId());
        if (active != null) {
            category.setActive(active);
        }
        categories.save(category);
        audit.record(principal, "category.updated", "category", id.toString(), category.getName(),
                before, category.getName() + " active=" + category.isActive());
        redirectAttributes.addFlashAttribute("flashSuccess", "Category updated");
        return "redirect:/admin/categories";
    }

    /** Soft delete: the category stops appearing in new-ticket forms. */
    @PostMapping("/admin/categories/{id}/delete")
    public String deleteCategory(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                                 RedirectAttributes redirectAttributes) {
        Category category = categories.findById(id).orElse(null);
        if (category == null) {
            redirectAttributes.addFlashAttribute("flashError", "Category not found");
            return "redirect:/admin/categories";
        }
        category.setActive(false);
        categories.save(category);
        audit.record(principal, "category.deleted", "category", id.toString(), category.getName(),
                "active", "inactive");
        redirectAttributes.addFlashAttribute("flashSuccess", "Category deactivated");
        return "redirect:/admin/categories";
    }

    // ------------------------------------------------------------------ SLA policies

    @GetMapping("/admin/sla")
    public String slaPage(Model model) {
        model.addAttribute("policies", slaPolicies.findAll(Sort.by("priority")));
        model.addAttribute("slaForm", new SlaPolicyForm(null, null, null, null, false, true));
        return "admin/admin-sla";
    }

    @PostMapping("/admin/sla")
    public String createSla(@CurrentUser RequestPrincipal principal,
                            @Valid @ModelAttribute("slaForm") SlaPolicyForm form,
                            RedirectAttributes redirectAttributes) {
        if (Boolean.TRUE.equals(form.active())) {
            // One active policy per priority: deactivate the current one.
            slaPolicies.findFirstByPriorityAndActiveTrue(form.priority()).ifPresent(existing -> {
                existing.setActive(false);
                slaPolicies.save(existing);
            });
        }
        SlaPolicy policy = new SlaPolicy();
        apply(policy, form);
        slaPolicies.save(policy);
        audit.record(principal, "sla.created", "sla_policy", policy.getId().toString(),
                policy.getName(), null, "priority=" + policy.getPriority());
        redirectAttributes.addFlashAttribute("flashSuccess", "SLA policy created");
        return "redirect:/admin/sla";
    }

    @PostMapping("/admin/sla/{id}")
    public String updateSla(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                            @Valid @ModelAttribute("slaForm") SlaPolicyForm form,
                            RedirectAttributes redirectAttributes) {
        SlaPolicy policy = slaPolicies.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("flashError", "Policy not found");
            return "redirect:/admin/sla";
        }
        if (Boolean.TRUE.equals(form.active()) && !form.priority().equals(policy.getPriority())) {
            slaPolicies.findFirstByPriorityAndActiveTrue(form.priority()).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    existing.setActive(false);
                    slaPolicies.save(existing);
                }
            });
        }
        apply(policy, form);
        slaPolicies.save(policy);
        audit.record(principal, "sla.updated", "sla_policy", id.toString(), policy.getName(),
                null, "priority=" + policy.getPriority() + " active=" + policy.isActive());
        redirectAttributes.addFlashAttribute("flashSuccess", "SLA policy updated");
        return "redirect:/admin/sla";
    }

    @PostMapping("/admin/sla/{id}/delete")
    public String deleteSla(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                            RedirectAttributes redirectAttributes) {
        SlaPolicy policy = slaPolicies.findById(id).orElse(null);
        if (policy == null) {
            redirectAttributes.addFlashAttribute("flashError", "Policy not found");
            return "redirect:/admin/sla";
        }
        long inUse = tickets.count((root, q, cb) -> cb.equal(root.get("slaPolicyId"), id));
        if (inUse > 0) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Cannot delete: policy is in use by " + inUse + " ticket(s)");
            return "redirect:/admin/sla";
        }
        slaPolicies.delete(policy);
        audit.record(principal, "sla.deleted", "sla_policy", id.toString(), policy.getName(),
                policy.getName(), null);
        redirectAttributes.addFlashAttribute("flashSuccess", "SLA policy deleted");
        return "redirect:/admin/sla";
    }

    private void apply(SlaPolicy policy, SlaPolicyForm form) {
        policy.setName(form.name());
        policy.setPriority(form.priority());
        policy.setResponseMinutes(form.responseMinutes());
        policy.setResolutionMinutes(form.resolutionMinutes());
        policy.setBusinessHoursOnly(Boolean.TRUE.equals(form.businessHoursOnly()));
        policy.setActive(Boolean.TRUE.equals(form.active()));
    }
}
