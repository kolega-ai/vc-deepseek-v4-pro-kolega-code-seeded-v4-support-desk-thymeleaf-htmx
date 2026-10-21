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

/**
 * Escalation. The reason, resolution and SLA-exception fields are internal-only content and
 * must never appear in customer portals, exports, webhooks or notifications.
 */
@Entity
@Table(name = "escalations")
@Getter
@Setter
@NoArgsConstructor
public class Escalation {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "sla_exception", nullable = false)
    private boolean slaException;

    @Column(name = "sla_exception_reason", columnDefinition = "text")
    private String slaExceptionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private EscalationStatus status;

    @Column(name = "resolution", columnDefinition = "text")
    private String resolution;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

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
