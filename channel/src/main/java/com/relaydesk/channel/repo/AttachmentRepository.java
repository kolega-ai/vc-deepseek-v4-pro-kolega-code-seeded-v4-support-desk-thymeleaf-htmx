package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {
    List<Attachment> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
    long countByTicketId(UUID ticketId);
}
