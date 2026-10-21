package com.relaydesk.channel.service;

import com.relaydesk.channel.client.DecisionPrincipal;
import com.relaydesk.channel.error.SessionInvalidException;
import com.relaydesk.shared.Role;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The acting principal for a request, built from the decision tier's response (which is the
 * authoritative source of role, scope and limits). The session token is carried along so the
 * CoreClient can mint scope tokens for calls to the customer-core.
 */
public record RequestPrincipal(
        UUID userId,
        String email,
        String displayName,
        Role role,
        Set<UUID> orgIds,
        Set<UUID> teamIds,
        Map<String, Integer> limits,
        String sessionId,
        String sessionToken) {

    public RequestPrincipal {
        orgIds = orgIds == null ? Set.of() : Set.copyOf(orgIds);
        teamIds = teamIds == null ? Set.of() : Set.copyOf(teamIds);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public boolean hasRole(Role r) {
        return role == r;
    }

    public boolean isStaff() {
        return role == Role.SUPPORT_ADMIN || role == Role.TEAM_LEAD || role == Role.SUPPORT_AGENT;
    }

    public boolean isAdmin() {
        return role == Role.SUPPORT_ADMIN;
    }

    public boolean isLead() {
        return role == Role.TEAM_LEAD;
    }

    /** Reads a decision-provided role limit with a safe default. */
    public int limit(String key, int defaultValue) {
        Integer v = limits.get(key);
        return v == null ? defaultValue : v;
    }

    /** The same principal acting for one named organisation (partner API keys). */
    public RequestPrincipal withOrgScope(Set<UUID> scope) {
        return new RequestPrincipal(userId, email, displayName, role, scope, teamIds, limits,
                sessionId, sessionToken);
    }

    /** Builds a channel principal from a decision validate response plus the session token. */
    public static RequestPrincipal fromDecision(DecisionPrincipal d, String sessionToken) {
        Role role;
        try {
            role = Role.valueOf(d.role());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new SessionInvalidException("decision returned unknown role: " + d.role());
        }
        return new RequestPrincipal(
                d.userId(), d.email(), d.displayName(), role,
                d.orgIds() == null ? Set.of() : Set.copyOf(d.orgIds()),
                d.teamIds() == null ? Set.of() : Set.copyOf(d.teamIds()),
                d.limits(), d.sessionId(), sessionToken);
    }

    /** A pseudo-principal for scheduled/system actions (actor is recorded as "system"). */
    public static RequestPrincipal system() {
        return new RequestPrincipal(null, "system", "System",
                Role.SUPPORT_ADMIN, Set.of(), Set.of(), Map.of(), "job", null);
    }
}
