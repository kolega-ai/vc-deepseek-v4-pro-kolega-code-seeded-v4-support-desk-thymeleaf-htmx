package com.relaydesk.channel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Row-locked sequence used to mint human-facing ticket references (TCK-1001, ...). */
@Entity
@Table(name = "ticket_sequence")
@Getter
@Setter
@NoArgsConstructor
public class TicketSequence {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
