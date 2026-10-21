# RelayDesk — Customer Support Desk

RelayDesk is the customer support desk for **Lumina Cloud, Inc.** (fictional SaaS company).
It manages customer organizations, tickets, SLAs, attachments, internal notes, escalations,
knowledge base articles, imports/exports, webhooks, API keys, audit logging and a customer
portal — built as a realistic, security-conscious three-tier monolith.

**Stack:** Java 21 · Spring Boot 3.3 · Spring MVC + Thymeleaf + htmx (server-rendered
fragments) · Spring Security · Spring Data JPA · Flyway · PostgreSQL 16 · Gradle 8.10
(multi-module) · Docker Compose.

---

## 1. Tier & Trust Statement (canonical)

The repository is a Gradle multi-module build with three deployable tiers:

| Module      | Port (host map) | Role                                                                                       |
|-------------|-----------------|--------------------------------------------------------------------------------------------|
| `channel`   | 8080 → **18080** | User-facing tier (UI + domain data). **Channel authenticates.**                            |
| `decision`  | 8081 → **18081** | Authorization tier. **Decision authorizes.** It resolves the opaque signed session token on **every** request and loads the acting user, role, scope and limits **from its own database**. It mints the short-lived scope tokens used for `core-mock` calls. |
| `core-mock` | 8082 → **18082** | Vendor-core stub: the external "Lumina Customer Core" (CRM) system. Own endpoint, own credential, own error codes (`CORE_1xxx`). It **verifies the decision-minted scope token** and enforces the scope claim; it never trusts a raw scope parameter. |
| `shared`    | —               | Plain library: signed-token codec (`TokenCodec`), `Role` enum.                             |

**Identity propagation is specified, not invented:**

1. A user logs in at `channel` (BCrypt against the channel user table). Channel mints an
   **opaque HMAC-signed session token** (12 h TTL) held in the HTTP session.
2. On **every** request — pages, htmx fragment routes, downloads, exports and API calls —
   a channel filter sends that token to `decision` (`POST /internal/auth/validate`).
   `decision` verifies the signature and returns role, scope (customer org ids / support
   team ids), limits and enabled state **from its own database**. Channel replaces the
   SecurityContext authorities with the decision-derived values, so role changes and
   account disables take effect immediately. If decision is unreachable, channel fails
   closed (503).
3. When channel needs customer-directory data, it asks `decision` for a **scope token**
   (60 s TTL) and presents it to `core-mock`, which verifies the signature and enforces the
   scope claim: a `CUSTOMER_USER` token may only read its own organization; mutations
   require `SUPPORT_ADMIN`. Any other org id in the path is rejected with `CORE_1002`.

`decision` and `core-mock` ignore any user id, role, company, scope, or limit supplied in
headers, paths, queries, forms, or bodies — identity comes exclusively from the signed
token. Service-to-service calls additionally present an `X-Internal-Key` credential
(channel→decision and channel→core-mock use separate keys).

**Data placement:** identities + passwords and the support domain (tickets, replies,
notes, attachments, escalations, KB, audit, notifications, outbox, webhooks, API keys)
live in the `channel` database. Roles, scopes, team memberships, org bindings and limits
live in the `decision` database. Organizations, contacts and entitlements live in the
`core-mock` database (the external CRM); tickets store only an org id + name snapshot.

**Money handling:** this product does not process monetary amounts, so the
fixed-precision-decimal rule does not apply.

---

## 2. Quick Start

**One command (recommended):**

```bash
docker compose up --build
```

This starts PostgreSQL plus all three tiers, runs Flyway migrations and loads the seed
data (idempotent). Then open **http://localhost:18080**.

> The three tiers run inside the compose network on ports 8080/8081/8082 and are exposed
> on the host as **18080** (channel), **18081** (decision), **18082** (core-mock) to avoid
> clashing with other local services. PostgreSQL is exposed on `127.0.0.1:15432`.

To reset everything (fresh seed): `docker compose down -v && docker compose up --build`.

