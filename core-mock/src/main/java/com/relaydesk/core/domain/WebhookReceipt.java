package com.relaydesk.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Recorded webhook delivery at the local sink ("webhook_receipts"). */
@Entity
@Table(name = "webhook_receipts")
@Getter
@Setter
@NoArgsConstructor
public class WebhookReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(columnDefinition = "text")
    private String headers;

    @Column(columnDefinition = "text")
    private String payload;
}
