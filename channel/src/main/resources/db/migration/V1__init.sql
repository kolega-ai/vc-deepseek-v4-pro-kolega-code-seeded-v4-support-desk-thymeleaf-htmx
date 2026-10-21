-- RelayDesk channel schema (v1)

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    display_name  varchar(120) NOT NULL,
    role          varchar(30)  NOT NULL,
    enabled       boolean      NOT NULL,
    created_at    timestamptz  NOT NULL
);

CREATE TABLE teams (
    id          uuid PRIMARY KEY,
    name        varchar(100) NOT NULL UNIQUE,
    description varchar(500),
    created_at  timestamptz  NOT NULL
);

CREATE TABLE queues (
    id          uuid PRIMARY KEY,
    name        varchar(100) NOT NULL,
    description varchar(500),
    team_id     uuid         NOT NULL REFERENCES teams (id),
    created_at  timestamptz  NOT NULL
);
CREATE INDEX idx_queues_team ON queues (team_id);

CREATE TABLE categories (
    id              uuid PRIMARY KEY,
    name            varchar(100) NOT NULL UNIQUE,
    description     varchar(500),
    default_team_id uuid REFERENCES teams (id),
    active          boolean      NOT NULL,
    created_at      timestamptz  NOT NULL
);

CREATE TABLE sla_policies (
    id                   uuid PRIMARY KEY,
    name                 varchar(100) NOT NULL,
    priority             varchar(20)  NOT NULL,
    response_minutes     integer      NOT NULL,
    resolution_minutes   integer      NOT NULL,
    business_hours_only  boolean      NOT NULL,
    active               boolean      NOT NULL,
    created_at           timestamptz  NOT NULL
);

CREATE TABLE tickets (
    id                      uuid PRIMARY KEY,
    ref                     varchar(30)  NOT NULL UNIQUE,
    org_id                  uuid         NOT NULL,
    org_name                varchar(200) NOT NULL,
    subject                 varchar(200) NOT NULL,
    description             text         NOT NULL,
    category_id             uuid         NOT NULL REFERENCES categories (id),
    priority                varchar(20)  NOT NULL,
    status                  varchar(30)  NOT NULL,
    team_id                 uuid REFERENCES teams (id),
    queue_id                uuid REFERENCES queues (id),
    assignee_id             uuid REFERENCES users (id),
    requester_id            uuid         NOT NULL REFERENCES users (id),
    sla_policy_id           uuid         NOT NULL REFERENCES sla_policies (id),
    sla_response_deadline   timestamptz  NOT NULL,
    sla_resolution_deadline timestamptz  NOT NULL,
    sla_status              varchar(20)  NOT NULL,
    first_response_at       timestamptz,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    resolved_at             timestamptz,
    closed_at               timestamptz
);
CREATE INDEX idx_tickets_status ON tickets (status);
CREATE INDEX idx_tickets_team ON tickets (team_id);
CREATE INDEX idx_tickets_assignee ON tickets (assignee_id);
CREATE INDEX idx_tickets_requester ON tickets (requester_id);
CREATE INDEX idx_tickets_org ON tickets (org_id);
CREATE INDEX idx_tickets_sla_status ON tickets (sla_status);
CREATE INDEX idx_tickets_created ON tickets (created_at DESC);

CREATE TABLE ticket_messages (
    id         uuid PRIMARY KEY,
    ticket_id  uuid        NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id),
    body       text        NOT NULL,
    internal   boolean     NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_messages_ticket ON ticket_messages (ticket_id);

CREATE TABLE attachments (
    id            uuid PRIMARY KEY,
    ticket_id     uuid         NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    uploader_id   uuid         NOT NULL REFERENCES users (id),
    category      varchar(30)  NOT NULL,
    original_name varchar(255) NOT NULL,
    stored_name   varchar(80)  NOT NULL,
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL,
    created_at    timestamptz  NOT NULL
);
CREATE INDEX idx_attachments_ticket ON attachments (ticket_id);

CREATE TABLE escalations (
    id                   uuid PRIMARY KEY,
    ticket_id            uuid        NOT NULL REFERENCES tickets (id),
    requester_id         uuid        NOT NULL REFERENCES users (id),
    assignee_id          uuid REFERENCES users (id),
    team_id              uuid        NOT NULL REFERENCES teams (id),
    reason               text        NOT NULL,
    sla_exception        boolean     NOT NULL,
    sla_exception_reason text,
    status               varchar(30) NOT NULL,
    resolution           text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    resolved_at          timestamptz
);
CREATE INDEX idx_escalations_ticket ON escalations (ticket_id);
CREATE INDEX idx_escalations_status ON escalations (status);
CREATE INDEX idx_escalations_team ON escalations (team_id);
CREATE INDEX idx_escalations_assignee ON escalations (assignee_id);