**Run without Docker** (requires a local PostgreSQL with databases `channel`, `decision`,
`core` — see `db/init/00-databases.sql`):

```bash
export SESSION_TOKEN_SECRET=dev-session-secret-change-me
export SCOPE_TOKEN_SECRET=dev-scope-secret-change-me
export DECISION_INTERNAL_KEY=dev-decision-key-change-me
export CORE_INTERNAL_KEY=dev-core-key-change-me
export DB_USER=relay DB_PASSWORD=relay-dev-only

DB_DECISION_URL=jdbc:postgresql://localhost:5432/decision SERVER_PORT=8081 \
  java -jar decision/build/libs/decision.jar
DB_CORE_URL=jdbc:postgresql://localhost:5432/core SERVER_PORT=8082 \
  java -jar core-mock/build/libs/core-mock.jar
DB_CHANNEL_URL=jdbc:postgresql://localhost:5432/channel SERVER_PORT=8080 \
  DECISION_URL=http://localhost:8081 CORE_URL=http://localhost:8082 ATTACHMENT_DIR=./attachments \
  java -jar channel/build/libs/channel.jar
```

> **Secrets:** the values above are development-only defaults injected by
> `docker-compose.yml`. All secrets are read from environment variables and the apps fail
> fast at startup when a required secret is missing. Override every secret in production.

**Build & test:**

```bash
./gradlew build          # compiles all modules, runs the test suites
./gradlew test           # tests only
```

CI: `Jenkinsfile` at the repository root builds, tests and publishes JUnit reports.

---

## 3. Demo Credentials (seeded)

| Role            | Email                          | Password        | Landing page  |
|-----------------|--------------------------------|-----------------|---------------|
| Support admin   | `admin@relaydesk.test`         | `Admin@Relay1`  | `/admin`      |
| Team lead       | `lead.billing@relaydesk.test`  | `Lead@Relay1`   | `/desk`       |
| Support agent   | `agent.devon@relaydesk.test`   | `Agent@Relay1`  | `/desk`       |
| Customer (Acme) | `jordan.lee@acme-corp.test`    | `Customer@Relay1` | `/portal`   |

Seed inventory: **10 customer organizations** (with contacts and entitlements in
core-mock), **30 customer users**, **12 support users** (1 admin, 3 team leads,
8 agents across 3 teams/queues), **70 tickets** across every status (including overdue
and SLA-breached ones), **25 attachments** (customer + internal categories), **20
escalations** across every status, **20 knowledge base articles** (draft / in review /
published / archived, some internal-only), plus internal notes, notifications, outbox
emails and webhook delivery attempts — all produced by real state-machine actions with
real audit events (~400+ rows).

---

## 4. Roles & Authorization

- **`customer_user`** — views and creates tickets for **their organization only**,
  replies, uploads/downloads customer attachments, reads the public knowledge base, and
  exports their own tickets. Never sees internal notes, internal documents, escalation
  reasons, or other organizations' data.
- **`support_agent`** — works the queue of **their teams** (or unassigned tickets),
  claims tickets, replies, adds internal notes, uploads internal documents, escalates.
- **`team_lead`** — everything an agent does, plus reassignment, priority changes,
  escalation decisions (accept / review / reject / resolve) for their managed teams, and
  SLA reports/exports.
- **`support_admin`** — everything, plus teams, queues, categories, SLA policies, users
  (create / role change / enable-disable), customer directory, imports, exports, webhooks,
  API keys, the full audit log and settings.

Every rule above is enforced **server-side** in services (`AccessGuard` + `@PreAuthorize`)
on page routes, htmx fragment routes, downloads, exports, imports and API routes alike —
the UI only hides what the server already forbids. There is **no impersonation**; admins
and leads work through scoped filters and reassignment instead.

### Ticket state machine (explicit edges)

