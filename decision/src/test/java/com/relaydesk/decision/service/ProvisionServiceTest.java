package com.relaydesk.decision.service;

import com.relaydesk.decision.domain.PrincipalOrgScope;
import com.relaydesk.decision.domain.PrincipalTeamMembership;
import com.relaydesk.decision.domain.PrincipalUser;
import com.relaydesk.decision.dto.ProvisionRequest;
import com.relaydesk.decision.dto.ProvisionUpdate;
import com.relaydesk.decision.repo.PrincipalOrgScopeRepository;
import com.relaydesk.decision.repo.PrincipalTeamMembershipRepository;
import com.relaydesk.decision.repo.PrincipalUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProvisionServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORG_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TEAM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final PrincipalUserRepository users = mock(PrincipalUserRepository.class);
    private final PrincipalOrgScopeRepository orgScopes = mock(PrincipalOrgScopeRepository.class);
    private final PrincipalTeamMembershipRepository memberships = mock(PrincipalTeamMembershipRepository.class);
    private final ProvisionService service = new ProvisionService(users, orgScopes, memberships);

    @Test
    void provision_createsUserAndReplacesScopesAndMemberships() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("customer@relaydesk.test")).thenReturn(Optional.empty());
        when(users.save(any(PrincipalUser.class))).thenAnswer(inv -> inv.getArgument(0));

        PrincipalUser created = service.provision(new ProvisionRequest(
                USER_ID, "customer@relaydesk.test", "Customer One", "CUSTOMER_USER",
                List.of(ORG_ID), List.of(TEAM_ID), true));

        assertThat(created.getId()).isEqualTo(USER_ID);
        assertThat(created.getEmail()).isEqualTo("customer@relaydesk.test");
        assertThat(created.getRole()).isEqualTo("CUSTOMER_USER");
        assertThat(created.isEnabled()).isTrue();

        verify(orgScopes).deleteByUserId(USER_ID);
        verify(memberships).deleteByUserId(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrincipalOrgScope>> scopeCaptor = ArgumentCaptor.forClass(List.class);
        verify(orgScopes).saveAll(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue()).hasSize(1);
        assertThat(scopeCaptor.getValue().get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(scopeCaptor.getValue().get(0).getOrgId()).isEqualTo(ORG_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PrincipalTeamMembership>> membershipCaptor = ArgumentCaptor.forClass(List.class);
        verify(memberships).saveAll(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue()).hasSize(1);
        assertThat(membershipCaptor.getValue().get(0).getUserId()).isEqualTo(USER_ID);
        assertThat(membershipCaptor.getValue().get(0).getTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void provision_throwsDuplicateEmail_whenEmailUsedByAnotherUser() {
        PrincipalUser other = new PrincipalUser();
        other.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        other.setEmail("customer@relaydesk.test");
        when(users.findByEmailIgnoreCase("customer@relaydesk.test")).thenReturn(Optional.of(other));

        ProvisionRequest request = new ProvisionRequest(
                USER_ID, "customer@relaydesk.test", "Customer One", "CUSTOMER_USER",
                List.of(), List.of(), true);
        assertThatThrownBy(() -> service.provision(request)).isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void provision_throwsInvalidRole_forUnknownRole() {
        ProvisionRequest request = new ProvisionRequest(
                USER_ID, "customer@relaydesk.test", "Customer One", "SUPER_ADMIN",
                List.of(), List.of(), true);
        assertThatThrownBy(() -> service.provision(request)).isInstanceOf(InvalidRoleException.class);
    }

    @Test
    void update_withAllNulls_leavesUserAndScopesUnchanged() {
        PrincipalUser existing = new PrincipalUser();
        existing.setId(USER_ID);
        existing.setEmail("customer@relaydesk.test");
        existing.setDisplayName("Customer One");
        existing.setRole("CUSTOMER_USER");
        existing.setEnabled(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(users.save(any(PrincipalUser.class))).thenAnswer(inv -> inv.getArgument(0));

        PrincipalUser updated = service.update(USER_ID, new ProvisionUpdate(null, null, null, null, null));

        assertThat(updated.getDisplayName()).isEqualTo("Customer One");
        assertThat(updated.getRole()).isEqualTo("CUSTOMER_USER");
        assertThat(updated.isEnabled()).isTrue();
        verify(orgScopes, never()).deleteByUserId(any());
        verify(memberships, never()).deleteByUserId(any());
    }

    @Test
    void update_appliesOnlyNonNullFields() {
        PrincipalUser existing = new PrincipalUser();
        existing.setId(USER_ID);
        existing.setEmail("customer@relaydesk.test");
        existing.setDisplayName("Customer One");
        existing.setRole("CUSTOMER_USER");
        existing.setEnabled(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(users.save(any(PrincipalUser.class))).thenAnswer(inv -> inv.getArgument(0));

        PrincipalUser updated = service.update(USER_ID, new ProvisionUpdate(
                "New Name", "SUPPORT_AGENT", false, List.of(ORG_ID), null));

        assertThat(updated.getDisplayName()).isEqualTo("New Name");
        assertThat(updated.getRole()).isEqualTo("SUPPORT_AGENT");
        assertThat(updated.isEnabled()).isFalse();
        verify(orgScopes).deleteByUserId(USER_ID);
        verify(memberships, never()).deleteByUserId(any());
    }

    @Test
    void update_throwsUnknownUser_whenMissing() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(USER_ID, new ProvisionUpdate(null, null, null, null, null)))
                .isInstanceOf(UnknownUserException.class);
    }

    @Test
    void update_throwsInvalidRole_forUnknownRole() {
        PrincipalUser existing = new PrincipalUser();
        existing.setId(USER_ID);
        existing.setRole("CUSTOMER_USER");
        when(users.findById(USER_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(USER_ID, new ProvisionUpdate(null, "HACKER", null, null, null)))
                .isInstanceOf(InvalidRoleException.class);
    }
}
