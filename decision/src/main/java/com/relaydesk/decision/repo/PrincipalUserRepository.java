package com.relaydesk.decision.repo;

import com.relaydesk.decision.domain.PrincipalUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PrincipalUserRepository extends JpaRepository<PrincipalUser, UUID> {

    Optional<PrincipalUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
