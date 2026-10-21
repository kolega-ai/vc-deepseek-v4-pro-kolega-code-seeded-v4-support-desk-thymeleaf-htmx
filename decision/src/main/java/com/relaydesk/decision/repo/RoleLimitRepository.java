package com.relaydesk.decision.repo;

import com.relaydesk.decision.domain.RoleLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleLimitRepository extends JpaRepository<RoleLimit, Long> {

    List<RoleLimit> findByRole(String role);
}
