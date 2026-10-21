package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.domain.KnowledgeArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, UUID>, JpaSpecificationExecutor<KnowledgeArticle> {
    Optional<KnowledgeArticle> findBySlug(String slug);
    boolean existsBySlug(String slug);
    List<KnowledgeArticle> findTop6ByStatusAndInternalOnlyFalseOrderByPublishedAtDesc(KbStatus status);
}
