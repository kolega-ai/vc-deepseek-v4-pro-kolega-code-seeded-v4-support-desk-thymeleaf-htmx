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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String SESSION_SECRET = "test-session-secret";
    private static final String SCOPE_SECRET = "test-scope-secret";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TEAM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final PrincipalUserRepository users = mock(PrincipalUserRepository.class);
    private final PrincipalOrgScopeRepository orgScopes = mock(PrincipalOrgScopeRepository.class);
    private final PrincipalTeamMembershipRepository memberships = mock(PrincipalTeamMembershipRepository.class);
    private final RoleLimitRepository roleLimits = mock(RoleLimitRepository.class);
    private final AuthService authService = new AuthService(
            new DecisionProperties(SESSION_SECRET, SCOPE_SECRET, "internal-key"),
            users, orgScopes, memberships, roleLimits);

    @BeforeEach
    void setUp() {
        PrincipalUser user = new PrincipalUser();
        user.setId(USER_ID);
        user.setEmail("agent@relaydesk.test");
        user.setDisplayName("Agent One");
        user.setRole("SUPPORT_AGENT");
        user.setEnabled(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        PrincipalOrgScope scope = new PrincipalOrgScope();
        scope.setUserId(USER_ID);
        scope.setOrgId(ORG_ID);
        when(orgScopes.findByUserId(USER_ID)).thenReturn(List.of(scope));

        PrincipalTeamMembership membership = new PrincipalTeamMembership();
        membership.setUserId(USER_ID);
        membership.setTeamId(TEAM_ID);
        when(memberships.findByUserId(USER_ID)).thenReturn(List.of(membership));

        RoleLimit limit = new RoleLimit();
        limit.setRole("SUPPORT_AGENT");
        limit.setLimitKey("max_claimed_tickets");
        limit.setLimitValue(20);
        when(roleLimits.findByRole("SUPPORT_AGENT")).thenReturn(List.of(limit));
    }

    private String sessionToken() {
        return TokenCodec.sign(SESSION_SECRET, "session",
                Map.of("uid", USER_ID.toString(), "sid", "session-abc"), Duration.ofHours(12));
    }

    @Test
    void validate_returnsPrincipalWithScopesAndLimits_forValidToken() {
        PrincipalResponse principal = authService.validate(sessionToken());

        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.email()).isEqualTo("agent@relaydesk.test");
        assertThat(principal.displayName()).isEqualTo("Agent One");
        assertThat(principal.role()).isEqualTo("SUPPORT_AGENT");
        assertThat(principal.enabled()).isTrue();
        assertThat(principal.orgIds()).containsExactly(ORG_ID);
        assertThat(principal.teamIds()).containsExactly(TEAM_ID);
        assertThat(principal.limits()).containsExactlyEntriesOf(Map.of("max_claimed_tickets", 20));
        assertThat(principal.sessionId()).isEqualTo("session-abc");
    }

    @Test
    void validate_throwsTokenException_forTamperedToken() {
        String token = sessionToken();
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");
        assertThatThrownBy(() -> authService.validate(tampered)).isInstanceOf(TokenException.class);
    }

    @Test
    void validate_throwsDisabledUserException_forDisabledUser() {
        PrincipalUser disabled = new PrincipalUser();
        disabled.setId(USER_ID);
        disabled.setEmail("agent@relaydesk.test");
        disabled.setDisplayName("Agent One");
        disabled.setRole("SUPPORT_AGENT");
        disabled.setEnabled(false);
        when(users.findById(USER_ID)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authService.validate(sessionToken()))
                .isInstanceOf(DisabledUserException.class);
    }

    @Test
    void validate_throwsUnknownUserException_forUnknownUser() {
        UUID ghost = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String token = TokenCodec.sign(SESSION_SECRET, "session",
                Map.of("uid", ghost.toString(), "sid", "session-xyz"), Duration.ofHours(12));
        when(users.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.validate(token)).isInstanceOf(UnknownUserException.class);
    }

    @Test
    void validate_rejectsTokenSignedWithWrongSecret() {
        String token = TokenCodec.sign("other-secret", "session",
                Map.of("uid", USER_ID.toString(), "sid", "session-abc"), Duration.ofHours(12));
        assertThatThrownBy(() -> authService.validate(token)).isInstanceOf(TokenException.class);
    }

    @Test
    void mintScopeToken_producesTokenWithRoleOrgsTeamsLimitsClaims() {
        ScopeTokenResponse response = authService.mintScopeToken(sessionToken());

        assertThat(response.expiresAt()).isGreaterThan(Instant.now().getEpochSecond());
        TokenCodec.Payload payload = TokenCodec.verify(SCOPE_SECRET, "scope", response.token());
        assertThat(payload.claim("uid")).isEqualTo(USER_ID.toString());
        assertThat(payload.claim("role")).isEqualTo("SUPPORT_AGENT");
        assertThat(payload.claim("orgs")).isEqualTo(ORG_ID.toString());
        assertThat(payload.claim("teams")).isEqualTo(TEAM_ID.toString());
        assertThat(payload.claim("limits")).isEqualTo("max_claimed_tickets=20");
    }
}
