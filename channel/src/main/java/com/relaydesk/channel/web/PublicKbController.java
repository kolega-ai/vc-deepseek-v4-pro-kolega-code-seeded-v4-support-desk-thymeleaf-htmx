package com.relaydesk.channel.web;

import com.relaydesk.channel.domain.KnowledgeArticle;
import com.relaydesk.channel.service.KbService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Public knowledge base: search, index and article pages (no authentication required). */
@Controller
public class PublicKbController {

    private final KbService kbService;

    public PublicKbController(KbService kbService) {
        this.kbService = kbService;
    }

    @GetMapping("/kb")
    public String index(@RequestParam(name = "q", required = false) String q,
                        @RequestParam(name = "page", defaultValue = "0") int page,
                        Model model) {
        model.addAttribute("page", kbService.publicSearch(q, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("q", q);
        return "kb/kb-index";
    }

    /** Debounced htmx search fragment (covered by the /kb/** public prefix). */
    @GetMapping("/kb/search")
    public String search(@RequestParam(name = "q", required = false) String q,
                         @RequestParam(name = "page", defaultValue = "0") int page,
                         Model model) {
        model.addAttribute("page", kbService.publicSearch(q, PageRequest.of(Math.max(0, page), 15)));
        model.addAttribute("q", q);
        return "fragments :: kbResults";
    }

    @GetMapping("/kb/{slug}")
    public String article(@PathVariable String slug, Model model) {
        KnowledgeArticle article = kbService.publicBySlug(slug);
        model.addAttribute("article", article);
        model.addAttribute("related", kbService.publicSearch(null, PageRequest.of(0, 3))
                .stream().filter(a -> !a.slug().equals(article.getSlug())).toList());
        return "kb/kb-article";
    }
}