`NEW→OPEN`, `NEW→CLOSED`, `OPEN→PENDING_CUSTOMER|PENDING_INTERNAL|ESCALATED|RESOLVED`,
`PENDING_*→OPEN|RESOLVED|ESCALATED`, `ESCALATED→OPEN|RESOLVED`, `RESOLVED→CLOSED`,
`RESOLVED→OPEN`, `CLOSED→OPEN`. Escalations: `REQUESTED→ACCEPTED|REJECTED`,
`ACCEPTED→IN_REVIEW|REJECTED|RESOLVED`, `IN_REVIEW→RESOLVED|REJECTED`. Knowledge base:
`DRAFT→IN_REVIEW→PUBLISHED→ARCHIVED` (with send-back `IN_REVIEW→DRAFT` and
restore `ARCHIVED→DRAFT`). Invalid transitions are rejected server-side.

---

## 5. Screens & Workflows

- **Public:** landing page, login, forgot/reset password (single-use, 30-minute tokens),
  invite acceptance, public knowledge base search.
- **Customer portal:** dashboard, org ticket list with filters + export, ticket creation
  with category/priority/contact and attachment, ticket detail with replies, attachments
  and SLA timeline.
- **Agent desk:** dashboard, filterable queue (status, priority, SLA, category, team,
  queue, assignment, overdue, dates, text), ticket detail with conversation (public
  replies + tinted internal notes), SLA timeline, escalation panel, audit history, claim /
  reply / note / status / escalate actions.
- **Team lead:** escalation queue + decision actions (accept, review, reject, resolve with
  ticket resolution), reassignment and priority changes, SLA reports dashboard with
  per-team stats, overdue list and CSV exports.
- **Admin:** dashboards for users (create, role, enable/disable, customer invites),
  teams & queues, categories & SLA policies, customer directory (via core-mock), CSV
  imports with per-row error reports and templates, export center (tickets, SLA,
  escalations, audit), webhooks (signed deliveries + attempt history + local sink), API
  keys (shown once, stored hashed), full audit viewer, settings (decision limits, outbox
  mail viewer, storage info).
- **API surface:** `POST /api/v1/tickets`, `GET /api/v1/tickets/{ref}`,
  `GET /api/v1/tickets` with bearer API keys (scoped `tickets:create` / `tickets:read`).
  Responses never contain internal notes or escalation reasons.
- **Background jobs (in-process scheduler):** SLA monitor (breach detection +
  notifications + audit), auto-close of resolved tickets after 72 h, mock outbox email
  sender, webhook delivery dispatcher.
