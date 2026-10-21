# RelayDesk — Architecture & Design Contract

RelayDesk is the customer support desk for **Lumina Cloud, Inc.** (fictional SaaS company).
It manages customer organizations, tickets, SLAs, attachments, internal notes, escalations,
knowledge base articles, imports/exports, webhooks, API keys and a customer portal.

This document is the binding design contract for every module in this repository. All code,
templates, and SQL must follow it exactly.

---

## 1. Tier & Trust Model (canonical statement)

The repository is a Gradle multi-module build with three deployable tiers plus one shared library:

| Module      | Port | Role                                                                 |
|-------------|------|----------------------------------------------------------------------|
| `channel`   | 8080 | User-facing tier: Spring Boot MVC + Thymeleaf + htmx, session auth. **Channel authenticates.** |
| `decision`  | 8081 | Authorization tier. **Decision authorizes.** It resolves an opaque signed token on every request and loads the acting user, role, scope and limits **from its own database**. It ignores any user id, role, company, scope, or limit supplied in headers, paths, queries, forms, or bodies. It also mints short-lived scope tokens for calls to `core-mock`. |
| `core-mock` | 8082 | Vendor-core stub: the external "customer-core" (CRM) system. It has its own network endpoint, its own credential (internal service key), and its own error codes. It **verifies the decision-minted scope token** and enforces the scope claim; it never trusts a raw scope parameter, and ignores identity claims supplied anywhere in the request. |

**Identity propagation is specified, not invented:**

1. A user logs in at `channel`. `channel` checks the password against its own user table
   (BCrypt) and mints an **opaque session token** (HMAC-SHA256 signed, 12 h TTL) stored in the
   HTTP session.
2. On **every** request (pages *and* htmx fragments *and* API calls), a channel filter sends
   that token to `POST /internal/auth/validate` on `decision`.
3. `decision` verifies the token signature, looks the user up **in its own database**, and
   returns the principal: role, scope (customer org ids / support team ids), limits, enabled
   flag. Channel sets the Spring Security authorities from this response, so role changes made
   in `decision` take effect immediately, and `decision` remains the authoritative source of
   role, scope, and limits.
4. When `channel` needs data from `core-mock`, it asks `decision` for a **scope token**
   (`POST /internal/auth/scope-token`, 60 s TTL). `channel` presents the scope token to
   `core-mock`. `core-mock` verifies the signature and enforces the scope claim in the token —
   e.g. a `CUSTOMER_USER` token carrying `orgs=<org-id>` may only read that organization.
   Any other org id supplied in the path is rejected with `CORE_1002 SCOPE_DENIED`.

Service-to-service calls (`channel` → `decision`, `channel` → `core-mock`) additionally present
an `X-Internal-Key` service credential (separate secrets, dev-only values injected by
docker compose; production must override).

**Data placement:** user identities + passwords and the whole support domain (tickets, replies,
notes, attachments metadata, escalations, KB, audit, notifications, outbox, webhooks, API keys)
live in `channel`'s database. Roles, scopes, team memberships, customer org bindings, and
per-role limits live in `decision`'s database. Customer organizations, contacts, and
entitlements live in `core-mock`'s database (the external CRM); `channel` stores only an
`org_id` and an `org_name` snapshot on each ticket.

**Money handling:** this product does not process monetary amounts; the fixed-precision
decimal rule does not apply (no money fields exist).

---

## 2. Repository Layout

```
.
├── settings.gradle, build.gradle, gradlew, gradle/wrapper/   (Gradle 8.10.2, Java 21)
├── shared/           plain Java library: TokenCodec, TokenException, Role
├── channel/          Spring Boot app (UI + domain), port 8080
├── decision/         Spring Boot app (authorization), port 8081
├── core-mock/        Spring Boot app (vendor-core stub), port 8082
├── db/init/00-databases.sql   creates channel/decision/core databases
├── docker-compose.yml
├── Jenkinsfile
├── scripts/smoke-check.sh
└── docs/ARCHITECTURE.md, README.md
```

Technology baseline: Java 21, Spring Boot **3.3.5**, Spring Security 6.3, Spring Data JPA,
Thymeleaf 3 + `thymeleaf-extras-springsecurity6`, htmx 2.0.4 (vendored), Flyway (Postgres
support artifact included), PostgreSQL 16. Lombok for entities only. DTOs are Java records.

---

## 3. Local Runtime Topology (docker compose)

- `db`: postgres:16-alpine. Host port `127.0.0.1:15432`. DBs: `channel`, `decision`, `core`.
  User `relay` (dev password via compose env, documented as dev-only).
- `channel`: host `http://localhost:18080`. `ATTACHMENT_DIR=/data/attachments` (named volume).
- `decision`: host `http://localhost:18081`.
- `core-mock`: host `http://localhost:18082`.

Environment variables (dev defaults live **only** in `docker-compose.yml`):

| Variable                | Used by                 | Meaning                                            |
|-------------------------|-------------------------|----------------------------------------------------|
| `SESSION_TOKEN_SECRET`  | channel (sign), decision (verify) | HMAC key for session tokens               |
| `SCOPE_TOKEN_SECRET`    | decision (sign), core-mock (verify) | HMAC key for scope tokens              |
| `DECISION_INTERNAL_KEY` | channel (send), decision (verify) | service credential channel→decision     |
| `CORE_INTERNAL_KEY`     | channel (send), core-mock (verify) | service credential channel→core-mock   |
| `DB_CHANNEL_URL`, `DB_DECISION_URL`, `DB_CORE_URL` | each app | JDBC urls |
| `DB_USER`, `DB_PASSWORD`| all three apps          | database credentials                               |
| `DECISION_URL`          | channel                 | e.g. `http://decision:8081`                       |
| `CORE_URL`              | channel                 | e.g. `http://core-mock:8082`                      |
| `ATTACHMENT_DIR`        | channel                 | local disk upload root                            |
| `APP_SEED`              | channel                 | `true` → run DataSeeder at startup (idempotent)   |

Each app fails fast at startup when a required secret is missing.

---

## 4. Shared Module: `com.relaydesk.shared`

```java
public enum Role { SUPPORT_ADMIN, SUPPORT_AGENT, TEAM_LEAD, CUSTOMER_USER }

public final class TokenException extends RuntimeException { ... }

public final class TokenCodec {
    // Compact signed token:  v1.<base64url(payload json)>.<base64url(HMAC-SHA256)>
    // payload json: {"typ":"session|scope","claims":{k:v,...},"exp":<epoch seconds>}
    public static String sign(String secret, String typ, Map<String,String> claims, Duration ttl);
    public static Payload verify(String secret, String expectedTyp, String token); // throws TokenException
    public record Payload(String typ, Map<String,String> claims, Instant expiresAt) {}
}
```

Session token claims (minted by channel): `uid=<user uuid>`, `sid=<session uuid>`.
Scope token claims (minted by decision): `uid`, `role`, `orgs=<csv uuids>`, `teams=<csv uuids>`,
`limits=<csv k=v>`.

---

## 5. Decision Service (`com.relaydesk.decision`)

### 5.1 Database (Flyway `V1__init.sql`)

```sql
principal_users(user_id uuid PK, email text unique, display_name text, role text,
                enabled boolean, created_at timestamptz, updated_at timestamptz)
principal_team_memberships(id bigserial PK, user_id uuid, team_id uuid,
                unique(user_id, team_id), created_at timestamptz)
principal_org_scopes(id bigserial PK, user_id uuid, org_id uuid, unique(user_id, org_id), created_at)
role_limits(id bigserial PK, role text, limit_key text, limit_value int, unique(role, limit_key))
```
`principal_users.role` values: `SUPPORT_ADMIN`, `SUPPORT_AGENT`, `TEAM_LEAD`, `CUSTOMER_USER`.

### 5.2 Bootstrap seed (Java `DataSeeder`, idempotent)

- Bootstrap admin principal: user id `00000000-0000-0000-0000-000000000001`, email
  `admin@relaydesk.test`, role `SUPPORT_ADMIN`, enabled — so the channel seeder's first admin
  token validates before any provisioning call has happened.
- Role limits:
  - `CUSTOMER_USER`: `max_open_tickets=10`, `max_attachment_mb=10`, `max_attachments_per_ticket=3`
  - `SUPPORT_AGENT`: `max_claimed_tickets=20`
  - `TEAM_LEAD`, `SUPPORT_ADMIN`: no limits.

