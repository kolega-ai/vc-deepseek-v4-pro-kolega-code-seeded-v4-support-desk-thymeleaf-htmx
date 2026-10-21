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

import java.util.UUID;

/** Product entitlement of an organization ("entitlements"). */
@Entity
@Table(name = "entitlements")
@Getter
@Setter
@NoArgsConstructor
public class Entitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 120)
    private String product;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false, length = 20)
    private String status;
}
