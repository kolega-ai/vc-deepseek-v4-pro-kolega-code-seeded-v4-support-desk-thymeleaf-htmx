package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Escalation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationRepository extends JpaRepository<Escalation, UUID>, JpaSpecificationExecutor<Escalation> {
    Optional<Escalation> findByTicketId(UUID ticketId);
    List<Escalation> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
