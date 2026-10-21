package com.relaydesk.channel.domain;

import com.relaydesk.shared.Role;
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
 * Channel-side identity: email, password hash, display name.
 * The role column is a cached copy for display only — {@code decision} is the authoritative
 * source of role, scope and limits and is consulted on every request.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Digest of the reusable pin the phone support channel asks callers for. */
    @Column(name = "callback_pin_hash", length = 64)
    private String callbackPinHash;

    /** Digest of the shared passphrase that opens the support inbox console. */
    @Column(name = "inbox_passphrase_hash", length = 128)
    private String inboxPassphraseHash;

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
