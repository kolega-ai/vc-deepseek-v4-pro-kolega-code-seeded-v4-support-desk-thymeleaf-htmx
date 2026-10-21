package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);
}
