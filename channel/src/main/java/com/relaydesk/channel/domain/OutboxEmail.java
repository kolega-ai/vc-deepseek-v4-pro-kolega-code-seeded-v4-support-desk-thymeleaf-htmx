package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Local email mock: outbound mail is written here and "sent" by OutboxEmailJob.
 * No external SMTP service is required.
 */
@Entity
@Table(name = "outbox_emails")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "to_address", nullable = false, length = 255)
    private String toAddress;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailStatus status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
