package com.relaydesk.decision.repo;

import com.relaydesk.decision.domain.PrincipalOrgScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrincipalOrgScopeRepository extends JpaRepository<PrincipalOrgScope, Long> {

    List<PrincipalOrgScope> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
