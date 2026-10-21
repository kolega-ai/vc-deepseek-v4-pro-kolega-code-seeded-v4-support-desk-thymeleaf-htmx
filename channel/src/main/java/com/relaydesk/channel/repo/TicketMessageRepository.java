package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {
    List<TicketMessage> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
    long countByTicketIdAndInternalFalse(UUID ticketId);
    boolean existsByTicketIdAndInternalFalseAndAuthorIdNot(UUID ticketId, UUID authorId);
}
