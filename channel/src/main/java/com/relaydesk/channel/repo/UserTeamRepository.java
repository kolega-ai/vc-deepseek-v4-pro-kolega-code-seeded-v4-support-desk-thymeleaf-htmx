package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.UserTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserTeamRepository extends JpaRepository<UserTeam, Long> {

    List<UserTeam> findByUserId(UUID userId);

    List<UserTeam> findByUserIdIn(Collection<UUID> userIds);

    List<UserTeam> findByTeamId(UUID teamId);

    void deleteByUserId(UUID userId);
}
