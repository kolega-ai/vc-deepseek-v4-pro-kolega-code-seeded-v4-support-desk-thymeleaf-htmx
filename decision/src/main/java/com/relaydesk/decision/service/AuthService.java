package com.relaydesk.decision.service;

import com.relaydesk.decision.config.DecisionProperties;
import com.relaydesk.decision.domain.PrincipalOrgScope;
import com.relaydesk.decision.domain.PrincipalTeamMembership;
import com.relaydesk.decision.domain.PrincipalUser;
import com.relaydesk.decision.domain.RoleLimit;
import com.relaydesk.decision.dto.PrincipalResponse;
import com.relaydesk.decision.dto.ScopeTokenResponse;
import com.relaydesk.decision.repo.PrincipalOrgScopeRepository;
import com.relaydesk.decision.repo.PrincipalTeamMembershipRepository;
import com.relaydesk.decision.repo.PrincipalUserRepository;
import com.relaydesk.decision.repo.RoleLimitRepository;
import com.relaydesk.shared.TokenCodec;
import com.relaydesk.shared.TokenException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Request-time authorization path. The acting user, role, scope and limits are loaded from
 * decision's own database for every call; nothing about identity is ever taken from the
 * request except the opaque signed token itself.
 */
@Service
public class AuthService {

    private static final Duration SCOPE_TOKEN_TTL = Duration.ofSeconds(60);

    private final DecisionProperties properties;
    private final PrincipalUserRepository users;
    private final PrincipalOrgScopeRepository orgScopes;
    private final PrincipalTeamMembershipRepository memberships;
    private final RoleLimitRepository roleLimits;

    public AuthService(DecisionProperties properties,
                       PrincipalUserRepository users,
                       PrincipalOrgScopeRepository orgScopes,
                       PrincipalTeamMembershipRepository memberships,
                       RoleLimitRepository roleLimits) {
        this.properties = properties;
        this.users = users;
        this.orgScopes = orgScopes;
        this.memberships = memberships;
        this.roleLimits = roleLimits;
    }

    /** Verifies a channel-minted session token and resolves the full principal from the database. */
    public PrincipalResponse validate(String rawToken) {
        TokenCodec.Payload payload = TokenCodec.verify(properties.sessionTokenSecret(), "session", rawToken);
        PrincipalUser user = loadEnabledUser(payload);
        List<UUID> orgIds = orgScopes.findByUserId(user.getId()).stream()
                .map(PrincipalOrgScope::getOrgId)
                .toList();
        List<UUID> teamIds = memberships.findByUserId(user.getId()).stream()
                .map(PrincipalTeamMembership::getTeamId)
                .toList();
        return new PrincipalResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole(),
                user.isEnabled(),
                orgIds,
                teamIds,
                limitsFor(user.getRole()),
                payload.claim("sid"));
    }

    /** Verifies a session token and mints a 60-second scope token carrying role/orgs/teams/limits claims. */
    public ScopeTokenResponse mintScopeToken(String rawToken) {
        TokenCodec.Payload payload = TokenCodec.verify(properties.sessionTokenSecret(), "session", rawToken);
        PrincipalUser user = loadEnabledUser(payload);
        List<UUID> orgIds = orgScopes.findByUserId(user.getId()).stream()
                .map(PrincipalOrgScope::getOrgId)
                .toList();
        List<UUID> teamIds = memberships.findByUserId(user.getId()).stream()
                .map(PrincipalTeamMembership::getTeamId)
                .toList();
        Map<String, String> claims = new LinkedHashMap<>();
        claims.put("uid", user.getId().toString());
        claims.put("role", user.getRole());
        claims.put("orgs", joinUuids(orgIds));
        claims.put("teams", joinUuids(teamIds));
        claims.put("limits", limitsFor(user.getRole()).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(",")));
        String token = TokenCodec.sign(properties.scopeTokenSecret(), "scope", claims, SCOPE_TOKEN_TTL);
        return new ScopeTokenResponse(token, Instant.now().plus(SCOPE_TOKEN_TTL).getEpochSecond());
    }

    private PrincipalUser loadEnabledUser(TokenCodec.Payload payload) {
        String uid = payload.claim("uid");
        if (uid == null || uid.isBlank()) {
            throw new TokenException("token missing uid claim");
        }
        UUID userId;
        try {
            userId = UUID.fromString(uid);
        } catch (IllegalArgumentException e) {
            throw new TokenException("token uid claim is not a UUID");
        }
        PrincipalUser user = users.findById(userId)
                .orElseThrow(() -> new UnknownUserException("unknown user " + userId));
        if (!user.isEnabled()) {
            throw new DisabledUserException("user " + user.getEmail() + " is disabled");
        }
        return user;
    }

    private Map<String, Integer> limitsFor(String role) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (RoleLimit limit : roleLimits.findByRole(role)) {
            limits.put(limit.getLimitKey(), limit.getLimitValue());
        }
        return limits;
    }

    private String joinUuids(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