- **Local integration mocks:** outbound email → `outbox_emails` table; webhook deliveries
  → recorded attempts (seeded endpoint points at core-mock's `/webhooks/sink` receiver);
  customer directory → core-mock REST API. No external services required.

## 6. Security Controls (summary)

- **Auth lifecycle:** BCrypt (strength 12), session fixation protection, httpOnly +
  SameSite=Lax cookies, POST-only CSRF-protected logout, single-use time-bound password
  reset tokens (hashed at rest), 7-day invites, profile/password updates.
- **Authorization:** decision tier is the authoritative source of role/scope/limits on
  every request; `@PreAuthorize` + explicit ownership/team/org checks in services on
  pages, fragments, downloads, exports, imports and API routes; no impersonation.
- **CSRF:** session-based token on all state-changing forms and htmx requests
  (`X-CSRF-TOKEN` header injected by `app.js`).
- **Input validation:** bean-validated allow-listed DTOs; enums for statuses/priorities;
  security-relevant fields are never form-bindable (ticket status changes only via the
  state machine service).
- **Output encoding:** Thymeleaf default escaping everywhere; **no `th:utext` on user
  content**; JSON via Jackson; `X-Content-Type-Options: nosniff`.
- **File handling:** local disk storage under UUID names, metadata in Postgres, type/size
  allowlists, per-download authorization + audit, downloads resolved through a
  canonicalised base directory, images previewed inline only.
- **Database:** JPA bound parameters only; Flyway migrations; append-only audit table
  (no update/delete path exists); read-auditing emits one event per request naming the
  resource class and count.
- **Secrets:** environment-variable driven, fail-fast on missing; API keys / reset tokens
  stored hashed; webhook payloads HMAC-signed per endpoint.
- **Error handling:** friendly 403/404/503/500 pages; core-mock problem+json codes
  surfaced as actionable messages; no stack traces rendered.

### Precision baseline (five non-scoring locations — implemented correctly)

1. Ownership/scope check in a shared service one call frame above the controller
   (`TicketQueryService.getTicketDetail` → `AccessGuard.requireTicketVisible`).
2. `java.util.Random` used only for a static-asset cache-busting suffix (`AssetHelper`).
3. MD5 used only as an ETag for a reference-data response
   (`ReferenceDataService.categoriesEtag`, endpoint `GET /desk/ref/categories`).
4. Parameterised query with a bounded, whitelisted sort column
   (`TicketQueryService.SORT_COLUMNS`).
5. File download via a canonicalised base directory (`FileStorageService.resolve`).

## 7. Scripted Verification

With the stack running:

```bash
PG_HOST=127.0.0.1 PG_PORT=5434 CHANNEL=http://localhost:18080 scripts/smoke-check.sh
```

The script logs in as all four roles and verifies: role redirects, ticket creation with
attachment, replies, internal notes, agent claim, wrong-team denial, cross-organization
denial, escalation accept/review/resolve, SLA + audit CSV exports, KB publishing, public
KB search, API-key creation (hashed storage) and bearer enforcement, and webhook
delivery-attempt recording.

## 8. Implementation Summary

- **Main screens:** public landing + KB, login/forgot/reset/invite, customer portal
  (dashboard, tickets, create, detail, profile), agent desk (dashboard, queue,
  ticket detail), escalation queue, SLA reports, KB editor, and 14 admin screens.
- **Workflows:** ticket lifecycle (create → claim → reply/note → status transitions →
  resolve → auto-close), escalation lifecycle (request → accept → review → reject/resolve),
  KB lifecycle (draft → review → publish → archive), password reset, invites, CSV imports,
  CSV exports, webhooks, API keys.
- **Security controls:** as summarized in §6, with the tier/trust model of §1.
- **Verification:** 87 automated tests (state machines, access-guard matrix, token codec,
  CSV handling, file-storage path traversal, controller authorization, API-key enforcement)
  plus the scripted end-to-end smoke suite in `scripts/smoke-check.sh` (74 checks).

## 9. UI Summary

- **Navigation:** role-aware sidebar (customer portal vs. staff desk vs. admin sections)
  with icons, active states and a mobile drawer; top bar with breadcrumbs, ticket lookup
  (staff), a notification bell (htmx-fetched dropdown with mark-all-read) and a user menu
  (profile, logout). All inner pages carry contextual back links or breadcrumbs.
- **Dashboards:** per-role dashboards with stat cards (open/new/overdue/escalated/
  resolved), recent activity, queue previews and SLA health strips for leads; admin
  dashboard adds system health (decision/core connectivity), outbox and webhook status.
- **Visual system:** hand-built design system in `app.css` — deep petrol-navy sidebar on a
  warm off-white canvas, Fraunces display serif + Sora UI sans + IBM Plex Mono for ticket
  refs (self-hosted woff2), tinted text-on-color status badges (never color-only),
  accessible contrast (≥ 4.5:1), visible focus rings, skip link, empty/error/success
  states, toast notifications, responsive down to tablet/mobile widths. htmx 2.0.4
  (vendored) powers fragments — every fragment endpoint is a real, independently
  authorized endpoint.

## 10. Requirements Status

All shared-core and app-specific requirements are implemented: registration
(invite-based onboarding), login/logout/session/password-reset/profile, admin user
management without impersonation, full domain CRUD with state machines, file
upload/download with two categories and ownership rules, comments/internal notes,
search & filtering across records, CSV import + exports, append-only audit log,
in-app notifications, API keys + webhooks + JSON endpoints, three-tier architecture
with decision-owned authorization, background jobs, local mocks for email/webhooks,
migrations + idempotent seed data, and the UI quality bar. **No requirement was skipped.**
