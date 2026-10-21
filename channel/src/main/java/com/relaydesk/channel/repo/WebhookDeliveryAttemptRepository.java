package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.DeliveryStatus;
import com.relaydesk.channel.domain.WebhookDeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, Long> {
    List<WebhookDeliveryAttempt> findTop20ByStatusOrderByCreatedAtAsc(DeliveryStatus status);
    List<WebhookDeliveryAttempt> findTop20ByOrderByCreatedAtDesc();
    long countByStatus(DeliveryStatus status);
}