### 5.3 REST API (all requests require header `X-Internal-Key`; 401 on mismatch)

| Endpoint | Body | Response |
|---|---|---|
| `POST /internal/auth/validate` | `{"token":"..."}` | 200 `{"userId","email","displayName","role","enabled","scope":{"orgIds":[],"teamIds":[]},"limits":{"k":"v"},"sessionId"}` — 401 bad/expired token, 403 disabled, 404 unknown user |
| `POST /internal/auth/scope-token` | `{"token":"..."}` | 200 `{"token","expiresAt"}` (epoch secs; TTL 60 s) |
| `POST /internal/users` | `{"userId","email","displayName","role","orgIds":[],"teamIds":[],"enabled"}` | 200/409 duplicate/422 invalid role. Provisioning. |
| `PUT /internal/users/{userId}` | `{"displayName"?,"role"?,"enabled"?,"orgIds"?,"teamIds"?}` | 200/404. Partial update; null = unchanged. |
| `GET /internal/health` | — | 200 `{"status":"UP"}` (no key required) |

Rules: `validate` and `scope-token` read **only** the signed token — any `userId`, `role`,
`scope` or `limits` fields in query params, headers, or extra body fields are ignored by
construction (the DTOs used are `TokenRequest(token)` only). Provisioning endpoints are
channel-initiated privileged mutations authenticated by the service key; channel performs
the admin authorization check before calling them (see §8.3 trust model). A comment in the
controller states this boundary.

### 5.4 Tests

- TokenCodec round-trip/expiry/tamper tests (shared).
- `validate` returns principal for a seeded user; rejects unknown/expired/tampered tokens.
- Provisioning creates/updates and ignores unknown-role payloads (422).

---

## 6. Core-Mock Service (`com.relaydesk.core`)

Vendor-core stub for the fictional external CRM "Lumina Customer Core". It behaves like an
external system: own endpoint, own credential, own error codes.

### 6.1 Database (Flyway `V1__init.sql`)

```sql
organizations(id uuid PK, name text, slug text unique, plan text, status text,  -- ACTIVE|SUSPENDED
              address text, created_at timestamptz)
contacts(id uuid PK, org_id uuid references organizations, first_name, last_name, email,
         phone, title, active boolean, created_at)
entitlements(id bigserial PK, org_id uuid references organizations, product text,
             seats int, status text)
webhook_receipts(id bigserial PK, received_at timestamptz, headers text, payload text)
```

### 6.2 Seed (idempotent, direct inserts — no state machines here)

10 organizations (ids generated once, persisted; channel discovers them at seed time via the
API): **Acme Corp** (Enterprise), **Globex Inc.** (Growth), **Initech LLC** (Starter),
**Umbrella Health** (Enterprise), **Northwind Traders** (Growth), **Stark Industries**
(Enterprise), **Wayne Enterprises** (Enterprise), **Hooli** (Growth), **Pied Piper**
(Starter), **Vandelay Industries** (Growth). 3–4 active contacts per org with realistic
names/emails (first two contacts per org are used as ticket requesters in channel seed).
Entitlements: 2–3 products per org (e.g. `lumina-cloud`, `analytics`, `sso-bundle`) with seats.

### 6.3 REST API — all endpoints require `X-Internal-Key` AND `X-Scope-Token`

The scope token is verified with `SCOPE_TOKEN_SECRET`; the role and org list are taken from
the token **only**. Path/query params never carry identity. Enforcement:

- `CUSTOMER_USER`: only read own org (token `orgs` claim); any other org id → `CORE_1002`.
  No mutations allowed (mutations → `CORE_1002`).
- `SUPPORT_AGENT` / `TEAM_LEAD`: read any org/contacts/entitlements; no mutations.
- `SUPPORT_ADMIN`: read + mutations.

| Endpoint | Notes |
|---|---|
| `GET /api/orgs?q=&page=&size=` | list; customers get only their own org |
| `GET /api/orgs/{id}` | detail incl. contacts + entitlements |
| `POST /api/orgs` | ADMIN; body `{name,plan,address}` → creates with slug |
| `PUT /api/orgs/{id}` | ADMIN; body `{name,plan,address,status}` |
| `POST /api/orgs/{id}/contacts` | ADMIN; body `{firstName,lastName,email,phone,title}` |
| `PUT /api/contacts/{id}` | ADMIN; body `{firstName,lastName,email,phone,title,active}` |
| `GET /api/entitlements?orgId=` | staff only |
| `POST /webhooks/sink` | local webhook receiver mock (no auth): stores payload + headers, returns 200. Used by channel webhook dispatch. |
| `GET /api/health` | `{"status":"UP"}` (no auth) |

Error responses: `application/problem+json` `{"code","message","detail"}`.
Codes: `CORE_1001` ORG_NOT_FOUND, `CORE_1002` SCOPE_DENIED, `CORE_1003` INVALID_TOKEN,
`CORE_1004` VALIDATION_FAILED, `CORE_1005` INVALID_SERVICE_KEY, `CORE_1006` CONTACT_NOT_FOUND.

### 6.4 Tests

- Customer scope token reading another org → 403 `CORE_1002`.
- Tampered/expired scope token → 403 `CORE_1003`; missing/wrong internal key → 401 `CORE_1005`.
- Admin token can create org; agent token cannot.

---

## 7. Channel Service (`com.relaydesk.channel`)

### 7.1 Package layout

```
config/     SecurityConfig, DecisionAuthFilter, ApiKeyAuthFilter, WebConfig,
            AppProperties, CurrentUserArgumentResolver, SchedulerConfig, AsyncConfig? (none)
domain/     19 JPA entities (§7.2)
repo/       Spring Data repositories
dto/        records: forms, filters, view rows (§7.3)
client/     DecisionClient, CoreClient (+ wire records)
service/    AccessGuard, state machines, business services (§7.4)
web/        controllers (§7.6), GlobalExceptionHandler, SecuritySupport? (no)
seed/       DataSeeder, SeedData (scenario arrays)
job/        SlaMonitorJob, TicketAutoCloseJob, OutboxEmailJob, WebhookDispatchJob
```

### 7.2 Entities (`domain`, table names)

| Entity | Table | Fields |
|---|---|---|
| `UserAccount` | `users` | id UUID PK, email unique, passwordHash, displayName, role (Role, cached copy — decision authoritative), enabled, createdAt |
| `Team` | `teams` | id UUID, name unique, description, createdAt |
| `Queue` | `queues` | id UUID, name, description, teamId FK, createdAt |
| `Category` | `categories` | id UUID, name unique, description, defaultTeamId FK nullable, active, createdAt |
| `SlaPolicy` | `sla_policies` | id UUID, name, priority (Priority), responseMinutes int, resolutionMinutes int, businessHoursOnly boolean, active, createdAt |
| `Ticket` | `tickets` | id UUID, ref text unique (e.g. `TCK-1001`), orgId UUID, orgName text (snapshot), subject, description, categoryId FK, priority (Priority), status (TicketStatus), teamId FK nullable, queueId FK nullable, assigneeId FK nullable, requesterId FK (UserAccount), slaPolicyId FK, slaResponseDeadline timestamptz, slaResolutionDeadline timestamptz, slaStatus (SlaStatus), firstResponseAt timestamptz nullable, createdAt, updatedAt, resolvedAt nullable, closedAt nullable |
| `TicketMessage` | `ticket_messages` | id UUID, ticketId FK, authorId FK, body text, internal boolean, createdAt |
| `Attachment` | `attachments` | id UUID, ticketId FK, uploaderId FK, category (AttachmentCategory), originalName, storedName (uuid on disk), contentType, sizeBytes, createdAt |
| `Escalation` | `escalations` | id UUID, ticketId FK, requesterId FK, assigneeId FK nullable (lead), teamId FK, reason text, slaException boolean, slaExceptionReason text nullable, status (EscalationStatus), resolution text nullable, createdAt, updatedAt, resolvedAt nullable |
| `KnowledgeArticle` | `kb_articles` | id UUID, title, slug unique, body text, categoryId FK nullable, tags text, status (KbStatus), internalOnly boolean, authorId FK, publishedAt nullable, createdAt, updatedAt, viewCount |
| `AuditEvent` | `audit_events` | id bigserial, occurredAt, actorId, actorEmail, action, resourceType, resourceId, resourceRef, beforeValue, afterValue, authzBasis, requestId |
| `Notification` | `notifications` | id UUID, userId FK, type, title, message, link, readAt nullable, createdAt |
| `OutboxEmail` | `outbox_emails` | id bigserial, toAddress, subject, body, status (PENDING/SENT/FAILED), sentAt, createdAt |
| `WebhookEndpoint` | `webhook_endpoints` | id UUID, name, url, secret, events (csv), enabled, createdAt |
| `WebhookDeliveryAttempt` | `webhook_delivery_attempts` | id bigserial, endpointId FK, event, payload, status (PENDING/SUCCEEDED/FAILED), responseCode, errorMessage, createdAt, deliveredAt |
| `ApiKey` | `api_keys` | id UUID, name, keyHash (SHA-256 hex), prefix (first 10 chars), ownerId FK (UserAccount), scopes (csv), enabled, expiresAt nullable, lastUsedAt nullable, createdAt |
| `PasswordResetToken` | `password_reset_tokens` | id UUID, userId FK, tokenHash (SHA-256 hex), expiresAt (now+30 min), used boolean, createdAt |
| `UserInvite` | `user_invites` | id UUID, email, displayName, orgId UUID, orgName snapshot, tokenHash, invitedById FK, expiresAt (7 d), usedAt nullable, createdAt |
| `ImportJob` | `import_jobs` | id UUID, kind (CUSTOMERS/CONTACTS), fileName, status (PROCESSING/COMPLETED/FAILED), rowsTotal, rowsOk, rowsFailed, errorReport text (CSV of row errors), createdById, createdAt, finishedAt |

