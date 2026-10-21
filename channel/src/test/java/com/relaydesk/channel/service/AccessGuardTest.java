package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Escalation;
import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.domain.KnowledgeArticle;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.shared.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessGuardTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID TEAM_1 = UUID.randomUUID();
    private static final UUID TEAM_2 = UUID.randomUUID();
    private static final UUID CUST = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID LEAD = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private RequestPrincipal customer() {
        return new RequestPrincipal(CUST, "cust@x", "Cust", Role.CUSTOMER_USER,
                Set.of(ORG_A), Set.of(), Map.of(), "s", "tok");
    }

    private RequestPrincipal agent() {
        return new RequestPrincipal(AGENT, "a@x", "Agent", Role.SUPPORT_AGENT,
                Set.of(), Set.of(TEAM_1), Map.of(), "s", "tok");
    }

    private RequestPrincipal lead() {
        return new RequestPrincipal(LEAD, "l@x", "Lead", Role.TEAM_LEAD,
                Set.of(), Set.of(TEAM_1), Map.of(), "s", "tok");
    }

    private RequestPrincipal admin() {
        return new RequestPrincipal(ADMIN, "m@x", "Admin", Role.SUPPORT_ADMIN,
                Set.of(), Set.of(), Map.of(), "s", "tok");
    }

    private Ticket ticket(UUID orgId, UUID teamId, UUID assigneeId) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setOrgId(orgId);
        t.setTeamId(teamId);
        t.setAssigneeId(assigneeId);
        return t;
    }

    @Test
    void customerSeesOwnOrgTicket() {
        assertDoesNotThrow(() -> AccessGuard.requireTicketVisible(customer(), ticket(ORG_A, TEAM_1, AGENT)));
    }

    @Test
    void customerDeniedForeignOrg() {
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireTicketVisible(customer(), ticket(ORG_B, TEAM_1, AGENT)));
    }

    @Test
    void agentSeesOwnTeamTicket() {
        assertDoesNotThrow(() -> AccessGuard.requireTicketVisible(agent(), ticket(ORG_A, TEAM_1, null)));
    }

    @Test
    void agentSeesUnassignedTicket() {
        assertDoesNotThrow(() -> AccessGuard.requireTicketVisible(agent(), ticket(ORG_B, null, null)));
    }

    @Test
    void agentSeesTicketAssignedToThem() {
        assertDoesNotThrow(() -> AccessGuard.requireTicketVisible(agent(), ticket(ORG_B, TEAM_2, AGENT)));
    }

    @Test
    void agentDeniedOtherTeamTicket() {
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireTicketVisible(agent(), ticket(ORG_B, TEAM_2, OTHER)));
    }

    @Test
    void adminSeesEverything() {
        assertDoesNotThrow(() -> AccessGuard.requireTicketVisible(admin(), ticket(ORG_B, TEAM_2, OTHER)));
    }

    @Test
    void workableStaffOnly() {
        assertDoesNotThrow(() -> AccessGuard.requireTicketWorkable(agent(), ticket(ORG_A, TEAM_1, null)));
        assertDoesNotThrow(() -> AccessGuard.requireTicketWorkable(admin(), ticket(ORG_B, TEAM_2, null)));
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireTicketWorkable(customer(), ticket(ORG_A, TEAM_1, null)));
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireTicketWorkable(agent(), ticket(ORG_A, TEAM_2, null)));
    }

    @Test
    void escalationVisibilityMatrix() {
        Escalation own = new Escalation();
        own.setTicketId(UUID.randomUUID());
        own.setRequesterId(AGENT);
        own.setTeamId(TEAM_1);
        assertDoesNotThrow(() -> AccessGuard.requireEscalationVisible(lead(), own));
        assertDoesNotThrow(() -> AccessGuard.requireEscalationVisible(admin(), own));
        assertDoesNotThrow(() -> AccessGuard.requireEscalationVisible(agent(), own)); // requester

        Escalation other = new Escalation();
        other.setTicketId(UUID.randomUUID());
        other.setRequesterId(OTHER);
        other.setTeamId(TEAM_2);
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireEscalationVisible(lead(), other));
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireEscalationVisible(agent(), other));
        assertThrows(AccessDeniedException.class,
                () -> AccessGuard.requireEscalationVisible(customer(), own));
    }

    @Test
    void managedTeamChecks() {
        assertDoesNotThrow(() -> AccessGuard.requireManagedTeam(admin(), TEAM_2));
        assertDoesNotThrow(() -> AccessGuard.requireManagedTeam(lead(), TEAM_1));
        assertThrows(AccessDeniedException.class, () -> AccessGuard.requireManagedTeam(lead(), TEAM_2));
        assertThrows(AccessDeniedException.class, () -> AccessGuard.requireManagedTeam(agent(), TEAM_1));
        assertThrows(AccessDeniedException.class, () -> AccessGuard.requireManagedTeam(customer(), TEAM_1));
    }

    @Test
    void kbVisibilityMatrix() {
        KnowledgeArticle published = new KnowledgeArticle();
        published.setStatus(KbStatus.PUBLISHED);
        published.setInternalOnly(false);
        KnowledgeArticle internal = new KnowledgeArticle();
        internal.setStatus(KbStatus.PUBLISHED);
        internal.setInternalOnly(true);
        KnowledgeArticle draft = new KnowledgeArticle();
        draft.setStatus(KbStatus.DRAFT);

        assertDoesNotThrow(() -> AccessGuard.requireKbVisible(customer(), published));
        assertThrows(AccessDeniedException.class, () -> AccessGuard.requireKbVisible(customer(), internal));
        assertThrows(AccessDeniedException.class, () -> AccessGuard.requireKbVisible(customer(), draft));
        assertDoesNotThrow(() -> AccessGuard.requireKbVisible(agent(), internal));
        assertDoesNotThrow(() -> AccessGuard.requireKbVisible(agent(), draft));
    }
}
