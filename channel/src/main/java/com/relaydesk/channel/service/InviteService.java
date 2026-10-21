package com.relaydesk.channel.service;

import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.domain.UserInvite;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.repo.UserInviteRepository;
import com.relaydesk.shared.Role;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Customer invite acceptance: creates a CUSTOMER_USER bound to the invite's org and
 * provisions decision. The invite is single-use and time-bound (7 days).
 */
@Service
@Transactional
public class InviteService {

    private final UserInviteRepository invites;
    private final UserAccountRepository users;
    private final DecisionClient decision;
    private final AuditService audit;
    private final BCryptPasswordEncoder passwordEncoder;

    public InviteService(UserInviteRepository invites, UserAccountRepository users,
                         DecisionClient decision, AuditService audit,
                         BCryptPasswordEncoder passwordEncoder) {
        this.invites = invites;
        this.users = users;
        this.decision = decision;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    public UserInvite validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResourceNotFoundException("invalid invite");
        }
        UserInvite invite = invites.findByTokenHash(Hashing.sha256Hex(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("invalid invite"));
        if (invite.getUsedAt() != null) {
            throw new ResourceNotFoundException("invite already used");
        }
        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new ResourceNotFoundException("invite expired");
        }
        return invite;
    }

    public UserAccount accept(String rawToken, String password) {
        return accept(rawToken, password, null);
    }

    /**
     * Accepts an invitation. {@code orgRef} is the organisation picked on the acceptance form
     * (customers who work across several of their company's organisations can correct the
     * pre-filled one before their portal account is provisioned).
     */
    public UserAccount accept(String rawToken, String password, UUID orgRef) {
        UserInvite invite = validate(rawToken);
        if (password == null || password.length() < 10) {
            throw new IllegalArgumentException("password must be at least 10 characters");
        }
        if (users.existsByEmailIgnoreCase(invite.getEmail())) {
            throw new IllegalArgumentException("email already registered");
        }
        UserAccount u = new UserAccount();
        u.setEmail(invite.getEmail());
        u.setDisplayName(invite.getDisplayName());
        u.setRole(Role.CUSTOMER_USER);
        u.setEnabled(true);
        u.setPasswordHash(passwordEncoder.encode(password));
        users.save(u);

        UUID orgId = orgRef != null ? orgRef : invite.getOrgId();
        decision.provisionUser(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                List.of(orgId), List.of(), true);
        invite.setUsedAt(Instant.now());
        invites.save(invite);

        RequestPrincipal actor = new RequestPrincipal(u.getId(), u.getEmail(), u.getDisplayName(),
                u.getRole(), Set.of(orgId), Set.of(), Map.of(), null, null);
        audit.record(actor, "user.invite_accepted", "invite", invite.getId().toString(),
                invite.getEmail(), null, "org=" + invite.getOrgName());
        return u;
    }
}
