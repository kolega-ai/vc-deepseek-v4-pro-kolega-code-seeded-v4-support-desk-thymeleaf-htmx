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

@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEndpoint {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    /** HMAC signing secret for outbound deliveries (kept per-endpoint). */
    @Column(name = "secret", nullable = false, length = 200)
    private String secret;

    /** Comma-separated event names this endpoint subscribes to. */
    @Column(name = "events", nullable = false, length = 300)
    private String events;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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
