package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    long countByRole(com.relaydesk.shared.Role role);
}
