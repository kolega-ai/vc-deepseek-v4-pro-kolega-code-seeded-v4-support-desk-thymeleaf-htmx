package com.relaydesk.channel.service;

import com.relaydesk.channel.domain.Escalation;
import com.relaydesk.channel.domain.KbStatus;
import com.relaydesk.channel.domain.KnowledgeArticle;
import com.relaydesk.channel.domain.Ticket;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

/**
 * Server-side authorization guards. Every page route, fragment route, download, export,
 * import and API path enforces these — the UI only hides what the server already forbids.
 */
public class AccessGuard {

    private AccessGuard() {
    }

    public static boolean isStaff(RequestPrincipal p) {
        return p != null && p.isStaff();
    }

    public static boolean isAdmin(RequestPrincipal p) {
        return p != null && p.isAdmin();
    }

    public static boolean isLead(RequestPrincipal p) {
        return p != null && p.isLead();
    }

    /**
     * Ticket visibility: staff see tickets in their teams, unassigned (team-less) tickets, and
     * tickets assigned to them; admins see everything; customers see their own org's tickets.
     */
    public static Ticket requireTicketVisible(RequestPrincipal p, Ticket t) {
        if (p == null) {
            throw new AccessDeniedException("no principal for access check");
        }
        if (p.isStaff()) {
            if (p.isAdmin()) {
                return t;
            }
            if (t.getTeamId() == null || p.teamIds().contains(t.getTeamId())) {
                return t;
            }
            if (p.userId() != null && p.userId().equals(t.getAssigneeId())) {
                return t;
            }
            throw new AccessDeniedException("ticket is outside your team scope");
        }
        if (p.orgIds().contains(t.getOrgId())) {
            return t;
        }
        throw new AccessDeniedException("ticket is outside your organization scope");
    }

    /**
     * Ticket workability (status changes, notes, internal attachments): staff within the
     * ticket's team scope, or admin. Customers are always denied.
     */
    public static Ticket requireTicketWorkable(RequestPrincipal p, Ticket t) {
        if (p == null || !p.isStaff()) {
            throw new AccessDeniedException("customers cannot modify tickets");
        }
        if (p.isAdmin() || t.getTeamId() == null || p.teamIds().contains(t.getTeamId())) {
            return t;
        }
        throw new AccessDeniedException("ticket is outside your team scope");
    }

    /** Escalation visibility: lead of the escalation's team, admin, or the requester themself. */
    public static Escalation requireEscalationVisible(RequestPrincipal p, Escalation e) {
        if (p == null) {
            throw new AccessDeniedException("no principal for access check");
        }
        if (p.isAdmin()) {
            return e;
        }
        if (p.isLead() && p.teamIds().contains(e.getTeamId())) {
            return e;
        }
        if (p.userId() != null && p.userId().equals(e.getRequesterId())) {
            return e;
        }
        throw new AccessDeniedException("escalation is outside your scope");
    }

    /**
     * Managed-team check for lead/admin actions (reassign, priority change, escalation decisions):
     * admins pass; leads require the team to be one of theirs; everyone else is denied.
     */
    public static void requireManagedTeam(RequestPrincipal p, UUID teamId) {
        if (p == null) {
            throw new AccessDeniedException("no principal for access check");
        }
        if (p.isAdmin()) {
            return;
        }
        if (p.isLead() && teamId != null && p.teamIds().contains(teamId)) {
            return;
        }
        throw new AccessDeniedException("team is not managed by you");
    }

    /** KB visibility: staff see everything; customers only published, non-internal articles. */
    public static KnowledgeArticle requireKbVisible(RequestPrincipal p, KnowledgeArticle a) {
        if (p == null || !p.isStaff()) {
            if (a.getStatus() == KbStatus.PUBLISHED && !a.isInternalOnly()) {
                return a;
            }
            throw new AccessDeniedException("article is not visible to you");
        }
        return a;
    }
}
