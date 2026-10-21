package com.relaydesk.core.domain;

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

/** Customer organization in the vendor CRM ("organizations"). Status: ACTIVE | SUSPENDED. */
@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
public class Organization {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 220, unique = true)
    private String slug;

    @Column(nullable = false, length = 50)
    private String plan;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String address;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
