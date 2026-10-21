-- RelayDesk channel schema v2: local mirror of user→team memberships (decision remains
-- the authoritative source of team scope; this table is maintained by UserAdminService
-- and used for display and lead lookups only).

CREATE TABLE user_teams (
    id         bigserial PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id),
    team_id    uuid        NOT NULL REFERENCES teams (id),
    created_at timestamptz NOT NULL,
    UNIQUE (user_id, team_id)
);
CREATE INDEX idx_user_teams_user ON user_teams (user_id);
CREATE INDEX idx_user_teams_team ON user_teams (team_id);
