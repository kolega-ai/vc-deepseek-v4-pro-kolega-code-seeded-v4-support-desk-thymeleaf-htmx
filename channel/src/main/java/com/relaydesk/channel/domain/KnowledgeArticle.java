package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kb_articles")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeArticle {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "slug", nullable = false, unique = true, length = 220)
    private String slug;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "tags", length = 500)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private KbStatus status;

    /** True = staff-only article, even when published. */
    @Column(name = "internal_only", nullable = false)
    private boolean internalOnly;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