Enums: `TicketStatus` NEW/OPEN/PENDING_CUSTOMER/PENDING_INTERNAL/ESCALATED/RESOLVED/CLOSED;
`Priority` LOW/MEDIUM/HIGH/URGENT; `SlaStatus` ON_TRACK/AT_RISK/BREACHED;
`EscalationStatus` REQUESTED/ACCEPTED/IN_REVIEW/RESOLVED/REJECTED;
`KbStatus` DRAFT/IN_REVIEW/PUBLISHED/ARCHIVED;
`AttachmentCategory` CUSTOMER_ATTACHMENT/INTERNAL_DOCUMENT;
`Role` (shared enum).

Audit events are **append-only**: no repository save on existing rows anywhere except insert;
no controller or endpoint modifies or deletes them (comment in `AuditService`).

### 7.3 DTOs (`dto`, records with jakarta validation)

Forms: `TicketCreateForm(subject, description, categoryId, priority, contactId?)`,
`TicketReplyForm(body)`, `TicketNoteForm(body)`, `TicketStatusForm(targetStatus, comment)`,
`TicketAssignForm(assigneeId?, teamId?)`, `TicketPriorityForm(priority)`,
`EscalateForm(reason, slaException, slaExceptionReason?)`, `EscalationResolveForm(resolution, resolveTicket)`,
`EscalationRejectForm(reason)`, `KbArticleForm(title, categoryId?, tags, body, internalOnly)`,
`KbReviewForm(reason)`, `TeamForm(name, description)`, `QueueForm(name, teamId, description)`,
`CategoryForm(name, description, defaultTeamId?)`, `SlaPolicyForm(name, priority, responseMinutes, resolutionMinutes, active, businessHoursOnly)`,
`UserForm(email, displayName, role, teamIds List<UUID>, enabled)`, `InviteForm(email, displayName, orgId)`,
`ProfileForm(displayName, currentPassword?, newPassword?)`, `ForgotPasswordForm(email)`,
`ResetPasswordForm(password, confirmPassword)`, `WebhookForm(name, url, secret, events List<String>)`,
`ApiKeyForm(name, ownerId, scopes List<String>, expiresAt LocalDate?)`,
`ApiTicketRequest(orgId?, subject, description, categoryId, priority?, contactEmail?)`.

Filters: `TicketFilter(status, priority, categoryId, teamId, queueId, assignee ("unassigned"|"mine"|"any"), overdueOnly, from LocalDate, to LocalDate, q, sort)`,
`EscalationFilter(status, teamId, q, from, to)`, `KbFilter(status, q, authorId)`,
`UserFilter(q, role, enabledOnly)`, `AuditFilter(actorEmail, action, resourceType, resourceRef, from, to)`.

View rows (records): `TicketSummaryRow`, `TicketDetailView`, `MessageRow`, `AttachmentRow`,
`SlaMilestone`, `EscalationRow`, `EscalationDetailView`, `KbRow`, `AuditRow`, `UserRow`,
`NotificationRow`, `ImportJobRow`, `WebhookAttemptRow`, `ApiKeyRow`, `OutboxRow`,
`DashboardStats`, `SlaStats`, `OrgSummaryRow` (from core), `ContactRow` (from core).
Field lists: mirror the entity fields (plus joined names such as `assigneeName`, `teamName`,
`categoryName`, `requesterEmail`), so templates can reference them directly.

### 7.4 Service layer (exact signatures — controllers and seeder call only these)

