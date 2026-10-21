package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Attachment metadata; the file itself lives on local disk under a UUID name. */
@Entity
@Table(name = "attachments")
@Getter
@Setter
@NoArgsConstructor
public class Attachment {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private AttachmentCategory category;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** File name on disk (a UUID — never the user-supplied name). */
    @Column(name = "stored_name", nullable = false, length = 80)
    private String storedName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
