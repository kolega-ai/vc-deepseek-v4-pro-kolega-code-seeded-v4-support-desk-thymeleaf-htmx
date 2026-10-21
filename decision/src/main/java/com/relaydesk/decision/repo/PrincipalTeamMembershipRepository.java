package com.relaydesk.decision.repo;

import com.relaydesk.decision.domain.PrincipalTeamMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrincipalTeamMembershipRepository extends JpaRepository<PrincipalTeamMembership, Long> {

    List<PrincipalTeamMembership> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
