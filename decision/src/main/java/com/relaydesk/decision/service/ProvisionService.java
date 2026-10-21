package com.relaydesk.decision.service;

import com.relaydesk.decision.domain.PrincipalOrgScope;
import com.relaydesk.decision.domain.PrincipalTeamMembership;
import com.relaydesk.decision.domain.PrincipalUser;
import com.relaydesk.decision.dto.ProvisionRequest;
import com.relaydesk.decision.dto.ProvisionUpdate;
import com.relaydesk.decision.repo.PrincipalOrgScopeRepository;
import com.relaydesk.decision.repo.PrincipalTeamMembershipRepository;
import com.relaydesk.decision.repo.PrincipalUserRepository;
import com.relaydesk.shared.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Provisioning is a privileged mutation authenticated by the channel service credential;
 * the request-time authorization path (validate/mint) never trusts client-supplied identity.
 */
@Service
public class ProvisionService {

    private final PrincipalUserRepository users;
    private final PrincipalOrgScopeRepository orgScopes;
    private final PrincipalTeamMembershipRepository memberships;

    public ProvisionService(PrincipalUserRepository users,
                            PrincipalOrgScopeRepository orgScopes,
                            PrincipalTeamMembershipRepository memberships) {
        this.users = users;
        this.orgScopes = orgScopes;
        this.memberships = memberships;
    }

    /** Upserts a principal and replaces its org scopes and team memberships. */
    @Transactional
    public PrincipalUser provision(ProvisionRequest r) {
        String role = requireValidRole(r.role());
        users.findByEmailIgnoreCase(r.email())
                .filter(existing -> !existing.getId().equals(r.userId()))
                .ifPresent(existing -> {
                    throw new DuplicateEmailException("email " + r.email() + " is already used by another user");
                });
        PrincipalUser user = users.findById(r.userId()).orElseGet(() -> {
            PrincipalUser fresh = new PrincipalUser();
            fresh.setId(r.userId());
            return fresh;
        });
        user.setEmail(r.email());
        user.setDisplayName(r.displayName());
        user.setRole(role);
        user.setEnabled(r.enabled());
        PrincipalUser saved = users.save(user);
        replaceOrgScopes(saved.getId(), r.orgIds() == null ? List.of() : r.orgIds());
        replaceTeamMemberships(saved.getId(), r.teamIds() == null ? List.of() : r.teamIds());
        return saved;
    }

    /** Partial update of a principal; null fields are left unchanged. */
    @Transactional
    public PrincipalUser update(UUID userId, ProvisionUpdate u) {
        PrincipalUser user = users.findById(userId)
                .orElseThrow(() -> new UnknownUserException("unknown user " + userId));
        if (u.displayName() != null) {
            user.setDisplayName(u.displayName());
        }
        if (u.role() != null) {
            user.setRole(requireValidRole(u.role()));
        }
        if (u.enabled() != null) {
            user.setEnabled(u.enabled());
        }
        PrincipalUser saved = users.save(user);
        if (u.orgIds() != null) {
            replaceOrgScopes(userId, u.orgIds());
        }
        if (u.teamIds() != null) {
            replaceTeamMemberships(userId, u.teamIds());
        }
        return saved;
    }

    private void replaceOrgScopes(UUID userId, List<UUID> orgIds) {
        orgScopes.deleteByUserId(userId);
        List<PrincipalOrgScope> rows = new ArrayList<>();
        for (UUID orgId : new LinkedHashSet<>(orgIds)) {
            PrincipalOrgScope scope = new PrincipalOrgScope();
            scope.setUserId(userId);
            scope.setOrgId(orgId);
            rows.add(scope);
        }
        orgScopes.saveAll(rows);
    }

    private void replaceTeamMemberships(UUID userId, List<UUID> teamIds) {
        memberships.deleteByUserId(userId);
        List<PrincipalTeamMembership> rows = new ArrayList<>();
        for (UUID teamId : new LinkedHashSet<>(teamIds)) {
            PrincipalTeamMembership membership = new PrincipalTeamMembership();
            membership.setUserId(userId);
            membership.setTeamId(teamId);
            rows.add(membership);
        }
        memberships.saveAll(rows);
    }

    private String requireValidRole(String role) {
        boolean valid = role != null && Arrays.stream(Role.values())
                .anyMatch(r -> r.name().equals(role));
        if (!valid) {
            throw new InvalidRoleException("invalid role: " + role);
        }
        return role;
    }
}
