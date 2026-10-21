package com.relaydesk.channel.repo;

import com.relaydesk.channel.domain.UserInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserInviteRepository extends JpaRepository<UserInvite, UUID> {
    Optional<UserInvite> findByTokenHash(String tokenHash);
    boolean existsByEmailIgnoreCaseAndUsedAtIsNull(String email);
}