```java
public final class AccessGuard {
  boolean isStaff(RequestPrincipal p); boolean isAdmin(RequestPrincipal p); boolean isLead(RequestPrincipal p);
  Ticket requireTicketVisible(RequestPrincipal p, Ticket t);      // staff: teamId ∈ teams or teamId null; admin: any; customer: orgId ∈ orgs
  Ticket requireTicketWorkable(RequestPrincipal p, Ticket t);     // staff team-scope or admin (status changes, notes, internal attachments)
  Escalation requireEscalationVisible(RequestPrincipal p, Escalation e); // lead team-scope/admin, or requester
  Team requireManagedTeam(RequestPrincipal p, UUID teamId);       // lead: teamId ∈ teams; admin: ok
  KnowledgeArticle requireKbVisible(RequestPrincipal p, KnowledgeArticle a); // staff all; customer: published && !internalOnly
}

public final class TicketTransitions { public static void require(TicketStatus from, TicketStatus to); }
public final class EscalationTransitions { public static void require(EscalationStatus from, EscalationStatus to); }
public final class KbTransitions { public static void require(KbStatus from, KbStatus to); }

public record RequestPrincipal(UUID userId, String email, String displayName, Role role,
    Set<UUID> orgIds, Set<UUID> teamIds, Map<String,Integer> limits, String sessionId, String sessionToken) {
  public boolean hasRole(Role r); public boolean isStaff(); public boolean isAdmin(); public boolean isLead();
}

public final class AuthService {
  LoginOutcome authenticate(String email, String rawPassword);   // BCrypt check, enabled check,
      // mints session token (TokenCodec), calls DecisionClient.validate → principal.
      // Throws BadCredentialsException / DisabledAccountException / DecisionUnavailableException.
  String sessionTokenFor(UserAccount user);                      // mint (also used by ApiKey flow)
}

public final class TicketQueryService {
  // Precision #1: the ownership/scope check lives in this shared service, one call frame
  // above the controller — controllers never inline access checks.
  TicketDetailView getTicketDetail(RequestPrincipal p, UUID id);
  // Precision #4: bounded, whitelisted sort column + parameterised filter values.
  Page<TicketSummaryRow> query(RequestPrincipal p, TicketFilter f, Pageable pageable);
  List<TicketSummaryRow> recent(RequestPrincipal p, int limit);
  DashboardStats dashboard(RequestPrincipal p);
}

public final class TicketService {
  Ticket create(RequestPrincipal p, TicketCreateForm form);      // customer or staff; org from scope;
      // enforces decision limits (max_open_tickets); computes SLA deadlines; audit + notification.
  TicketMessage reply(RequestPrincipal p, UUID ticketId, String body);   // public reply; reopens CLOSED; clears PENDING_CUSTOMER; sets firstResponseAt
  TicketMessage internalNote(RequestPrincipal p, UUID ticketId, String body); // staff only
  Ticket changeStatus(RequestPrincipal p, UUID ticketId, TicketStatus target, String comment); // transition guard + audit + notifications
  Ticket claim(RequestPrincipal p, UUID ticketId);               // agent: team-scope, unassigned, NEW→OPEN
  Ticket reassign(RequestPrincipal p, UUID ticketId, UUID assigneeId, UUID teamId); // lead team-scope/admin
  Ticket changePriority(RequestPrincipal p, UUID ticketId, Priority priority);      // lead team-scope/admin
  void delete(RequestPrincipal p, UUID ticketId);                // admin only
  void markCustomerReplied(Ticket t);                            // PENDING_INTERNAL? no — sets status transitions on reply (see reply)
}

public final class AttachmentService {
  Attachment upload(RequestPrincipal p, UUID ticketId, MultipartFile file, AttachmentCategory category);
  StoredAttachment open(RequestPrincipal p, UUID attachmentId);  // guard (internal doc → staff only; org scope) + per-download audit
  List<AttachmentRow> listForTicket(RequestPrincipal p, UUID ticketId);
  public record StoredAttachment(Attachment meta, Path path) {}
}

public final class EscalationService {
  Escalation create(RequestPrincipal p, UUID ticketId, EscalateForm form); // ticket → ESCALATED (from OPEN/PENDING_*), escalation REQUESTED; assignee = first lead of ticket team
  EscalationDetailView detail(RequestPrincipal p, UUID id);
  Page<EscalationRow> query(RequestPrincipal p, EscalationFilter f, Pageable pageable);
  Escalation accept(RequestPrincipal p, UUID id);     // REQUESTED→ACCEPTED (lead team/admin)
  Escalation reject(RequestPrincipal p, UUID id, String reason);   // REQUESTED|IN_REVIEW→REJECTED; ticket ESCALATED→OPEN
  Escalation startReview(RequestPrincipal p, UUID id); // ACCEPTED→IN_REVIEW
  Escalation resolve(RequestPrincipal p, UUID id, EscalationResolveForm form); // →RESOLVED; ticket ESCALATED→RESOLVED (resolveTicket=true) else →OPEN
}

public final class KbService {
  Page<KbRow> staffQuery(RequestPrincipal p, KbFilter f, Pageable pageable);
  Page<KbRow> publicSearch(String q, Pageable pageable);          // PUBLISHED && !internalOnly
  KnowledgeArticle article(RequestPrincipal p, UUID id);          // visibility guard; +viewCount on customer view
  KnowledgeArticle publicBySlug(String slug);
  KnowledgeArticle create(RequestPrincipal p, KbArticleForm form);       // staff → DRAFT
  KnowledgeArticle update(RequestPrincipal p, UUID id, KbArticleForm form); // author of DRAFT, or lead/admin
  KnowledgeArticle submit(RequestPrincipal p, UUID id);           // DRAFT→IN_REVIEW (author)
  KnowledgeArticle publish(RequestPrincipal p, UUID id);          // IN_REVIEW→PUBLISHED (lead/admin)
  KnowledgeArticle sendBack(RequestPrincipal p, UUID id, String reason);  // IN_REVIEW→DRAFT (lead/admin)
  KnowledgeArticle archive(RequestPrincipal p, UUID id);          // PUBLISHED→ARCHIVED (lead/admin)
  KnowledgeArticle restore(RequestPrincipal p, UUID id);          // ARCHIVED→DRAFT (lead/admin)
}

public final class UserAdminService {
  Page<UserRow> query(UserFilter f, Pageable pageable);
  UserAccount createSupportUser(RequestPrincipal admin, UserForm form);  // + DecisionClient.provisionUser + audit
  UserAccount updateRole(RequestPrincipal admin, UUID userId, Role role);      // decision is authoritative; audit
  UserAccount setEnabled(RequestPrincipal admin, UUID userId, boolean enabled); // decision; audit; disables sessions
  UserInvite createInvite(RequestPrincipal admin, InviteForm form);   // outbox email with accept link
  Page<UserInvite> invites(Pageable pageable);
  void updateProfile(RequestPrincipal p, ProfileForm form);      // display name; password change if both passwords given
}

public final class InviteService {
  UserInvite validate(String rawToken);                          // unexpired, unused
  UserAccount accept(String rawToken, String password);          // creates CUSTOMER_USER bound to invite org; provisions decision
}

public final class PasswordResetService {
  void request(String email);                                    // always 200-ish; token 30 min; outbox email (preview link shown in UI — email is a local mock)
  boolean isValid(String rawToken);
  void reset(String rawToken, String newPassword);               // single use; audit
}

public final class ImportService {
  byte[] template(ImportKind kind);                              // CSV template
  ImportJob run(RequestPrincipal admin, ImportKind kind, MultipartFile csv); // parse + validate + create via CoreClient (admin principal); per-row errors
  Page<ImportJobRow> jobs(Pageable pageable);
  byte[] errorReport(UUID jobId);
}

public final class ExportService {
  CsvFile tickets(RequestPrincipal p, TicketFilter f);           // scoped rows; internal notes excluded
  CsvFile sla(RequestPrincipal p, SlaReportFilter f);            // lead/admin
  CsvFile escalations(RequestPrincipal p, EscalationFilter f);   // lead/admin; escalation reason excluded for non-lead? (lead/admin only)
  CsvFile audit(RequestPrincipal admin, AuditFilter f);          // admin; view-only
  CsvFile customerTickets(RequestPrincipal customer);            // own org, public fields only
  public record CsvFile(String name, String content) {}
}
// Every export is a privileged action → one audit event each (resource class + count).
```

```java
public final class AuditService {
  void record(RequestPrincipal p, String action, String resourceType, String resourceId,
              String resourceRef, String before, String after); // append-only insert
  void recordRead(RequestPrincipal p, String resourceClass, int count); // one event per request (never per record)
  Page<AuditRow> query(AuditFilter f, Pageable pageable);
  List<AuditRow> forTicket(UUID ticketId);
}

public final class NotificationService {
  void notifyUser(UUID userId, String type, String title, String message, String link);
  Page<NotificationRow> forUser(RequestPrincipal p, Pageable pageable);
  long unread(RequestPrincipal p);
  void markRead(RequestPrincipal p, UUID id); void markAllRead(RequestPrincipal p);
}

public final class EmailOutboxService { void enqueue(String to, String subject, String body); Page<OutboxRow> recent(Pageable p); }

public final class WebhookService {
  Page<WebhookEndpoint> endpoints(Pageable p); List<WebhookEndpoint> all();
  WebhookEndpoint create(RequestPrincipal admin, WebhookForm f); // audit
  WebhookEndpoint update(RequestPrincipal admin, UUID id, WebhookForm f); // audit
  void delete(RequestPrincipal admin, UUID id);                  // audit
  void publish(String event, String payloadJson);                // public fields only — NEVER internal notes/escalation reasons
  Page<WebhookAttemptRow> attempts(Pageable p);
  void fireTest(RequestPrincipal admin, UUID id);
}

public final class ApiKeyService {
  ApiKey create(RequestPrincipal admin, ApiKeyForm f);           // returns entity + generated plaintext once
  Page<ApiKeyRow> keys(Pageable p);
  void revoke(RequestPrincipal admin, UUID id);
  Optional<ApiKey> authenticate(String rawKey);                  // SHA-256 hash lookup, enabled, not expired
}

public final class SlaService {
  void evaluate(Ticket t);                                       // deadlines vs now → ON_TRACK/AT_RISK/BREACHED; breach → notification + audit
  SlaStats stats(SlaReportFilter f);
  Page<TicketSummaryRow> overdue(RequestPrincipal p, Pageable pageable);
}

public final class FileStorageService {
  StoredFile store(MultipartFile file);                          // uuid name on disk, size/type allowlist enforced
  // Precision #5: downloads resolve through a canonicalised base directory:
  Path resolve(String storedName);   // base.toRealPath() → resolve → normalize → toRealPath → startsWith(base) check
  public record StoredFile(String storedName, long sizeBytes, String contentType, String originalName) {}
}

public final class ReferenceDataService {
  // Precision #3: MD5 used ONLY as an ETag for a reference-data response (non-cryptographic).
  List<CategoryRef> activeCategories();
  String categoriesEtag(List<CategoryRef> data);                 // hex MD5 of canonical JSON
}

@Component public class AssetHelper {
  // Precision #2: java.util.Random used only for a cache-busting suffix on static assets
  // (non-cryptographic; a static asset version does not need unpredictability).
  public String bust(String path);   // returns path + "?v=" + random suffix (stable per process start)
}
```

### 7.5 Clients

