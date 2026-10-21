package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket message: either a public reply (internal=false) or an internal-only note
 * (internal=true). Internal notes must never be rendered or exported to customers —
 * see service-level guards and export filtering.
 */
@Entity
@Table(name = "ticket_messages")
@Getter
@Setter
@NoArgsConstructor
public class TicketMessage {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "internal", nullable = false)
    private boolean internal;

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
