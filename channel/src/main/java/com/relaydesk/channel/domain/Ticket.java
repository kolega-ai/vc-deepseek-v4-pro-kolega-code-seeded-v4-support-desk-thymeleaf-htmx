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
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
public class Ticket {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Human-facing reference, e.g. TCK-1001. */
    @Column(name = "ref", nullable = false, unique = true, length = 30)
    private String ref;

    /** Customer organization id in core-mock (the external customer directory). */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Snapshot of the organization name at creation time (avoids a core lookup per row). */
    @Column(name = "org_name", nullable = false, length = 200)
    private String orgName;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TicketStatus status;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "queue_id")
    private UUID queueId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "sla_policy_id", nullable = false)
    private UUID slaPolicyId;

    @Column(name = "sla_response_deadline", nullable = false)
    private Instant slaResponseDeadline;

    @Column(name = "sla_resolution_deadline", nullable = false)
    private Instant slaResolutionDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "sla_status", nullable = false, length = 20)
    private SlaStatus slaStatus;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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
