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

/** Person at an organization ("contacts"). */
@Entity
@Table(name = "contacts")
@Getter
@Setter
@NoArgsConstructor
public class Contact {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(length = 120)
    private String title;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
