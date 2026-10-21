package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit event. Every state transition and privileged action writes one event with
 * actor, action, before/after values, timestamp, and the authorization basis. No code path
 * ever updates or deletes rows of this table (there is deliberately no update/delete endpoint
 * and the repository only saves new instances).
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "action", nullable = false, length = 60)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 80)
    private String resourceId;

    @Column(name = "resource_ref", length = 80)
    private String resourceRef;

    @Column(name = "before_value", columnDefinition = "text")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "text")
    private String afterValue;

    /** Authorization basis of the action, e.g. "role=SUPPORT_AGENT scope=teams:[uuid]". */
    @Column(name = "authz_basis", length = 500)
    private String authzBasis;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