CREATE TABLE kb_articles (
    id            uuid PRIMARY KEY,
    title         varchar(200) NOT NULL,
    slug          varchar(220) NOT NULL UNIQUE,
    body          text         NOT NULL,
    category_id   uuid REFERENCES categories (id),
    tags          varchar(500),
    status        varchar(20)  NOT NULL,
    internal_only boolean      NOT NULL,
    author_id     uuid         NOT NULL REFERENCES users (id),
    published_at  timestamptz,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    view_count    bigint       NOT NULL
);
CREATE INDEX idx_kb_status ON kb_articles (status);
CREATE INDEX idx_kb_published ON kb_articles (published_at DESC);

-- Append-only audit log. No update/delete endpoints exist anywhere for this table.
CREATE TABLE audit_events (
    id            bigserial PRIMARY KEY,
    occurred_at   timestamptz  NOT NULL,
    actor_id      uuid,
    actor_email   varchar(255),
    action        varchar(60)  NOT NULL,
    resource_type varchar(40)  NOT NULL,
    resource_id   varchar(80),
    resource_ref  varchar(80),
    before_value  text,
    after_value   text,
    authz_basis   varchar(500),
    request_id    varchar(64)
);
CREATE INDEX idx_audit_occurred ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_action ON audit_events (action);
CREATE INDEX idx_audit_resource_type ON audit_events (resource_type);
CREATE INDEX idx_audit_resource_id ON audit_events (resource_id);
CREATE INDEX idx_audit_actor ON audit_events (actor_email);

CREATE TABLE notifications (
    id         uuid PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users (id),
    type       varchar(40)  NOT NULL,
    title      varchar(160) NOT NULL,
    message    varchar(400) NOT NULL,
    link       varchar(300),
    read_at    timestamptz,
    created_at timestamptz  NOT NULL
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE outbox_emails (
    id         bigserial PRIMARY KEY,
    to_address varchar(255) NOT NULL,
    subject    varchar(200) NOT NULL,
    body       text         NOT NULL,
    status     varchar(20)  NOT NULL,
    sent_at    timestamptz,
    created_at timestamptz  NOT NULL
);
CREATE INDEX idx_outbox_status ON outbox_emails (status);

CREATE TABLE webhook_endpoints (
    id         uuid PRIMARY KEY,
    name       varchar(100) NOT NULL,
    url        varchar(500) NOT NULL,
    secret     varchar(200) NOT NULL,
    events     varchar(300) NOT NULL,
    enabled    boolean      NOT NULL,
    created_at timestamptz  NOT NULL
);

CREATE TABLE webhook_delivery_attempts (
    id            bigserial PRIMARY KEY,
    endpoint_id   uuid         NOT NULL REFERENCES webhook_endpoints (id) ON DELETE CASCADE,
    event         varchar(50)  NOT NULL,
    payload       text         NOT NULL,
    status        varchar(20)  NOT NULL,
    response_code integer,
    error_message varchar(500),
    created_at    timestamptz  NOT NULL,
    delivered_at  timestamptz
);
CREATE INDEX idx_attempts_status ON webhook_delivery_attempts (status);

CREATE TABLE api_keys (
    id           uuid PRIMARY KEY,
    name         varchar(100) NOT NULL,
    key_hash     varchar(64)  NOT NULL UNIQUE,
    prefix       varchar(16)  NOT NULL,
    owner_id     uuid         NOT NULL REFERENCES users (id),
    scopes       varchar(200) NOT NULL,
    enabled      boolean      NOT NULL,
    expires_at   timestamptz,
    last_used_at timestamptz,
    created_at   timestamptz  NOT NULL
);

CREATE TABLE password_reset_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users (id),
    token_hash varchar(64)  NOT NULL UNIQUE,
    expires_at timestamptz  NOT NULL,
    used       boolean      NOT NULL,
    created_at timestamptz  NOT NULL
);
CREATE INDEX idx_reset_tokens_user ON password_reset_tokens (user_id);

CREATE TABLE user_invites (
    id            uuid PRIMARY KEY,
    email         varchar(255) NOT NULL,
    display_name  varchar(120) NOT NULL,
    org_id        uuid         NOT NULL,
    org_name      varchar(200) NOT NULL,
    token_hash    varchar(64)  NOT NULL UNIQUE,
    invited_by_id uuid         NOT NULL REFERENCES users (id),
    expires_at    timestamptz  NOT NULL,
    used_at       timestamptz,
    created_at    timestamptz  NOT NULL
);
CREATE INDEX idx_invites_email ON user_invites (email);

CREATE TABLE import_jobs (
    id            uuid PRIMARY KEY,
    kind          varchar(20)  NOT NULL,
    file_name     varchar(255) NOT NULL,
    status        varchar(20)  NOT NULL,
    rows_total    integer      NOT NULL,
    rows_ok       integer      NOT NULL,
    rows_failed   integer      NOT NULL,
    error_report  text,
    created_by_id uuid         NOT NULL REFERENCES users (id),
    created_at    timestamptz  NOT NULL,
    finished_at   timestamptz
);

CREATE TABLE ticket_sequence (
    id         uuid PRIMARY KEY,
    next_value bigint NOT NULL
);
