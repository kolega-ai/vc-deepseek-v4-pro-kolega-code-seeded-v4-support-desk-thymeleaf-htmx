package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    Optional<Team> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}
