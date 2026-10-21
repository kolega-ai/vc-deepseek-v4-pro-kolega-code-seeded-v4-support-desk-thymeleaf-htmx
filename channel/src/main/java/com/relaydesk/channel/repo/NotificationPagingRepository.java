package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Paged notification lookups (added alongside the foundation repository). */
public interface NotificationPagingRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
