package com.relaydesk.channel.seed;

import com.relaydesk.channel.client.CoreClient;
import com.relaydesk.channel.client.DecisionClient;
import com.relaydesk.channel.config.AppProperties;
import com.relaydesk.channel.domain.AttachmentCategory;
import com.relaydesk.channel.domain.Category;
import com.relaydesk.channel.domain.Priority;
import com.relaydesk.channel.domain.Queue;
import com.relaydesk.channel.domain.SlaPolicy;
import com.relaydesk.channel.domain.Team;
import com.relaydesk.channel.domain.Ticket;
import com.relaydesk.channel.domain.TicketStatus;
import com.relaydesk.channel.domain.KnowledgeArticle;
import com.relaydesk.channel.domain.UserAccount;
import com.relaydesk.channel.dto.ContactRow;
import com.relaydesk.channel.dto.EscalateForm;
import com.relaydesk.channel.dto.EscalationResolveForm;
import com.relaydesk.channel.dto.KbArticleForm;
import com.relaydesk.channel.dto.OrgDetailRow;
import com.relaydesk.channel.dto.OrgSummaryRow;
import com.relaydesk.channel.dto.TicketCreateForm;
import com.relaydesk.channel.dto.UserForm;
import com.relaydesk.channel.dto.WebhookForm;
import com.relaydesk.channel.repo.AttachmentRepository;
import com.relaydesk.channel.repo.AuditEventRepository;
import com.relaydesk.channel.repo.CategoryRepository;
import com.relaydesk.channel.repo.EscalationRepository;
import com.relaydesk.channel.repo.KnowledgeArticleRepository;
import com.relaydesk.channel.repo.QueueRepository;
import com.relaydesk.channel.repo.SlaPolicyRepository;
import com.relaydesk.channel.repo.TeamRepository;
import com.relaydesk.channel.repo.TicketRepository;
import com.relaydesk.channel.repo.UserAccountRepository;
import com.relaydesk.channel.service.AttachmentService;
import com.relaydesk.channel.service.AuthService;
import com.relaydesk.channel.service.CreatedUser;
import com.relaydesk.channel.service.EscalationService;
import com.relaydesk.channel.service.KbService;
import com.relaydesk.channel.service.NotificationService;
import com.relaydesk.channel.service.RequestPrincipal;
import com.relaydesk.channel.service.SlaService;
import com.relaydesk.channel.service.TicketService;
import com.relaydesk.channel.service.UserAdminService;
import com.relaydesk.channel.service.WebhookService;
import com.relaydesk.shared.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic demo-data seeder ({@code new Random(42)}), gated by
 * {@code relaydesk.seed-enabled} and an empty users table. Every ticket, escalation and KB
 * state is produced by a real service/state-machine action with a real authenticated
 * principal, so all seeded audit events are real. Backdating (bootstrap-only) adjusts
 * createdAt/resolvedAt/closedAt afterwards so SLA/date filters and dashboards have history.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final UUID BOOTSTRAP_ADMIN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ADMIN_EMAIL = "admin@relaydesk.test";
    private static final String ADMIN_PASSWORD = "Admin@Relay1";
    private static final String CUSTOMER_PASSWORD = "Customer@Relay1";

    private record CustomerCandidate(String email, String displayName) {
    }

    private final AppProperties properties;
    private final UserAccountRepository users;
    private final TeamRepository teams;
    private final QueueRepository queues;
    private final CategoryRepository categories;
    private final SlaPolicyRepository slaPolicies;
    private final TicketRepository tickets;
    private final EscalationRepository escalations;
    private final AttachmentRepository attachments;
    private final KnowledgeArticleRepository kb;
    private final AuditEventRepository auditEvents;
    private final BCryptPasswordEncoder passwordEncoder;
    private final DecisionClient decision;
    private final CoreClient core;
    private final AuthService authService;
    private final UserAdminService userAdminService;
    private final TicketService ticketService;
    private final EscalationService escalationService;
    private final KbService kbService;
    private final AttachmentService attachmentService;
    private final NotificationService notificationService;
    private final SlaService slaService;
    private final WebhookService webhookService;

    public DataSeeder(AppProperties properties, UserAccountRepository users,
                      TeamRepository teams, QueueRepository queues,
                      CategoryRepository categories, SlaPolicyRepository slaPolicies,
                      TicketRepository tickets, EscalationRepository escalations,
                      AttachmentRepository attachments, KnowledgeArticleRepository kb,
                      AuditEventRepository auditEvents, BCryptPasswordEncoder passwordEncoder,
                      DecisionClient decision, CoreClient core, AuthService authService,
                      UserAdminService userAdminService, TicketService ticketService,
                      EscalationService escalationService, KbService kbService,
                      AttachmentService attachmentService, NotificationService notificationService,
                      SlaService slaService, WebhookService webhookService) {
        this.properties = properties;
        this.users = users;
        this.teams = teams;
        this.queues = queues;
        this.categories = categories;
        this.slaPolicies = slaPolicies;
        this.tickets = tickets;
        this.escalations = escalations;
        this.attachments = attachments;
        this.kb = kb;
        this.auditEvents = auditEvents;
        this.passwordEncoder = passwordEncoder;
        this.decision = decision;
        this.core = core;
        this.authService = authService;
        this.userAdminService = userAdminService;
        this.ticketService = ticketService;
        this.escalationService = escalationService;
        this.kbService = kbService;
        this.attachmentService = attachmentService;
        this.notificationService = notificationService;
        this.slaService = slaService;
        this.webhookService = webhookService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seedEnabled()) {
            log.info("seeding disabled");
            return;
        }
        if (tickets.count() > 0) {
            log.info("tickets already present — skipping seed");
            return;
        }
        if (users.count() > 0) {
            // A previous seed run was interrupted mid-way (e.g. the container was killed).
            // Failing fast with a clear message is safer than silently continuing with
            // half-seeded, inconsistent demo data.
            throw new IllegalStateException("Partial seed detected: users exist but no tickets. "
                    + "Reset the channel database (docker compose down -v) and start again.");
        }
        Random random = new Random(42);
        log.info("seeding channel demo data...");

        RequestPrincipal admin = bootstrapAdmin();

        Map<String, OrgSummaryRow> orgs = ensureOrgs(admin);
        List<UUID> orgIds = SeedData.ORG_NAMES.stream().map(n -> orgs.get(n).id()).toList();
        List<List<CustomerCandidate>> candidates = ensureContacts(admin, orgs, orgIds);

        List<UUID> teamIds = seedStaticConfig();
        List<UUID> categoryIds = seedCategories(teamIds);
        seedSlaPolicies();

        Map<String, RequestPrincipal> staff = seedStaff(admin, teamIds);

        List<List<RequestPrincipal>> customerPrincipals = seedCustomers(orgIds, candidates);

        seedTickets(random, customerPrincipals, categoryIds, teamIds, staff, admin);
        seedKb(random, staff, categoryIds, admin);
        seedWebhook(admin);

        // Two customers read their notifications so the unread bell has variety.
        notificationService.markAllRead(customerPrincipals.get(0).get(0));
        notificationService.markAllRead(customerPrincipals.get(1).get(0));

        log.info("seed complete: users={}, tickets={}, escalations={}, attachments={}, kb={}, audit={}",
                users.count(), tickets.count(), escalations.count(), attachments.count(),
                kb.count(), auditEvents.count());
    }

    // ------------------------------------------------------------------ 1. admin

    private RequestPrincipal bootstrapAdmin() {
        UserAccount admin = new UserAccount();
        admin.setId(BOOTSTRAP_ADMIN_ID);
        admin.setEmail(ADMIN_EMAIL);
        admin.setDisplayName("Maya Chen");
        admin.setRole(Role.SUPPORT_ADMIN);
        admin.setEnabled(true);
        admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        users.save(admin);
        decision.provisionUser(admin.getId(), admin.getEmail(), admin.getDisplayName(),
                admin.getRole(), List.of(), List.of(), true);
        return authService.authenticate(ADMIN_EMAIL, ADMIN_PASSWORD).principal();
    }

    // ------------------------------------------------------------------ 2. orgs & contacts

    private Map<String, OrgSummaryRow> ensureOrgs(RequestPrincipal admin) {
        Map<String, OrgSummaryRow> byName = core.allOrgs(admin).stream()
                .collect(Collectors.toMap(OrgSummaryRow::name, o -> o));
        for (int i = 0; i < SeedData.ORG_NAMES.size(); i++) {
            String name = SeedData.ORG_NAMES.get(i);
            if (!byName.containsKey(name)) {
                OrgSummaryRow created = core.createOrg(admin, name, SeedData.ORG_PLANS.get(i),
                        "1 Relay Way, Suite " + (i + 1));
                byName.put(name, created);
            }
        }
        return byName;
    }

    private List<List<CustomerCandidate>> ensureContacts(RequestPrincipal admin,
                                                         Map<String, OrgSummaryRow> orgs,
                                                         List<UUID> orgIds) {
        List<List<CustomerCandidate>> result = new ArrayList<>();
        for (int i = 0; i < SeedData.ORG_NAMES.size(); i++) {
            OrgDetailRow detail = core.org(admin, orgIds.get(i));
            List<ContactRow> contacts = new ArrayList<>(detail.contacts() == null
                    ? List.of() : detail.contacts());
            int added = 0;
            while (contacts.size() < 3) {
                int j = added++;
                String first = SeedData.FIRST_NAMES.get(i * 3 + j);
                String last = SeedData.LAST_NAMES.get(i * 3 + j);
                String email = (first + "." + last).toLowerCase(Locale.ROOT)
                        + "@" + SeedData.ORG_DOMAINS.get(i);
                ContactRow c = core.createContact(admin, orgIds.get(i), first, last, email,
                        "+1-555-01" + String.format("%02d", i * 3 + j),
                        SeedData.TITLES.get((i + j) % SeedData.TITLES.size()));
                contacts.add(c);
            }
            List<CustomerCandidate> candidates = contacts.subList(0, 3).stream()
                    .map(c -> new CustomerCandidate(c.email(), c.firstName() + " " + c.lastName()))
                    .toList();
            result.add(candidates);
        }
        if (result.get(0).stream().noneMatch(c -> c.email().equalsIgnoreCase("jordan.lee@acme.test"))) {
            log.warn("Acme's first contact is {} — the documented demo login jordan.lee@acme.test "
                    + "does not exist; use the contact email instead", result.get(0).get(0).email());
        }
        return result;
    }

    // ------------------------------------------------------------------ 3. static config

    private List<UUID> seedStaticConfig() {
        List<UUID> teamIds = new ArrayList<>();
        teamIds.add(saveTeam("Billing & Payments", "Invoicing, plans, refunds and payment issues"));
        teamIds.add(saveTeam("Platform Engineering", "API, integrations and platform reliability"));
        teamIds.add(saveTeam("Account & Security", "Access, MFA, SSO and security incidents"));

        String[] queueNames = {"Billing queue", "Platform queue", "Security queue"};
        for (int i = 0; i < 3; i++) {
            Queue q = new Queue();
            q.setName(queueNames[i]);
            q.setTeamId(teamIds.get(i));
            q.setDescription("Queue for " + SeedData.ORG_NAMES.get(i));
            queues.save(q);
        }
        return teamIds;
    }

    private UUID saveTeam(String name, String description) {
        Team t = new Team();
        t.setName(name);
        t.setDescription(description);
        teams.save(t);
        return t.getId();
    }

    private List<UUID> seedCategories(List<UUID> teamIds) {
        List<UUID> ids = new ArrayList<>();
        ids.add(saveCategory("Billing & Payments", "Billing questions, invoices and refunds", teamIds.get(0)));
        ids.add(saveCategory("Platform / Technical", "APIs, integrations and platform issues", teamIds.get(1)));
        ids.add(saveCategory("Account & Security", "Access, MFA, SSO and security incidents", teamIds.get(2)));
        ids.add(saveCategory("Onboarding & Training", "Getting started and best practices", teamIds.get(1)));
        ids.add(saveCategory("Product Feedback", "Suggestions and feature requests", null));
        return ids;
    }

    private UUID saveCategory(String name, String description, UUID defaultTeamId) {
        Category c = new Category();
        c.setName(name);
        c.setDescription(description);
        c.setDefaultTeamId(defaultTeamId);
        c.setActive(true);
        categories.save(c);
        return c.getId();
    }

    private void seedSlaPolicies() {
        savePolicy("Standard", Priority.LOW, 480, 2880);
        savePolicy("Normal", Priority.MEDIUM, 240, 1440);
        savePolicy("Elevated", Priority.HIGH, 60, 480);
        savePolicy("Critical", Priority.URGENT, 15, 120);
    }

    private void savePolicy(String name, Priority priority, int response, int resolution) {
        SlaPolicy p = new SlaPolicy();
        p.setName(name);
        p.setPriority(priority);
        p.setResponseMinutes(response);
        p.setResolutionMinutes(resolution);
        p.setBusinessHoursOnly(false);
        p.setActive(true);
        slaPolicies.save(p);
    }

    // ------------------------------------------------------------------ 4. staff

    private Map<String, RequestPrincipal> seedStaff(RequestPrincipal admin, List<UUID> teamIds) {
        Map<String, RequestPrincipal> staff = new HashMap<>();
        staff.put(ADMIN_EMAIL, admin);

        staff.put("lead.billing@relaydesk.test", createStaff(admin,
                "lead.billing@relaydesk.test", "Sofia Rossi", Role.TEAM_LEAD, teamIds.get(0), "Lead@Relay1"));
        staff.put("agent.devon@relaydesk.test", createStaff(admin,
                "agent.devon@relaydesk.test", "Devon Patel", Role.SUPPORT_AGENT, teamIds.get(1), "Agent@Relay1"));
        staff.put("agent.marcus@relaydesk.test", createStaff(admin,
                "agent.marcus@relaydesk.test", "Marcus Webb", Role.SUPPORT_AGENT, teamIds.get(0), null));
        staff.put("agent.elena@relaydesk.test", createStaff(admin,
                "agent.elena@relaydesk.test", "Elena Novak", Role.SUPPORT_AGENT, teamIds.get(0), null));
        staff.put("agent.aiko@relaydesk.test", createStaff(admin,
                "agent.aiko@relaydesk.test", "Aiko Sato", Role.SUPPORT_AGENT, teamIds.get(1), null));
        staff.put("agent.tomas@relaydesk.test", createStaff(admin,
                "agent.tomas@relaydesk.test", "Tomas Silva", Role.SUPPORT_AGENT, teamIds.get(1), null));
        staff.put("agent.nadia@relaydesk.test", createStaff(admin,
                "agent.nadia@relaydesk.test", "Nadia Haddad", Role.SUPPORT_AGENT, teamIds.get(2), null));
        staff.put("agent.rafael@relaydesk.test", createStaff(admin,
                "agent.rafael@relaydesk.test", "Rafael Okafor", Role.SUPPORT_AGENT, teamIds.get(2), null));
        staff.put("agent.leo@relaydesk.test", createStaff(admin,
                "agent.leo@relaydesk.test", "Leo Anders", Role.SUPPORT_AGENT, teamIds.get(2), null));
        staff.put("lead.platform@relaydesk.test", createStaff(admin,
                "lead.platform@relaydesk.test", "Ingrid Lindqvist", Role.TEAM_LEAD, teamIds.get(1), null));
        staff.put("lead.security@relaydesk.test", createStaff(admin,
                "lead.security@relaydesk.test", "Sam Byrne", Role.TEAM_LEAD, teamIds.get(2), null));
        return staff;
    }

    /** Creates a support user through the admin service; {@code overridePassword} replaces the
     * generated password for the documented demo accounts. Returns an authenticated principal. */
    private RequestPrincipal createStaff(RequestPrincipal admin, String email, String displayName,
                                         Role role, UUID teamId, String overridePassword) {
        CreatedUser created = userAdminService.createSupportUser(admin,
                new UserForm(email, displayName, role, List.of(teamId), true));
        String password = overridePassword != null ? overridePassword : created.initialPassword();
        if (overridePassword != null) {
            UserAccount u = users.findByEmailIgnoreCase(email).orElseThrow();
            u.setPasswordHash(passwordEncoder.encode(overridePassword));
            users.save(u);
        }
        return authService.authenticate(email, password).principal();
    }

    // ------------------------------------------------------------------ 5. customers

    private List<List<RequestPrincipal>> seedCustomers(List<UUID> orgIds,
                                                       List<List<CustomerCandidate>> candidates) {
        List<List<RequestPrincipal>> result = new ArrayList<>();
        for (int i = 0; i < orgIds.size(); i++) {
            List<RequestPrincipal> orgCustomers = new ArrayList<>();
            for (int j = 0; j < 3; j++) {
                CustomerCandidate c = candidates.get(i).get(j);
                UserAccount u = new UserAccount();
                u.setEmail(c.email());
                u.setDisplayName(c.displayName());
                u.setRole(Role.CUSTOMER_USER);
                u.setEnabled(true);
                u.setPasswordHash(passwordEncoder.encode(CUSTOMER_PASSWORD));
                users.save(u);
                decision.provisionUser(u.getId(), u.getEmail(), u.getDisplayName(),
                        u.getRole(), List.of(orgIds.get(i)), List.of(), true);
                orgCustomers.add(authService.authenticate(c.email(), CUSTOMER_PASSWORD).principal());
            }
            result.add(orgCustomers);
        }
        return result;
    }

    // ------------------------------------------------------------------ 6. tickets

    private void seedTickets(Random random, List<List<RequestPrincipal>> orgCustomers,
                             List<UUID> categoryIds, List<UUID> teamIds,
                             Map<String, RequestPrincipal> staff, RequestPrincipal admin) {
        Map<Integer, RequestPrincipal> categoryAgent = Map.of(
                0, staff.get("agent.marcus@relaydesk.test"),
                1, staff.get("agent.devon@relaydesk.test"),
                2, staff.get("agent.nadia@relaydesk.test"),
                3, staff.get("agent.aiko@relaydesk.test"),
                4, staff.get("agent.devon@relaydesk.test"));
        Map<UUID, RequestPrincipal> teamAgent = Map.of(
                teamIds.get(0), staff.get("agent.marcus@relaydesk.test"),
                teamIds.get(1), staff.get("agent.devon@relaydesk.test"),
                teamIds.get(2), staff.get("agent.nadia@relaydesk.test"));
        Map<UUID, RequestPrincipal> teamLead = Map.of(
                teamIds.get(0), staff.get("lead.billing@relaydesk.test"),
                teamIds.get(1), staff.get("lead.platform@relaydesk.test"),
                teamIds.get(2), staff.get("lead.security@relaydesk.test"));

        for (int i = 0; i < 70; i++) {
            int orgIdx = i % 10;
            int contactIdx = i % 3;
            int categoryIdx = i % 5;
            RequestPrincipal customer = orgCustomers.get(orgIdx).get(contactIdx);
            Priority priority = weightedPriority(random);

            Ticket ticket = ticketService.create(customer, new TicketCreateForm(
                    SeedData.subject(categoryIdx, i), SeedData.body(categoryIdx, i),
                    categoryIds.get(categoryIdx), priority, null));

            boolean escalated = i < 20;
            // Product Feedback (no default team) cannot be escalated as-is — route it first.
            if (escalated && ticket.getTeamId() == null) {
                ticket = ticketService.reassign(admin, ticket.getId(), null, teamIds.get(i % 3));
            }
            RequestPrincipal agent = ticket.getTeamId() == null
                    ? categoryAgent.getOrDefault(categoryIdx, staff.get("agent.devon@relaydesk.test"))
                    : teamAgent.getOrDefault(ticket.getTeamId(), staff.get("agent.devon@relaydesk.test"));
            RequestPrincipal lead = ticket.getTeamId() == null ? staff.get("lead.billing@relaydesk.test")
                    : teamLead.getOrDefault(ticket.getTeamId(), staff.get("lead.billing@relaydesk.test"));

            // Customer attachments on a deterministic 18-ticket subset.
            if (isCustomerAttachment(i)) {
                attachmentService.upload(customer, ticket.getId(),
                        SeedData.attachment(i), AttachmentCategory.CUSTOMER_ATTACHMENT);
            }
            // Internal documents on a deterministic 7-ticket subset.
            if (isInternalDocument(i)) {
                attachmentService.upload(agent, ticket.getId(),
                        SeedData.attachment(i), AttachmentCategory.INTERNAL_DOCUMENT);
            }

            if (i < 10) {
                // Escalation path: 5 stay REQUESTED, 2 go IN_REVIEW, 3 are REJECTED.
                var esc = escalate(ticket, agent, i);
                if (i >= 5 && i <= 6) {
                    escalationService.accept(lead, esc.getId());
                    escalationService.startReview(lead, esc.getId());
                } else if (i >= 7) {
                    escalationService.reject(lead, esc.getId(),
                            "Not enough impact to warrant a lead decision at this time.");
                }
            } else if (i < 20) {
                // Escalation accepted and resolved — the ticket ends RESOLVED.
                var esc = escalate(ticket, agent, i);
                escalationService.accept(lead, esc.getId());
                escalationService.resolve(lead, esc.getId(),
                        new EscalationResolveForm("Resolved after lead review with the platform team.",
                                true));
            } else if (i < 24) {
                // PENDING_INTERNAL
                ticketService.claim(agent, ticket.getId());
                ticketService.internalNote(agent, ticket.getId(), SeedData.INTERNAL_NOTES.get(i % 4));
                ticketService.changeStatus(agent, ticket.getId(), TicketStatus.PENDING_INTERNAL, null);
                ticketService.reply(agent, ticket.getId(), SeedData.REPLY_BODIES.get(i % 4));
            } else if (i < 34) {
                // PENDING_CUSTOMER (agent replied, awaiting customer)
                ticketService.claim(agent, ticket.getId());
                ticketService.reply(agent, ticket.getId(), SeedData.REPLY_BODIES.get(i % 4));
                if (i % 5 == 1 || i % 5 == 3) {
                    ticketService.internalNote(agent, ticket.getId(), SeedData.INTERNAL_NOTES.get(i % 4));
                }
                ticketService.changeStatus(agent, ticket.getId(), TicketStatus.PENDING_CUSTOMER, null);
            } else if (i < 46) {
                // OPEN with claims and a reply pair
                ticketService.claim(agent, ticket.getId());
                ticketService.reply(agent, ticket.getId(), SeedData.REPLY_BODIES.get(i % 4));
                ticketService.reply(customer, ticket.getId(), SeedData.CUSTOMER_FOLLOWUPS.get(i % 4));
                if (i % 5 == 1 || i % 5 == 3) {
                    ticketService.internalNote(agent, ticket.getId(), SeedData.INTERNAL_NOTES.get(i % 4));
                }
            } else if (i < 58) {
                // RESOLVED
                ticketService.claim(agent, ticket.getId());
                ticketService.reply(agent, ticket.getId(), SeedData.REPLY_BODIES.get(i % 4));
                ticketService.changeStatus(agent, ticket.getId(), TicketStatus.RESOLVED,
                        "Resolved — please reopen if the issue returns.");
            } else if (i < 68) {
                // CLOSED
                ticketService.claim(agent, ticket.getId());
                ticketService.reply(agent, ticket.getId(), SeedData.REPLY_BODIES.get(i % 4));
                ticketService.changeStatus(agent, ticket.getId(), TicketStatus.RESOLVED, null);
                ticketService.changeStatus(agent, ticket.getId(), TicketStatus.CLOSED, null);
            }
            // i 68..69 stay NEW (unclaimed, no activity).
        }
        backdate(random);
    }

    private com.relaydesk.channel.domain.Escalation escalate(Ticket ticket, RequestPrincipal agent, int i) {
        if (ticket.getStatus() == TicketStatus.NEW) {
            ticketService.claim(agent, ticket.getId());
        }
        boolean slaException = i % 6 == 0;
        return escalationService.create(agent, ticket.getId(), new EscalateForm(
                SeedData.ESCALATION_REASONS.get(i % 3), slaException,
                slaException ? "SLA exception: month-end close for the customer." : null));
    }

    private boolean isCustomerAttachment(int i) {
        return switch (i) {
            case 2, 5, 8, 11, 14, 17, 20, 23, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53 -> true;
            default -> false;
        };
    }

    private boolean isInternalDocument(int i) {
        return switch (i) {
            case 6, 16, 26, 36, 46, 56, 66 -> true;
            default -> false;
        };
    }

    private Priority weightedPriority(Random random) {
        double r = random.nextDouble();
        if (r < 0.30) {
            return Priority.LOW;
        }
        if (r < 0.65) {
            return Priority.MEDIUM;
        }
        if (r < 0.90) {
            return Priority.HIGH;
        }
        return Priority.URGENT;
    }

    // ------------------------------------------------------------------ backdating

    /**
     * Bootstrap-only: backdates createdAt/resolvedAt/closedAt and SLA deadlines so SLA/date
     * filters and dashboards have history, then runs the real SLA evaluation for every active
     * ticket so the seeded SLA states are produced by the production job logic. (JPA's
     * @PreUpdate refreshes updatedAt to "now" on these saves; see report.)
     */
    private void backdate(Random random) {
        Instant now = Instant.now();
        for (Ticket t : tickets.findByStatusIn(List.of(TicketStatus.RESOLVED))) {
            long resolvedDays = 1 + random.nextInt(6);
            long createdDays = 1 + random.nextInt(9);
            t.setCreatedAt(now.minus(createdDays, ChronoUnit.DAYS));
            t.setResolvedAt(now.minus(resolvedDays, ChronoUnit.DAYS));
            t.setUpdatedAt(t.getCreatedAt().plus(1, ChronoUnit.HOURS));
            tickets.save(t);
        }
        for (Ticket t : tickets.findByStatusIn(List.of(TicketStatus.CLOSED))) {
            long days = 7 + random.nextInt(34); // 7..40
            Instant createdAt = now.minus(days, ChronoUnit.DAYS);
            t.setCreatedAt(createdAt);
            t.setResolvedAt(createdAt.plus(1, ChronoUnit.DAYS));
            t.setClosedAt(createdAt.plus(2, ChronoUnit.DAYS));
            t.setUpdatedAt(t.getClosedAt());
            tickets.save(t);
        }
        // 8 active tickets backdated so they breach their SLA resolution deadline.
        List<Ticket> activeCandidates = tickets.findByStatusIn(List.of(
                TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER, TicketStatus.PENDING_INTERNAL));
        java.util.Collections.shuffle(activeCandidates, random);
        for (Ticket t : activeCandidates.stream().limit(8).toList()) {
            long days = 2 + random.nextInt(9); // 2..10
            Instant createdAt = now.minus(days, ChronoUnit.DAYS);
            t.setCreatedAt(createdAt);
            t.setUpdatedAt(createdAt);
            SlaPolicy policy = slaPolicies.findById(t.getSlaPolicyId()).orElse(null);
            long resolutionMinutes = policy == null ? 1440 : policy.getResolutionMinutes();
            Instant deadline = createdAt.plus(Duration.ofMinutes(resolutionMinutes));
            if (!deadline.isBefore(now)) {
                // Guarantee the breach: push creation back past the full resolution window.
                createdAt = now.minus(resolutionMinutes + 60, ChronoUnit.MINUTES);
                t.setCreatedAt(createdAt);
                t.setUpdatedAt(createdAt);
                deadline = createdAt.plus(Duration.ofMinutes(resolutionMinutes));
            }
            t.setSlaResolutionDeadline(deadline);
            tickets.save(t);
        }
        // Produce seeded SLA states through the real evaluation logic.
        List<Ticket> active = tickets.findByStatusIn(List.of(
                TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING_CUSTOMER,
                TicketStatus.PENDING_INTERNAL, TicketStatus.ESCALATED));
        for (Ticket t : active) {
            slaService.evaluate(t);
        }
    }

    // ------------------------------------------------------------------ 7. KB

    private void seedKb(Random random, Map<String, RequestPrincipal> staff,
                        List<UUID> categoryIds, RequestPrincipal admin) {
        List<RequestPrincipal> authors = List.of(
                admin,
                staff.get("lead.billing@relaydesk.test"),
                staff.get("lead.platform@relaydesk.test"),
                staff.get("agent.devon@relaydesk.test"),
                staff.get("agent.marcus@relaydesk.test"),
                staff.get("agent.elena@relaydesk.test"),
                staff.get("agent.aiko@relaydesk.test"),
                staff.get("agent.nadia@relaydesk.test"),
                staff.get("agent.rafael@relaydesk.test"),
                staff.get("agent.tomas@relaydesk.test"),
                staff.get("lead.security@relaydesk.test"));
        RequestPrincipal lead = staff.get("lead.billing@relaydesk.test");
        for (int j = 0; j < 20; j++) {
            RequestPrincipal author = authors.get(j % authors.size());
            boolean internalOnly = j < 8 && j % 2 == 1;
            var article = kbService.create(author, new KbArticleForm(
                    SeedData.KB_TITLES.get(j), categoryIds.get(j % 5),
                    SeedData.KB_TAGS.get(j), SeedData.KB_BODIES.get(j), internalOnly));
            if (j < 8) {
                // PUBLISHED (4 internal-only)
                kbService.submit(author, article.getId());
                kbService.publish(admin, article.getId());
                // Reload fresh: the local `article` object still holds the DRAFT status and
                // re-saving it would clobber the published state.
                KnowledgeArticle fresh = kb.findById(article.getId()).orElseThrow();
                fresh.setPublishedAt(Instant.now().minus(2 + random.nextInt(59), ChronoUnit.DAYS));
                kb.save(fresh);
            } else if (j < 12) {
                // IN_REVIEW
                kbService.submit(author, article.getId());
            } else if (j < 17) {
                // DRAFT — no transition needed
            } else {
                // ARCHIVED: DRAFT -> IN_REVIEW -> PUBLISHED -> ARCHIVED
                kbService.submit(author, article.getId());
                kbService.publish(admin, article.getId());
                KnowledgeArticle fresh = kb.findById(article.getId()).orElseThrow();
                fresh.setPublishedAt(Instant.now().minus(2 + random.nextInt(59), ChronoUnit.DAYS));
                kb.save(fresh);
                kbService.archive(lead, article.getId());
            }
        }
    }

    // ------------------------------------------------------------------ 8. webhooks

    private void seedWebhook(RequestPrincipal admin) {
        webhookService.create(admin, new WebhookForm(
                "Local integration sandbox", "http://core-mock:8082/webhooks/sink",
                "sandbox-secret", List.of("ticket.created", "ticket.status_changed",
                        "ticket.replied", "escalation.resolved")));
    }
}
