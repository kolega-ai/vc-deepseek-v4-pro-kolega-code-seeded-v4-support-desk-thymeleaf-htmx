package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.SlaPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {
    List<SlaPolicy> findByActiveTrueOrderByPriorityAsc();
    Optional<SlaPolicy> findFirstByPriorityAndActiveTrue(Priority priority);
    boolean existsByPriorityAndActiveTrue(Priority priority);
}
