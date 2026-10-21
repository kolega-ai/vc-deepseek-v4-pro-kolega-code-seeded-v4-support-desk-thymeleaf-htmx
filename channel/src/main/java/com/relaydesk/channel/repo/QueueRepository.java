package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Queue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QueueRepository extends JpaRepository<Queue, UUID> {
    List<Queue> findByTeamId(UUID teamId);
}
