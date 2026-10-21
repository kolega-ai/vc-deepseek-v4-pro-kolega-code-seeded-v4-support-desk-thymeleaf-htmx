package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.EmailStatus;
import com.relaydesk.channel.domain.OutboxEmail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, Long> {
    List<OutboxEmail> findTop20ByStatusOrderByCreatedAtAsc(EmailStatus status);
    List<OutboxEmail> findTop20ByOrderByCreatedAtDesc();
    long countByStatus(EmailStatus status);
}
