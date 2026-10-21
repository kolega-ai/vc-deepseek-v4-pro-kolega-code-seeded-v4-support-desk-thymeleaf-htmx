package com.relaydesk.decision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-role numeric limits enforced downstream (e.g. max_open_tickets). */
@Entity
@Table(name = "role_limits")
@Getter
@Setter
@NoArgsConstructor
public class RoleLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "role", nullable = false, length = 30)
    private String role;

    @Column(name = "limit_key", nullable = false, length = 60)
    private String limitKey;

    @Column(name = "limit_value", nullable = false)
    private int limitValue;
}
