package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.domain.KnowledgeArticle;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.KbArticleForm;
import com.relaydesk.channel.dto.KbFilter;
import com.relaydesk.channel.dto.KbRow;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.KnowledgeArticleRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Knowledge base. Visibility: PUBLISHED && !internalOnly → customers + public; everything
 * else staff only. Body is plain text with line breaks (escaped rendering).
 */
@Service
@Transactional
public class KbService {

    private final KnowledgeArticleRepository kb;
    private final CategoryRepository categories;
    private final UserAccountRepository users;
    private final AuditService audit;

    public KbService(KnowledgeArticleRepository kb, CategoryRepository categories,
                     UserAccountRepository users, AuditService audit) {
        this.kb = kb;
        this.categories = categories;
        this.users = users;
        this.audit = audit;
    }

    public Page<KbRow> staffQuery(RequestPrincipal p, KbFilter f, Pageable pageable) {
        if (!p.isStaff()) {
            throw new AccessDeniedException("staff only");
        }
        KbFilter filter = f == null ? new KbFilter(null, null, null) : f;
        Specification<KnowledgeArticle> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (filter.status() != null) {
                ps.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.authorId() != null) {
                ps.add(cb.equal(root.get("authorId"), filter.authorId()));
            }
            if (filter.q() != null) {
                String like = "%" + filter.q().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("body")), like),
                        cb.like(cb.lower(root.get("tags")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<KnowledgeArticle> page = kb.findAll(spec, pr);
        Map<UUID, String> categoryNames = categoryNames(page.getContent());
        Map<UUID, UserAccount> accountMap = userMap(page.getContent().stream()
                .map(KnowledgeArticle::getAuthorId).collect(Collectors.toSet()));
        return page.map(a -> toRow(a, categoryNames, accountMap));
    }

    public Page<KbRow> publicSearch(String q, Pageable pageable) {
        String query = (q == null || q.isBlank()) ? null : q.toLowerCase(Locale.ROOT);
        Specification<KnowledgeArticle> spec = (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), KbStatus.PUBLISHED));
            ps.add(cb.isFalse(root.get("internalOnly")));
            if (query != null) {
                String like = "%" + query + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("body")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "publishedAt"));
        Page<KnowledgeArticle> page = kb.findAll(spec, pr);
        Map<UUID, String> categoryNames = categoryNames(page.getContent());
        Map<UUID, UserAccount> accountMap = userMap(page.getContent().stream()
                .map(KnowledgeArticle::getAuthorId).collect(Collectors.toSet()));
        return page.map(a -> toRow(a, categoryNames, accountMap));
    }

    public KnowledgeArticle article(RequestPrincipal p, UUID id) {
        KnowledgeArticle a = kb.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("article not found"));
        AccessGuard.requireKbVisible(p, a);
        if (!p.isStaff()) {
            a.setViewCount(a.getViewCount() + 1);
            kb.save(a);
        }
        return a;
    }

    public KnowledgeArticle publicBySlug(String slug) {
        KnowledgeArticle a = kb.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("article not found"));
        if (a.getStatus() != KbStatus.PUBLISHED || a.isInternalOnly()) {
            throw new ResourceNotFoundException("article not found");
        }
        return a;
    }

    public KnowledgeArticle create(RequestPrincipal p, KbArticleForm form) {
        requireStaff(p);
        KnowledgeArticle a = new KnowledgeArticle();
        a.setTitle(form.title());
        a.setBody(form.body());
        a.setCategoryId(form.categoryId());
        a.setTags(form.tags());
        a.setInternalOnly(Boolean.TRUE.equals(form.internalOnly()));
        a.setStatus(KbStatus.DRAFT);
        a.setAuthorId(p.userId());
        a.setViewCount(0);
        a.setSlug(uniqueSlug(form.title()));
        kb.save(a);
        audit.record(p, "kb.created", "kb_article", a.getId().toString(), a.getSlug(),
                null, a.getTitle());
        return a;
    }

    public KnowledgeArticle update(RequestPrincipal p, UUID id, KbArticleForm form) {
        KnowledgeArticle a = load(id);
        boolean authorOfDraft = p.userId() != null && p.userId().equals(a.getAuthorId())
                && a.getStatus() == KbStatus.DRAFT;
        if (!(p.isAdmin() || p.isLead() || authorOfDraft)) {
            throw new AccessDeniedException("not allowed to edit this article");
        }
        a.setTitle(form.title());
        a.setBody(form.body());
        a.setCategoryId(form.categoryId());
        a.setTags(form.tags());
        a.setInternalOnly(form.internalOnly());
        kb.save(a);
        audit.record(p, "kb.updated", "kb_article", id.toString(), a.getSlug(), null, a.getTitle());
        return a;
    }

    public KnowledgeArticle submit(RequestPrincipal p, UUID id) {
        KnowledgeArticle a = load(id);
        boolean author = p.userId() != null && p.userId().equals(a.getAuthorId());
        if (!(author || p.isAdmin() || p.isLead())) {
            throw new AccessDeniedException("only the author can submit this article");
        }
        KbTransitions.require(a.getStatus(), KbStatus.IN_REVIEW);
        a.setStatus(KbStatus.IN_REVIEW);
        kb.save(a);
        audit.record(p, "kb.submitted", "kb_article", id.toString(), a.getSlug(), "DRAFT", "IN_REVIEW");
        return a;
    }

    public KnowledgeArticle publish(RequestPrincipal p, UUID id) {
        requireLeadOrAdmin(p);
        KnowledgeArticle a = load(id);
        KbTransitions.require(a.getStatus(), KbStatus.PUBLISHED);
        a.setStatus(KbStatus.PUBLISHED);
        a.setPublishedAt(Instant.now());
        kb.save(a);
        audit.record(p, "kb.published", "kb_article", id.toString(), a.getSlug(), "IN_REVIEW", "PUBLISHED");
        return a;
    }

    public KnowledgeArticle sendBack(RequestPrincipal p, UUID id, String reason) {
        requireLeadOrAdmin(p);
        KnowledgeArticle a = load(id);
        KbTransitions.require(a.getStatus(), KbStatus.DRAFT);
        a.setStatus(KbStatus.DRAFT);
        kb.save(a);
        audit.record(p, "kb.sent_back", "kb_article", id.toString(), a.getSlug(), "IN_REVIEW", reason);
        return a;
    }

    public KnowledgeArticle archive(RequestPrincipal p, UUID id) {
        requireLeadOrAdmin(p);
        KnowledgeArticle a = load(id);
        KbTransitions.require(a.getStatus(), KbStatus.ARCHIVED);
        a.setStatus(KbStatus.ARCHIVED);
        kb.save(a);
        audit.record(p, "kb.archived", "kb_article", id.toString(), a.getSlug(), "PUBLISHED", "ARCHIVED");
        return a;
    }

    public KnowledgeArticle restore(RequestPrincipal p, UUID id) {
        requireLeadOrAdmin(p);
        KnowledgeArticle a = load(id);
        KbTransitions.require(a.getStatus(), KbStatus.DRAFT);
        a.setStatus(KbStatus.DRAFT);
        kb.save(a);
        audit.record(p, "kb.restored", "kb_article", id.toString(), a.getSlug(), "ARCHIVED", "DRAFT");
        return a;
    }

    // ------------------------------------------------------------------ helpers

    private KnowledgeArticle load(UUID id) {
        return kb.findById(id).orElseThrow(() -> new ResourceNotFoundException("article not found"));
    }

    private void requireStaff(RequestPrincipal p) {
        if (p == null || !p.isStaff()) {
            throw new AccessDeniedException("staff only");
        }
    }

    private void requireLeadOrAdmin(RequestPrincipal p) {
        if (p == null || !(p.isAdmin() || p.isLead())) {
            throw new AccessDeniedException("team lead or admin only");
        }
    }

    /** Lowercase, non-alphanumerics → '-', trimmed and collapsed, uniquified with -2, -3… */
    private String uniqueSlug(String title) {
        String base = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (base.isEmpty()) {
            base = "article";
        }
        String slug = base;
        int i = 2;
        while (kb.existsBySlug(slug)) {
            slug = base + "-" + (i++);
        }
        return slug;
    }

    private KbRow toRow(KnowledgeArticle a, Map<UUID, String> categoryNames,
                        Map<UUID, UserAccount> accountMap) {
        UserAccount author = accountMap.get(a.getAuthorId());
        return new KbRow(a.getId(), a.getSlug(), a.getTitle(), a.getStatus(), a.isInternalOnly(),
                author == null ? null : author.getDisplayName(),
                categoryNames.get(a.getCategoryId()), a.getTags(),
                a.getPublishedAt(), a.getUpdatedAt(), a.getViewCount());
    }

    private Map<UUID, String> categoryNames(List<KnowledgeArticle> articles) {
        Set<UUID> ids = articles.stream().map(KnowledgeArticle::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categories.findAllById(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    private Map<UUID, UserAccount> userMap(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(UserAccount::getId, u -> u));
    }
}
