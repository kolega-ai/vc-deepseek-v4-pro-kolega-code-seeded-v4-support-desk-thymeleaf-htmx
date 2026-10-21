package com.relaydesk.channel.service;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.domain.UserInvite;
import com.relaydesk.channel.domain.UserTeam;
import com.relaydesk.channel.dto.InviteForm;
import com.relaydesk.channel.dto.ProfileForm;
import com.relaydesk.channel.dto.UserFilter;
import com.relaydesk.channel.dto.UserForm;
import com.relaydesk.channel.dto.UserRow;
import com.relaydesk.channel.error.CoreClientException;
import com.relaydesk.channel.error.ResourceNotFoundException;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.repo.UserInviteRepository;
import com.relaydesk.channel.repo.UserTeamRepository;
import com.relaydesk.shared.Role;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin-side user management. Decision is the authoritative source of role/scope/enabled —
 * every mutation provisions decision first and the channel table is a display cache.
 * UserTeam rows mirror the memberships this service provisions (for display + lead lookups).
 */
@Service
@Transactional
public class UserAdminService {

    private static final String BASE62 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<Role> STAFF_ROLES = Set.of(Role.SUPPORT_ADMIN, Role.TEAM_LEAD, Role.SUPPORT_AGENT);
    private static final byte[] INBOX_SALT =
            "relaydesk-inbox".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final UserAccountRepository users;
    private final TeamRepository teams;
    private final UserTeamRepository userTeams;
    private final UserInviteRepository invites;
    private final DecisionClient decision;
    private final CoreClient core;
    private final EmailOutboxService outbox;
    private final AuditService audit;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserAdminService(UserAccountRepository users, TeamRepository teams,
                            UserTeamRepository userTeams, UserInviteRepository invites,
                            DecisionClient decision, CoreClient core, EmailOutboxService outbox,
                            AuditService audit, BCryptPasswordEncoder passwordEncoder) {
        this.users = users;
        this.teams = teams;
        this.userTeams = userTeams;
        this.invites = invites;
        this.decision = decision;
        this.core = core;
        this.outbox = outbox;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    public Page<UserRow> query(UserFilter f, Pageable pageable) {
        UserFilter filter = f == null ? new UserFilter(null, null, false) : f;
        Specification<UserAccount> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (filter.q() != null) {
                String like = "%" + filter.q().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("displayName")), like)));
            }
            if (filter.role() != null) {
                ps.add(cb.equal(root.get("role"), filter.role()));
            }
            if (Boolean.TRUE.equals(filter.enabledOnly())) {
                ps.add(cb.isTrue(root.get("enabled")));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "email"));
        Page<UserAccount> page = users.findAll(spec, pr);
        List<UUID> ids = page.getContent().stream().map(UserAccount::getId).toList();
        Map<UUID, List<UUID>> memberships = ids.isEmpty() ? Map.of()
                : userTeams.findByUserIdIn(ids).stream()
                        .collect(Collectors.groupingBy(UserTeam::getUserId,
                                Collectors.mapping(UserTeam::getTeamId, Collectors.toList())));
        Map<UUID, String> teamNames = teams.findAll().stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));
        return page.map(u -> {
            List<String> names = memberships.getOrDefault(u.getId(), List.of()).stream()
                    .map(id -> teamNames.getOrDefault(id, "Unknown"))
                    .sorted().toList();
            // orgName comes from decision's org scope, which has no read endpoint — null here.
            return new UserRow(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                    u.isEnabled(), String.join(", ", names), null, u.getCreatedAt());
        });
    }

    public CreatedUser createSupportUser(RequestPrincipal admin, UserForm form) {
        requireAdmin(admin);
        if (!STAFF_ROLES.contains(form.role())) {
            throw new IllegalArgumentException("only support roles can be created here; customers come via invites");
        }
        if (users.existsByEmailIgnoreCase(form.email())) {
            throw new IllegalArgumentException("email already registered");
        }
        String initialPassword = randomBase62(12);
        UserAccount u = new UserAccount();
        u.setEmail(form.email());
        u.setDisplayName(form.displayName());
        u.setRole(form.role());
        u.setEnabled(Boolean.TRUE.equals(form.enabled()));
        u.setPasswordHash(passwordEncoder.encode(initialPassword));
        users.save(u);

        List<UUID> teamIds = form.teamIds() == null ? List.of() : form.teamIds();
        decision.provisionUser(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                List.of(), teamIds, u.isEnabled());
        replaceTeamMirror(u.getId(), teamIds);

        audit.record(admin, "user.created", "user", u.getId().toString(), u.getEmail(),
                null, "role=" + u.getRole());
        return new CreatedUser(u, initialPassword);
    }

    public UserAccount updateRole(RequestPrincipal admin, UUID userId, Role role) {
        requireAdmin(admin);
        if (role == Role.CUSTOMER_USER) {
            throw new IllegalArgumentException("customers are created via the invite flow");
        }
        UserAccount u = loadUser(userId);
        Role before = u.getRole();
        decision.updateUser(userId, role, null, null, null);
        u.setRole(role);
        users.save(u);
        audit.record(admin, "user.role_changed", "user", userId.toString(), u.getEmail(),
                before.name(), role.name());
        return u;
    }

    public UserAccount setEnabled(RequestPrincipal admin, UUID userId, boolean enabled) {
        requireAdmin(admin);
        UserAccount u = loadUser(userId);
        boolean before = u.isEnabled();
        decision.updateUser(userId, null, enabled, null, null);
        u.setEnabled(enabled);
        users.save(u);
        audit.record(admin, "user.enabled_changed", "user", userId.toString(), u.getEmail(),
                String.valueOf(before), String.valueOf(enabled));
        return u;
    }

    public InviteCreated createInvite(RequestPrincipal admin, InviteForm form) {
        requireAdmin(admin);
        String orgName;
        try {
            orgName = core.org(admin, form.orgId()).name();
        } catch (CoreClientException e) {
            if (e.getStatus() == 404) {
                throw new ResourceNotFoundException("organization not found");
            }
            throw e;
        }
        String rawToken = randomToken();
        UserInvite invite = new UserInvite();
        invite.setEmail(form.email());
        invite.setDisplayName(form.displayName());
        invite.setOrgId(form.orgId());
        invite.setOrgName(orgName);
        invite.setTokenHash(Hashing.sha256Hex(rawToken));
        invite.setInvitedById(admin.userId());
        invite.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        invites.save(invite);

        String link = "/invite/" + rawToken;
        outbox.enqueue(form.email(), "You're invited to RelayDesk",
                "You have been invited to the " + orgName + " support portal.\nAccept: " + link);
        audit.record(admin, "user.invite_created", "invite", invite.getId().toString(),
                form.email(), null, "org=" + orgName);
        return new InviteCreated(invite, link);
    }

    public Page<UserInvite> invites(Pageable pageable) {
        return invites.findAll(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public void updateProfile(RequestPrincipal p, ProfileForm form) {
        UserAccount u = loadUser(p.userId());
        u.setDisplayName(form.displayName());
        boolean hasCurrent = form.currentPassword() != null && !form.currentPassword().isBlank();
        boolean hasNew = form.newPassword() != null && !form.newPassword().isBlank();
        if (hasCurrent || hasNew) {
            if (!hasCurrent || !hasNew) {
                throw new IllegalArgumentException("both current and new password are required");
            }
            if (!passwordEncoder.matches(form.currentPassword(), u.getPasswordHash())) {
                throw new BadCredentialsException("current password does not match");
            }
            if (form.newPassword().length() < 10) {
                throw new IllegalArgumentException("new password must be at least 10 characters");
            }
            u.setPasswordHash(passwordEncoder.encode(form.newPassword()));
        }
        users.save(u);
        audit.record(p, "profile.updated", "user", u.getId().toString(), u.getEmail(),
                null, "displayName=" + u.getDisplayName());
    }

    /** Enrols the reusable pin the phone support channel asks a caller to read out. */
    public void storeCallbackPin(RequestPrincipal p, UUID userId, String pin) {
        if (pin == null || pin.length() < 4) {
            throw new IllegalArgumentException("pin must be at least 4 digits");
        }
        UserAccount u = loadUser(userId);
        u.setCallbackPinHash(org.springframework.util.DigestUtils.md5DigestAsHex(
                pin.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        users.save(u);
        audit.record(p, "user.callback_pin_set", "user", userId.toString(), u.getEmail(),
                null, "pin enrolled");
    }

    /** Checks a caller-supplied pin against the stored digest for the phone workflow. */
    public boolean matchesCallbackPin(UUID userId, String pin) {
        UserAccount u = loadUser(userId);
        if (u.getCallbackPinHash() == null || pin == null) {
            return false;
        }
        return u.getCallbackPinHash().equals(org.springframework.util.DigestUtils.md5DigestAsHex(
                pin.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Enrols the shared passphrase the support inbox console opens with. */
    public void storeInboxPassphrase(RequestPrincipal p, String passphrase) {
        if (passphrase == null || passphrase.length() < 8) {
            throw new IllegalArgumentException("passphrase must be at least 8 characters");
        }
        UserAccount u = loadUser(p.userId());
        u.setInboxPassphraseHash(kdf(passphrase));
        users.save(u);
        audit.record(p, "user.inbox_passphrase_set", "user", u.getId().toString(), u.getEmail(),
                null, "inbox passphrase enrolled");
    }

    /** Checks a supplied passphrase against the stored shared-inbox digest. */
    public boolean matchesInboxPassphrase(UUID userId, String passphrase) {
        UserAccount u = loadUser(userId);
        if (u.getInboxPassphraseHash() == null || passphrase == null) {
            return false;
        }
        return u.getInboxPassphraseHash().equals(kdf(passphrase));
    }

    private static String kdf(String passphrase) {
        try {
            javax.crypto.SecretKeyFactory skf =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            javax.crypto.spec.PBEKeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), INBOX_SALT, 1024, 128);
            byte[] out = skf.generateSecret(spec).getEncoded();
            return java.util.HexFormat.of().formatHex(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("cannot hash passphrase", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireAdmin(RequestPrincipal p) {
        if (p == null || !p.isAdmin()) {
            throw new AccessDeniedException("admin only");
        }
    }

    private UserAccount loadUser(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("user not found"));
    }

    private void replaceTeamMirror(UUID userId, Collection<UUID> teamIds) {
        userTeams.deleteByUserId(userId);
        for (UUID teamId : teamIds) {
            UserTeam m = new UserTeam();
            m.setUserId(userId);
            m.setTeamId(teamId);
            userTeams.save(m);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomBase62(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