```java
public final class DecisionClient {
  DecisionPrincipal validate(String sessionToken);   // throws DecisionUnavailableException on 5xx/timeout
  String mintScopeToken(String sessionToken);
  void provisionUser(UUID userId, String email, String displayName, Role role,
                     List<UUID> orgIds, List<UUID> teamIds, boolean enabled);
  void updateUser(UUID userId, Role role, Boolean enabled, List<UUID> orgIds, List<UUID> teamIds);
}
// DecisionPrincipal = wire DTO mirrored from decision's response JSON.

public final class CoreClient {
  // Every call mints a fresh scope token via DecisionClient using the principal's session
  // token, then presents X-Internal-Key + X-Scope-Token. Throws CoreClientException with the
  // core error code on problem+json responses.
  Page<OrgSummaryRow> listOrgs(RequestPrincipal p, String q, int page, int size);
  OrgDetailRow org(RequestPrincipal p, UUID orgId);             // + contacts + entitlements
  List<OrgSummaryRow> allOrgs(RequestPrincipal p);
  OrgSummaryRow createOrg(RequestPrincipal p, String name, String plan, String address);
  void updateOrg(RequestPrincipal p, UUID orgId, String name, String plan, String address, String status);
  ContactRow createContact(RequestPrincipal p, UUID orgId, String firstName, String lastName, String email, String phone, String title);
  void updateContact(RequestPrincipal p, UUID contactId, String firstName, String lastName, String email, String phone, String title, boolean active);
}
```

### 7.6 Request pipeline (security)

1. `SecurityFilterChain`: csrf enabled (CookieCsrfTokenRepository.withHttpOnlyFalse NOT used —
   standard session-based CSRF token; htmx requests read the token from
   `<meta name="_csrf" content=...>` and send `X-CSRF-TOKEN` via `htmx:configRequest` hook in
   `app.js`; regular forms get the hidden input automatically via `th:action`).
   `permitAll`: `/`, `/login`, `/kb/**` (public KB pages), `/forgot-password`, `/reset-password`,
   `/invite/**`, `/error`, `/css/**`, `/js/**`, `/fonts/**`, `/vendor/**`, `/favicon.ico`,
   `/actuator/health`, `/api/v1/**` (stateless Bearer filter applies there).
   Everything else: `authenticated()` + `@EnableMethodSecurity` (`@PreAuthorize` on controllers).
   Session cookie: httpOnly, SameSite=Lax; session fixation protection on; logout POST-only.
2. `ApiKeyAuthFilter` (before authentication, only `/api/v1/**`): reads `Authorization: Bearer`,
   `ApiKeyService.authenticate` → looks up owner `UserAccount` → sets request attribute
   `apiKeyOwner` (userId, keyId). No session created.
3. `DecisionAuthFilter` (after `SecurityContext` establishment, every non-permitAll request):
   - obtains the session token (from HTTP session attribute, or — for `/api/v1/**` — mints a
     short-lived token for `apiKeyOwner` via `AuthService.sessionTokenFor`);
   - calls `DecisionClient.validate(token)` on **every request** (no caching across requests);
   - on success: builds `RequestPrincipal`, stores it as request attribute `requestPrincipal`,
     and **replaces the SecurityContext authorities** with decision-derived authorities
     (`ROLE_<Role>`), so every `@PreAuthorize` check reflects decision's database;
   - decision returns disabled/unknown → session invalidated, redirect to `/login?expired`;
   - decision unreachable → 503 with friendly error page (decision is the authorization
     authority; channel fails closed).
4. `CurrentUserArgumentResolver`: `@CurrentUser RequestPrincipal` in controller methods.

### 7.7 Route table (controllers → templates, model attributes, fragments)

Role codes: C=customer, A=agent, L=lead, M=admin, S=any staff, P=public.

