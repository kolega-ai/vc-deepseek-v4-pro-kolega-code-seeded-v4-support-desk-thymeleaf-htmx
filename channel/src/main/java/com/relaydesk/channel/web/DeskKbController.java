package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.KnowledgeArticle;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.KbArticleForm;
import com.relaydesk.channel.dto.KbFilter;
import com.relaydesk.channel.dto.KbReviewForm;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.service.KbService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.shared.Role;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staff knowledge base: list, editor, and the review workflow (submit/publish/send-back/archive/restore). */
@Controller
@PreAuthorize("hasAnyRole('ROLE_SUPPORT_AGENT','ROLE_TEAM_LEAD','ROLE_SUPPORT_ADMIN')")
public class DeskKbController {

    private final KbService kbService;
    private final CategoryRepository categories;
    private final UserAccountRepository users;

    public DeskKbController(KbService kbService, CategoryRepository categories,
                            UserAccountRepository users) {
        this.kbService = kbService;
        this.categories = categories;
        this.users = users;
    }

    @GetMapping("/desk/kb")
    public String list(@CurrentUser RequestPrincipal principal,
                       @ModelAttribute("filter") KbFilter filter,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("page",
                kbService.staffQuery(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        model.addAttribute("authors", staffUsers());
        return "desk/desk-kb-list";
    }

    @GetMapping("/desk/kb/fragment")
    public String fragment(@CurrentUser RequestPrincipal principal,
                           @ModelAttribute("filter") KbFilter filter,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           Model model) {
        model.addAttribute("page",
                kbService.staffQuery(principal, filter, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("filter", filter);
        return "fragments :: kbRows";
    }

    /**
     * Live preview of a body fragment an author pasted into the editor, rendered with the
     * knowledge-base markup engine so writers can check the output before saving.
     */
    @GetMapping("/desk/kb/preview/{snippet}")
    public void previewSnippet(@PathVariable String snippet, HttpServletResponse response)
            throws Exception {
        freemarker.template.Configuration cfg =
                new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_33);
        freemarker.template.Template template =
                new freemarker.template.Template("snippet", snippet, cfg);
        Map<String, Object> model = new HashMap<>();
        model.put("article", "RelayDesk");
        model.put("category", "Billing");
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(writer.toString());
    }

    @GetMapping("/desk/kb/new")
    public String editorNew(Model model) {
        model.addAttribute("form", new KbArticleForm(null, null, null, null, false));
        model.addAttribute("article", null);
        model.addAttribute("categories", categories.findByActiveTrueOrderByNameAsc());
        return "desk/desk-kb-editor";
    }

    @PostMapping("/desk/kb")
    public String create(@CurrentUser RequestPrincipal principal,
                         @Valid @ModelAttribute("form") KbArticleForm form) {
        KnowledgeArticle article = kbService.create(principal, form);
        return "redirect:/desk/kb/" + article.getId() + "/edit";
    }

    @GetMapping("/desk/kb/{id}/edit")
    public String editorEdit(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                             Model model) {
        KnowledgeArticle article = kbService.article(principal, id);
        model.addAttribute("article", article);
        model.addAttribute("form", new KbArticleForm(article.getTitle(), article.getCategoryId(),
                article.getTags(), article.getBody(), article.isInternalOnly()));
        model.addAttribute("categories", categories.findByActiveTrueOrderByNameAsc());
        model.addAttribute("reviewForm", new KbReviewForm(null));
        return "desk/desk-kb-editor";
    }

    @PostMapping("/desk/kb/{id}")
    public String update(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         @Valid @ModelAttribute("form") KbArticleForm form,
                         RedirectAttributes redirectAttributes) {
        kbService.update(principal, id, form);
        redirectAttributes.addFlashAttribute("flashSuccess", "Article saved");
        return "redirect:/desk/kb/" + id + "/edit";
    }

    @PostMapping("/desk/kb/{id}/submit")
    public String submit(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                         RedirectAttributes redirectAttributes) {
        kbService.submit(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Article submitted for review");
        return "redirect:/desk/kb/" + id + "/edit";
    }

    @PostMapping("/desk/kb/{id}/publish")
    public String publish(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          RedirectAttributes redirectAttributes) {
        kbService.publish(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Article published");
        return "redirect:/desk/kb/" + id + "/edit";
    }

    @PostMapping("/desk/kb/{id}/send-back")
    public String sendBack(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                           @ModelAttribute KbReviewForm form, RedirectAttributes redirectAttributes) {
        kbService.sendBack(principal, id, form.reason());
        redirectAttributes.addFlashAttribute("flashSuccess", "Article sent back to draft");
        return "redirect:/desk/kb/" + id + "/edit";
    }

    @PostMapping("/desk/kb/{id}/archive")
    public String archive(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          RedirectAttributes redirectAttributes) {
        kbService.archive(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Article archived");
        return "redirect:/desk/kb/" + id + "/edit";
    }

    @PostMapping("/desk/kb/{id}/restore")
    public String restore(@CurrentUser RequestPrincipal principal, @PathVariable UUID id,
                          RedirectAttributes redirectAttributes) {
        kbService.restore(principal, id);
        redirectAttributes.addFlashAttribute("flashSuccess", "Article restored to draft");
        return "redirect:/desk/kb/" + id + "/edit";
    }

    private List<UserAccount> staffUsers() {
        return users.findAll((root, q, cb) -> cb.or(
                        cb.equal(root.get("role"), Role.SUPPORT_AGENT),
                        cb.equal(root.get("role"), Role.TEAM_LEAD),
                        cb.equal(root.get("role"), Role.SUPPORT_ADMIN)),
                Sort.by("displayName"));
    }
}
