CREATE TABLE organizations (id uuid PRIMARY KEY, name varchar(200) NOT NULL, slug varchar(220) NOT NULL UNIQUE, plan varchar(50) NOT NULL, status varchar(20) NOT NULL, address varchar(500), created_at timestamptz NOT NULL);
CREATE TABLE contacts (id uuid PRIMARY KEY, org_id uuid NOT NULL REFERENCES organizations(id), first_name varchar(100) NOT NULL, last_name varchar(100) NOT NULL, email varchar(255) NOT NULL, phone varchar(50), title varchar(120), active boolean NOT NULL, created_at timestamptz NOT NULL);
CREATE INDEX idx_contacts_org ON contacts (org_id);
CREATE TABLE entitlements (id bigserial PRIMARY KEY, org_id uuid NOT NULL REFERENCES organizations(id), product varchar(120) NOT NULL, seats integer NOT NULL, status varchar(20) NOT NULL);
CREATE INDEX idx_entitlements_org ON entitlements (org_id);
CREATE TABLE webhook_receipts (id bigserial PRIMARY KEY, received_at timestamptz NOT NULL, headers text, payload text);
