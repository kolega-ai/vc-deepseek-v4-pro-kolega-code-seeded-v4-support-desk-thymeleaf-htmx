package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByUserIdAndReadAtIsNull(UUID userId);
    List<Notification> findByUserIdAndReadAtIsNull(UUID userId);
}