| Method+Path | Roles | Controller / view or action | Model attributes |
|---|---|---|---|
| `GET /` | P | `home` (landing, KB teaser, login CTA) | `kb` (top published), `orgs`? no |
| `GET /login`, `POST /login` | P | Spring formLogin → custom success handler redirects by role | `error`, `expired` |
| `GET /forgot-password`, `POST /forgot-password` | P | `forgot-password` (on POST: outbox mock → show preview link on next screen `reset-sent`) | `form`, `previewLink?` |
| `GET /reset-password`, `POST /reset-password` | P | `reset-password` (validates single-use token) | `form`, `valid`, `error` |
| `GET /invite/{token}`, `POST /invite/{token}` | P | `invite-accept` (creates customer account, provisions decision) | `invite`, `form`, `error` |
| `GET /kb` | P | `kb/index` public search/list (published, non-internal) | `page`, `q` |
| `GET /kb/search` | P | fragment `fragments :: kbResults` (hx-get, debounced) | `page`, `q` |
| `GET /kb/{slug}` | P | `kb/article` public article | `article`, `related` |
| `GET /portal` | C | `portal/dashboard` | `stats`, `recent`, `unread`, `kb` |
| `GET /portal/tickets` | C | `portal/tickets` org list + filters | `page`, `filter`, `categories` |
| `GET /portal/tickets/fragment` | C | fragment `fragments :: portalTicketRows` | `page`, `filter` |
| `GET /portal/tickets/new` | C | `portal/ticket-new` | `form`, `categories`, `contacts` (core), `slaHint` |
| `POST /portal/tickets` | C | create → redirect detail | `form` |
| `GET /portal/tickets/{id}` | C | `portal/ticket-detail` (public messages, attachments, status, timeline) | `detail` |
| `POST /portal/tickets/{id}/reply` | C | reply → redirect back | `form` |
| `POST /portal/tickets/{id}/attachments` | C | upload → redirect back | `file` |
| `GET /portal/tickets/{id}/attachments/{attId}` | C | download (guard + audit) | — |
| `GET /portal/tickets/export` | C | CSV (own org, public fields) | — |
| `GET /portal/profile`, `POST /portal/profile` | C | `portal/profile` | `form` |
| `GET /desk` | S | `desk/dashboard` | `stats`, `recent`, `overdue`, `escalations` (lead), `unread` |
| `GET /desk/queue` | S | `desk/queue` agent queue + filters | `page`, `filter`, `teams`, `categories`, `assignees` |
| `GET /desk/queue/fragment` | S | fragment `fragments :: queueTable` | `page`, `filter` |
| `GET /desk/tickets/{id}` | S | `desk/ticket-detail` (thread incl. internal notes, attachments, escalation panel, SLA timeline, audit history) | `detail`, `statusForm`, `noteForm`, `replyForm`, `escalateForm`, `assignForm`, `priorityForm`, `agents` |
| `POST /desk/tickets/{id}/reply` | S | reply → redirect | `replyForm` |
| `POST /desk/tickets/{id}/note` | S | internal note → redirect | `noteForm` |
| `POST /desk/tickets/{id}/status` | S | transition → redirect | `statusForm` |
| `POST /desk/tickets/{id}/claim` | A | claim → redirect | — |
| `POST /desk/tickets/{id}/assign` | L,M | reassign → redirect | `assignForm` |
| `POST /desk/tickets/{id}/priority` | L,M | → redirect | `priorityForm` |
| `POST /desk/tickets/{id}/attachments` | S | upload (either category) → redirect | `file`, `category` |
| `GET /desk/tickets/{id}/attachments/{attId}` | S | download (guard + audit) | — |
| `POST /desk/tickets/{id}/escalate` | S | escalate → redirect escalation detail | `escalateForm` |
| `GET /desk/tickets/{id}/timeline/fragment` | S | fragment `fragments :: timeline` | `detail` |
| `GET /desk/escalations` | L,M (A sees own) | `desk/escalations` queue + filters | `page`, `filter`, `teams` |
| `GET /desk/escalations/fragment` | L,M | fragment `fragments :: escalationRows` | `page`, `filter` |
| `GET /desk/escalations/{id}` | L,M,(A requester) | `desk/escalation-detail` | `detail`, `resolveForm`, `rejectForm` |
| `POST /desk/escalations/{id}/accept` | L,M | → redirect | — |
| `POST /desk/escalations/{id}/review` | L,M | → redirect | — |
| `POST /desk/escalations/{id}/reject` | L,M | → redirect | `rejectForm` |
| `POST /desk/escalations/{id}/resolve` | L,M | → redirect | `resolveForm` |
| `GET /desk/reports` | L,M,A | `desk/reports` SLA dashboard + filters | `stats`, `overdue`, `filter`, `teams` |
| `GET /desk/reports/export/sla` | L,M | CSV | `filter` |
| `GET /desk/reports/export/tickets` | S | CSV (scoped; no internal notes) | `filter` |
| `GET /desk/reports/export/escalations` | L,M | CSV | `filter` |
| `GET /desk/kb` | S | `desk/kb-list` (all statuses + filters) | `page`, `filter` |
| `GET /desk/kb/fragment` | S | fragment `fragments :: kbRows` | `page`, `filter` |
| `GET /desk/kb/new` | S | `desk/kb-editor` (create) | `form`, `categories` |
| `POST /desk/kb` | S | create draft → redirect editor | `form` |
| `GET /desk/kb/{id}/edit` | S | `desk/kb-editor` (edit) | `form`, `article`, `categories` |
| `POST /desk/kb/{id}` | S | update → redirect | `form` |
| `POST /desk/kb/{id}/submit` | S(author) | → redirect | — |
| `POST /desk/kb/{id}/publish` | L,M | → redirect | — |
| `POST /desk/kb/{id}/send-back` | L,M | → redirect | `reviewForm` |
| `POST /desk/kb/{id}/archive` | L,M | → redirect | — |
| `POST /desk/kb/{id}/restore` | L,M | → redirect | — |
| `GET /admin` | M | `admin/dashboard` (counts, health, outbox, webhook health) | `stats`, `outbox`, `attempts`, `system` |
| `GET /admin/users` | M | `admin/users` | `page`, `filter`, `roles`, `teams` |
| `GET /admin/users/fragment` | M | fragment `fragments :: userRows` | `page`, `filter` |
| `POST /admin/users` | M | create support user → redirect | `userForm` |
| `POST /admin/users/{id}/role` | M | role change → redirect | `roleForm` |
| `POST /admin/users/{id}/enabled` | M | enable/disable → redirect | `enabledForm` |
| `POST /admin/users/invite` | M | invite customer → redirect | `inviteForm`, `orgs` (core) |
| `GET /admin/invites` | M | `admin/invites` | `page` |
| `GET /admin/teams` | M | `admin/teams` (teams + member counts + queues) | `teams`, `queues`, `teamForm`, `queueForm` |
| `POST /admin/teams`, `POST /admin/teams/{id}`, `POST /admin/teams/{id}/delete` | M | CRUD → redirect | `teamForm` |
| `POST /admin/queues`, `POST /admin/queues/{id}`, `POST /admin/queues/{id}/delete` | M | CRUD → redirect | `queueForm` |
| `GET /admin/categories` | M | `admin/categories` | `categories`, `teams`, `categoryForm` |
| `POST /admin/categories`, `POST /admin/categories/{id}`, `POST /admin/categories/{id}/delete` | M | CRUD (delete = soft, active=false) | `categoryForm` |
| `GET /admin/sla` | M | `admin/sla` (policies + preview) | `policies`, `slaForm` |
| `POST /admin/sla`, `POST /admin/sla/{id}`, `POST /admin/sla/{id}/delete` | M | CRUD (delete guarded: policy in use → reject) | `slaForm` |
| `GET /admin/imports` | M | `admin/imports` (upload + template + history) | `jobs`, `importForm` |
| `POST /admin/imports/run` | M | run import → redirect result | `kind`, `file` |
| `GET /admin/imports/template/{kind}` | M | CSV template download | — |
| `GET /admin/imports/{id}/errors` | M | error report CSV download | — |
| `GET /admin/exports` | M | `admin/exports` (all exports + filters) | `filter`, `lastExports`? no |
| `GET /admin/exports/tickets` | M | CSV (all rows, no internal notes) | `filter` |
| `GET /admin/exports/sla` | M | CSV | `filter` |
| `GET /admin/exports/escalations` | M | CSV | `filter` |
| `GET /admin/exports/audit` | M | CSV | `filter` |
| `GET /admin/webhooks` | M | `admin/webhooks` (endpoints + recent attempts) | `endpoints`, `attempts`, `webhookForm` |
| `GET /admin/webhooks/fragment` | M | fragment `fragments :: webhookAttempts` | `attempts` |
| `POST /admin/webhooks`, `POST /admin/webhooks/{id}`, `POST /admin/webhooks/{id}/delete`, `POST /admin/webhooks/{id}/test` | M | CRUD+test → redirect | `webhookForm` |
| `GET /admin/api-keys` | M | `admin/api-keys` | `keys`, `apiKeyForm`, `users` |
| `POST /admin/api-keys`, `POST /admin/api-keys/{id}/revoke` | M | → redirect (new key plaintext shown once) | `apiKeyForm` |
| `GET /admin/audit` | M | `admin/audit` viewer + filters | `page`, `filter` |
| `GET /admin/audit/fragment` | M | fragment `fragments :: auditRows` | `page`, `filter` |
| `GET /admin/settings` | M | `admin/settings` (limits from decision, outbox mail viewer, storage info) | `limits`, `outbox`, `storage` |
| `GET /admin/orgs` | M | `admin/orgs` (customer directory via core-mock, search) | `page`, `q` |
| `GET /admin/orgs/{id}` | M | `admin/org-detail` (contacts + entitlements) | `org` |
| `POST /admin/orgs`, `POST /admin/orgs/{id}`, `POST /admin/orgs/{id}/contacts`, `POST /admin/contacts/{id}` | M | CRUD via core-mock → redirect | forms |
| `GET /notifications/fragment` | S,C | fragment `fragments :: notificationList` (dropdown) | `page`, `unread` |
| `POST /notifications/{id}/read`, `POST /notifications/read-all` | S,C | hx → fragment | — |
| `GET /api/v1/tickets`, `GET /api/v1/tickets/{ref}`, `POST /api/v1/tickets` | Bearer | JSON API (§7.8) | — |
| `GET /desk/ref/categories` | S | JSON reference data + **MD5 ETag** (precision #3) | — |
| `GET /search/ticket` | S | topbar ticket lookup (ref/q) → redirect to detail or queue search | `q` |

Fragment endpoints are **real endpoints**: each is authenticated, authorized, and scoped
identically to its page route (the `DecisionAuthFilter` runs on all of them; services apply
the same guards). Templates use Thymeleaf default escaping everywhere; **no `th:utext` on
user content** (KB bodies and messages render as escaped text with `white-space: pre-wrap`).

### 7.8 API v1 + API keys + webhooks

- API keys: created by admin, owned by a support user; scopes `tickets:create`, `tickets:read`;
  optional expiry; key shown once at creation (SHA-256 at rest). `Authorization: Bearer`.
- `POST /api/v1/tickets` (scope `tickets:create`): body `{orgId?, subject, description,
  categoryId, priority?, contactEmail?}`. For CUSTOMER_USER owners the org is forced to the
  owner's scope org; staff owners may pass `orgId`. → 201 `{id, ref, status, slaResolutionDeadline}`.
- `GET /api/v1/tickets/{ref}` (scope `tickets:read`): public fields + public messages only.
- `GET /api/v1/tickets?status=&q=&limit=&page=`: scoped list (customers: own org; staff: scope).
- **Internal notes and escalation reasons never appear** in API responses, webhook payloads,
  customer exports, or customer notifications.
- Webhooks: admin-managed endpoints with URL, secret, subscribed events
  (`ticket.created`, `ticket.status_changed`, `ticket.replied`, `escalation.resolved`).
  Payload: `{event, occurredAt, ref, subject, status, priority, orgName, actor}` — public
  fields only. Signed with `X-RelayDesk-Signature: sha256=<hmac hex>` (secret per endpoint).
  Delivery attempts recorded; `WebhookDispatchJob` performs deliveries (RestClient, 5 s
  timeout); the seeded endpoint points at `http://core-mock:8082/webhooks/sink`.

### 7.9 Scheduled jobs (in-process `@Scheduled`, no external broker)

- `SlaMonitorJob` (every 60 s): evaluates SLA status for active tickets (NEW/OPEN/PENDING_*/ESCALATED);
  breach transition → notification (assignee/requester-lead) + audit event; `at_risk` is
  silent. Also refreshes deadlines when policies change.
- `TicketAutoCloseJob` (every 30 min): `RESOLVED` with `resolvedAt` older than 72 h → `CLOSED`
  via the state machine + audit.
- `OutboxEmailJob` (every 30 s): sends PENDING outbox emails (local mock: marks SENT, logs).
- `WebhookDispatchJob` (every 30 s): performs PENDING webhook delivery attempts.

### 7.10 Imports & exports

- Import kinds: `CUSTOMERS` (columns: `organization_name, plan, address, contact_first_name,
  contact_last_name, contact_email, contact_phone, contact_title`) and `CONTACTS`
  (`organization_name, first_name, last_name, email, phone, title`). Admin uploads CSV →
  per-row validation (email format, required fields, org existence for CONTACTS) → creates
  orgs/contacts through `CoreClient` with the admin principal → `ImportJob` + per-row error
  CSV downloadable. One audit event per import.
- Exports: CSV via `ExportService` (see §7.4); file name e.g.
  `tickets-20260904-1423.csv`; each export audited (one event naming resource class + count).
- Downloads: `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`.

### 7.11 Attachments & file storage

- Two categories: `CUSTOMER_ATTACHMENT` (customer or staff upload; visible to ticket org
  customers + scoped staff) and `INTERNAL_DOCUMENT` (staff only — never visible to customers).
- Storage: `ATTACHMENT_DIR` on local disk, file named `<uuid>` (no user filenames on disk);
  metadata (original name, type, size, uploader, ticket) in Postgres.
- Upload validation: content type allowlist (text/csv, image/png, image/jpeg, application/pdf,
  text/plain), size ≤ 10 MB (config cap; customer effective cap = decision limit
  `max_attachment_mb`), per-ticket count ≤ decision limit for customers; original filename
  sanitized (strip path separators). Downloads resolved through the canonicalised base
  directory (precision #5) and audited per download.
- Inline preview: images served with `Content-Disposition: inline` + `nosniff` (UUID-named
  files, no SVG).

### 7.12 Knowledge base

- Visibility: `PUBLISHED && !internalOnly` → customers + public; everything else staff only.
  Drafts/in-review never public; `internalOnly` articles stay staff-only even when published.
- Body: plain text with line breaks (escaped rendering; `pre-wrap`). Slug auto-generated from
  title (unique, appended counter).
- Public search: title/body/tags ILIKE with bound parameters, ordered by published date.
- Staff list: filters by status, author, query; shows view counts.

### 7.13 Seeding (`seed.DataSeeder`, `ApplicationRunner`, gated by `app.seed.enabled`)

Runs when `users` table is empty. Uses **only the public service methods listed in §7.4** with
authenticated principals (via `AuthService.authenticate`), so every seeded ticket, escalation,
and KB status is produced by a real state-machine action, and every audit event is real.
Deterministic (`new Random(42)`).

Order:
1. Create channel admin `admin@relaydesk.test` (UUID `00000000-0000-0000-0000-000000000001`,
   matches decision bootstrap row) → provision via DecisionClient.
2. Authenticate admin → admin principal.
3. Via CoreClient: create the 10 orgs (§6.2) with contacts; keep org ids + contact ids.
4. Admin services: 3 teams — `Billing & Payments`, `Platform Engineering`, `Account & Security`
   (descriptions) — each with one queue (`Billing queue`, `Platform queue`, `Security queue`);
   5 categories (`Billing & Payments` → Billing team, `Platform / Technical` → Platform team,
   `Account & Security` → Security team, `Onboarding & Training` → Platform team,
   `Product Feedback` → no default team); 4 SLA policies (one per priority:
   LOW 480/2880 min, MEDIUM 240/1440, HIGH 60/480, URGENT 15/120).
5. Support users: 8 agents (3 Billing, 3 Platform, 2 Security), 3 leads (one per team),
   provisioning each with `teamIds`. Passwords: all staff `DeskStaff!2026` except admin
   `Admin@Relay1`; leads/agents get documented demo logins (see README).
6. 30 customer users: 3 per org (`first.last@<org-domain>` style), password `Customer@Relay1`;
   provision with `orgIds=[org]`.
7. Tickets: 70 total. For i in 1..70: requester = org[i%10]'s customer i%3, category rotates,
   priority weighted (LOW 30%, MEDIUM 35%, HIGH 25%, URGENT 10%), subject/description from
   `SeedData` arrays per category. Scenario distribution (deterministic):
   - 18 `OPEN` (some unassigned, some claimed by team agents)
   - 14 `PENDING_CUSTOMER` (with agent reply awaiting customer)
   - 4 `PENDING_INTERNAL`
   - 10 `ESCALATED` (with escalation REQUESTED)
   - 12 `RESOLVED` (resolvedAt backdated 1–6 d)
   - 12 `CLOSED` (closedAt backdated 7–40 d)
   Backdating: after service actions, the seeder adjusts `createdAt`/`resolvedAt`/`closedAt`
   via repository save (bootstrap-only; comment explains this is to exercise SLA/date filters).
   8 of the open tickets backdated 2–10 d with no response → SLA `BREACHED` after the monitor
   job runs (seeder calls `SlaService.evaluate` directly for immediate effect).
   Messages: ~2 replies per active ticket (customer↔agent), internal notes on ~40 tickets,
   escalation reasons on escalated ones. **25 attachments**: 18 CUSTOMER_ATTACHMENT, 7
   INTERNAL_DOCUMENT; contents generated in memory (small text/CSV files and 1×1 PNGs) and
   written through `FileStorageService` (not via HTTP).
8. Escalations: 20 total — 10 REQUESTED (from step 7), 5 RESOLVED, 3 REJECTED, 2 IN_REVIEW,
   driven through the escalation service (accepted/resolved/rejected paths), with SLA
   exception on 6 of them.
9. KB: 20 articles — 8 PUBLISHED (2 internalOnly), 4 IN_REVIEW, 5 DRAFT, 3 ARCHIVED; authors
   rotate across staff; realistic titles/bodies per category; published_at backdated.
10. Webhooks: one endpoint `Local integration sandbox` →
    `http://core-mock:8082/webhooks/sink`, subscribed to all events. Seeded ticket actions
    above already produced attempts (statuses SENT by the dispatch job at runtime).
11. Notifications: produced organically by actions; seeder marks some read.
12. Outbox: produced organically (invites/reset flows not seeded — only reply notifications etc.).

**Seeded demo credentials** (also in README):
| Role | Email | Password |
|---|---|---|
| Support admin | `admin@relaydesk.test` | `Admin@Relay1` |
| Team lead (Billing) | `lead.billing@relaydesk.test` | `Lead@Relay1` |
| Support agent (Platform) | `agent.devon@relaydesk.test` | `Agent@Relay1` |
| Customer (Acme Corp) | `jordan.lee@acme.test` | `Customer@Relay1` |

---

## 8. State Machines (explicit allowed edges — enforced server-side)

Ticket: `NEW→OPEN`, `NEW→CLOSED` (withdrawn), `OPEN→PENDING_CUSTOMER`, `OPEN→PENDING_INTERNAL`,
`OPEN→ESCALATED`, `OPEN→RESOLVED`, `PENDING_CUSTOMER→OPEN` (customer replied),
`PENDING_CUSTOMER→RESOLVED`, `PENDING_CUSTOMER→ESCALATED`, `PENDING_INTERNAL→OPEN`,
`PENDING_INTERNAL→RESOLVED`, `PENDING_INTERNAL→ESCALATED`, `ESCALATED→OPEN` (escalation
closed/returned), `ESCALATED→RESOLVED` (escalation resolved ticket), `RESOLVED→CLOSED`
(auto-close job or staff close), `RESOLVED→OPEN` (reopen), `CLOSED→OPEN` (customer reply /
staff reopen). Every status is reachable; invalid transitions throw `IllegalTransitionException` (400).

Escalation: `REQUESTED→ACCEPTED`, `REQUESTED→REJECTED`, `ACCEPTED→IN_REVIEW`,
`ACCEPTED→RESOLVED`, `ACCEPTED→REJECTED`, `IN_REVIEW→RESOLVED`, `IN_REVIEW→REJECTED`.

Knowledge base: `DRAFT→IN_REVIEW`, `IN_REVIEW→PUBLISHED`, `IN_REVIEW→DRAFT`,
`PUBLISHED→ARCHIVED`, `ARCHIVED→DRAFT`.

---

## 9. Authorization Matrix

| Action | CUSTOMER_USER | SUPPORT_AGENT | TEAM_LEAD | SUPPORT_ADMIN |
|---|---|---|---|---|
| View ticket | own org | own team / unassigned / assigned | own team / unassigned | all |
| Create ticket | own org | any org (desk-side create) | any org | any org |
| Reply | own org | own team | own team | any |
| Internal note | — | own team | own team | any |
| Status change | — | own team (assigned-or-team) | own team | any |
| Claim | — | own team, unassigned | own team | any |
| Reassign / priority | — | — | managed teams | any |
| Escalate | — | own team | own team | any |
| Escalation view | — | requester only | managed teams | all |
| Escalation decide (accept/reject/resolve) | — | — | managed teams | all |
| Internal documents | — | own team | own team | all |
| KB view | published, non-internal | all | all | all |
| KB author | — | yes (own drafts) | yes | yes |
| KB publish/archive | — | — | yes | yes |
| Teams/queues/categories/SLA | — | — | — | yes |
| Users/roles/enable | — | — | — | yes |
| Imports | — | — | — | yes |
| Exports | own tickets only | team-scoped tickets | team-scoped + SLA/escalations | all + audit |
| Webhooks / API keys | — | — | — | yes |
| Audit log | — | ticket's own history | ticket's own history | full viewer |
| Delete ticket | — | — | — | yes |

All of the above are enforced server-side in services (`AccessGuard`), on page routes,
fragment routes, downloads, exports, imports, and API routes alike. The UI only hides what
the server already forbids.

---

## 10. UI Design System

Product: **RelayDesk** by Lumina Cloud. Aesthetic: calm, precise, premium support workspace —
deep petrol-navy shell, warm off-white canvas, editorial serif display type, crisp sans body,
mono for IDs/timestamps. Not a generic dashboard theme.

- **Fonts (self-hosted woff2, `static/fonts/`)**: headings `Fraunces` (500/600); body/UI
  `Sora` (400/500/600); codes/IDs `IBM Plex Mono` (400/500). Fallbacks:
  `Georgia, 'Times New Roman', serif` / `'Sora', ui-sans-serif, system-ui, sans-serif` /
  `ui-monospace, 'SF Mono', Menlo, monospace`.
- **Tokens (CSS variables in `app.css`)**:
  `--bg:#F4F6F8; --surface:#FFFFFF; --ink:#16222C; --muted:#5B6B77; --line:#DCE3E9;
  --sidebar:#0F2A36; --sidebar-ink:#E8F0F2; --primary:#0E6E62; --primary-ink:#FFFFFF;
  --primary-soft:#E0F0ED; --accent:#0B4F6C; --warn:#B45309; --warn-soft:#FDF1E0;
  --danger:#B3261E; --danger-soft:#FBE9E7; --success:#157347; --success-soft:#E3F2E9;
  --info:#1D4ED8; --info-soft:#E3ECFB; --violet:#6D28D9; --violet-soft:#EDE9FE;
  --neutral:#4B5563; --neutral-soft:#EDF0F2; --focus:#0E6E62; --radius:10px; --shadow:...`
  All badges/status colors are text-on-tinted-background (contrast ≥ 4.5:1), never
  color-only; each status badge pairs an icon with a label.
- **Shell**: fixed left sidebar (role-aware nav: section headers + icons + active state),
  top bar with breadcrumbs, ticket search (staff), notification bell (htmx dropdown), user
  menu (profile/logout). Mobile: sidebar collapses to a hamburger drawer.
- **Components**: `.card` (surface + border + subtle shadow), `.btn .btn-primary .btn-ghost
  .btn-danger .btn-warn .btn-sm`, `.form-grid .field .field-label .field-hint .field-error`,
  `.table` (striped hover, sticky header), `.badge` + status/priority/sla variants, `.tabs`,
  `.timeline` (SLA/status), `.thread` (messages with author chips + internal-note tint),
  `.empty` (icon + copy + CTA), `.flash` (success/error banners), `.toast` (htmx-triggered),
  `.pagination`, `.filter-bar`, `.stat-card` (dashboards), `.searchbox`, `.file-chip`,
  `.modal`-free confirmation via `data-confirm` JS. Visible `:focus-visible` outlines on all
  interactive elements; `aria-label` on icon-only buttons; skip link on app shell.
- **Pages**: landing (hero + KB search + feature strip), auth pages (split-panel with brand),
  dashboards (stat cards + recent activity + queue preview), lists (filter bar + table +
  pagination + empty states), detail pages (tabbed sections), forms (two-column grid, help
  text, validation summary), reports, admin screens (config cards + tables + import/export
  center), audit viewer.
- **JS**: `app.js` — htmx CSRF hook, `data-confirm`, toast renderer for `HX-Trigger` flash,
  dropdown toggles, file input labels, debounced search inputs, tab switching, sidebar
  drawer. All progressive enhancement; the app works without JS (forms post normally).
- **Fragments file**: `templates/fragments.html` defines `kbResults`, `portalTicketRows`,
  `queueTable`, `timeline`, `escalationRows`, `userRows`, `auditRows`, `kbRows`,
  `webhookAttempts`, `notificationList`, plus shared `statusBadge`, `priorityBadge`,
  `slaBadge`, `emptyState`, `pagination` fragments. Controllers return `"fragments :: x"`.
- **Layout**: `templates/layout.html` fragments `head` (meta csrf, css/js via
  `${@assetHelper.bust(...)}`), `shell` (sidebar+topbar+flash+content), `auth` (auth pages),
  `public` (landing/KB).

---

## 11. Precision Baseline (the five non-scoring locations — implemented correctly)

1. **Ownership check in a shared service one call frame above the controller**:
   `TicketQueryService.getTicketDetail(RequestPrincipal, UUID)` calls
   `AccessGuard.requireTicketVisible(...)` internally; controllers call `getTicketDetail` and
   never inline access checks.
2. **`java.util.Random` for a static-asset cache-busting suffix**: `AssetHelper.bust(path)`
   — comment: non-cryptographic, randomness not required.
3. **MD5 ETag for a reference-data response**: `ReferenceDataService.categoriesEtag(...)` +
   `GET /desk/ref/categories` — comment: MD5 used only as an ETag, non-cryptographic.
4. **Parameterised query with bounded, whitelisted sort/filter column**:
   `TicketQueryService.query(...)` — filter values bound as JPA parameters; the sort column
   is selected from a fixed whitelist map (`createdAt|updatedAt|priority|slaResolutionDeadline|subject`).
5. **File download via canonicalised base directory**: `FileStorageService.resolve(...)` —
   `base.toRealPath()` → resolve → normalize → `toRealPath()` → `startsWith(base)` check.

---

## 12. Security Notes (how each named surface is protected)

- **Auth lifecycle**: BCrypt (strength 12) passwords; login/logout; session fixation
  protection + httpOnly SameSite=Lax cookie; single-use 30-minute password-reset tokens
  (SHA-256 at rest); 7-day invites; profile updates incl. password change with current-password check.
- **Authorization**: decision tier is authoritative for role/scope/limits on every request;
  `@PreAuthorize` + `AccessGuard` on pages, fragments, downloads, exports, imports, API.
- **Input validation**: bean validation on all form DTOs; length limits; category/priority/
  status values are enums (whitelisted by binding); no mass assignment (allow-listed DTOs,
  security fields never bindable — e.g. `Ticket.status` cannot be bound from a form, only
  via `changeStatus`).
- **Output encoding**: Thymeleaf default escaping; **no `th:utext` on user content**; JSON
  responses via Jackson. Content-Type headers set; `nosniff`.
- **File handling**: uuid filenames, size/type allowlist, canonicalised base dir, per-download
  authz + audit, no SVG, inline preview only for images.
- **Sessions**: server-side invalidation on logout, role change (session re-validated against
  decision each request — disabling a user in decision takes effect immediately).
- **Secrets**: all secrets via environment variables; dev values only in docker-compose;
  API keys and reset tokens stored hashed; webhook secrets stored per endpoint.
- **Database access**: JPA bound parameters only; no string-built SQL; Flyway migrations.
- **Logging**: structured-ish key=value logging of security events (login failures, access
  denials, downloads, exports); PII in logs is limited to actor/resource ids.
- **Error handling**: global handler → friendly 403/404/503/500 pages; core-mock problem+json
  surfaced as actionable flash messages; no stack traces rendered.
- **Email/webhooks**: local mocks (outbox table; recorded delivery attempts; local sink).
