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

/** Customer onboarding invite (7 days, single use); only the SHA-256 hash of the token is stored. */
@Entity
@Table(name = "user_invites")
@Getter
@Setter
@NoArgsConstructor
public class UserInvite {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "org_name", nullable = false, length = 200)
    private String orgName;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "invited_by_id", nullable = false)
    private UUID invitedById;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

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
