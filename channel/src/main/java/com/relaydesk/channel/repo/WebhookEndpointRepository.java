package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {
}
