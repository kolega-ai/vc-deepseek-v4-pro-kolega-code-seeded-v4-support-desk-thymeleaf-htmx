package com.relaydesk.core.repo;

import com.relaydesk.core.domain.WebhookReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookReceiptRepository extends JpaRepository<WebhookReceipt, Long> {
}
