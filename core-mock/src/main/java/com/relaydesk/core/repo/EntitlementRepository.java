package com.relaydesk.core.repo;

import com.relaydesk.core.domain.Entitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {

    List<Entitlement> findByOrgIdOrderByIdAsc(UUID orgId);
}
