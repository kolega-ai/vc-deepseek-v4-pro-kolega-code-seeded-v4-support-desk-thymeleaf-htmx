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
 * API key for the JSON API surface. Only the SHA-256 hash of the key is stored;
 * the plaintext is shown once at creation.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    /** First characters of the key, for identification in the UI only. */
    @Column(name = "prefix", nullable = false, length = 16)
    private String prefix;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "scopes", nullable = false, length = 200)
    private String scopes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

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
