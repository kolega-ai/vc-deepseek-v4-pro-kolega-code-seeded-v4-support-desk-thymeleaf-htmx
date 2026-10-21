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

/**
 * SLA policy per priority. Response/resolution deadlines in minutes.
 * Policies are seeded reference data managed by admins; the values are read server-side —
 * never accepted from the client — when a ticket is created or its priority changes.
 */
@Entity
@Table(name = "sla_policies")
@Getter
@Setter
@NoArgsConstructor
public class SlaPolicy {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private Priority priority;

    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "business_hours_only", nullable = false)
    private boolean businessHoursOnly = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
