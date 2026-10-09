BEGIN;

CREATE SEQUENCE IF NOT EXISTS gateway_announcement_seq;
CREATE TABLE IF NOT EXISTS gateway_announcements (
    announcement_id BIGINT PRIMARY KEY DEFAULT nextval('gateway_announcement_seq'),
    category VARCHAR(24) NOT NULL CHECK (category IN ('GENERAL','PRODUCT','MAINTENANCE','SECURITY','RISK')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN')),
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100),
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by BIGINT NOT NULL REFERENCES gateway_users(user_id),
    updated_by BIGINT NOT NULL REFERENCES gateway_users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_announcements_time_check CHECK (expires_at IS NULL OR expires_at > starts_at)
);
CREATE INDEX IF NOT EXISTS gateway_announcements_active_idx
    ON gateway_announcements (starts_at, priority DESC, announcement_id DESC)
    WHERE status = 'PUBLISHED';

CREATE TABLE IF NOT EXISTS gateway_announcement_translations (
    announcement_id BIGINT NOT NULL REFERENCES gateway_announcements(announcement_id) ON DELETE CASCADE,
    locale VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL CHECK (length(trim(title)) > 0),
    summary VARCHAR(500) NOT NULL DEFAULT '',
    body VARCHAR(12000) NOT NULL CHECK (length(trim(body)) > 0),
    PRIMARY KEY (announcement_id, locale)
);

CREATE TABLE IF NOT EXISTS gateway_announcement_targets (
    announcement_id BIGINT NOT NULL REFERENCES gateway_announcements(announcement_id) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL CHECK (target_type IN ('PRODUCT_LINE','PLATFORM','PLACEMENT')),
    target_value VARCHAR(32) NOT NULL,
    PRIMARY KEY (announcement_id, target_type, target_value)
);
CREATE INDEX IF NOT EXISTS gateway_announcement_targets_lookup_idx
    ON gateway_announcement_targets (target_type, target_value, announcement_id);

CREATE TABLE IF NOT EXISTS gateway_announcement_reads (
    announcement_id BIGINT NOT NULL REFERENCES gateway_announcements(announcement_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (announcement_id, user_id)
);
CREATE INDEX IF NOT EXISTS gateway_announcement_reads_user_idx
    ON gateway_announcement_reads (user_id, read_at DESC, announcement_id DESC);

CREATE TABLE IF NOT EXISTS gateway_announcement_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    announcement_id BIGINT NOT NULL REFERENCES gateway_announcements(announcement_id) ON DELETE CASCADE,
    action VARCHAR(16) NOT NULL CHECK (action IN ('CREATE','UPDATE','PUBLISH','WITHDRAW')),
    admin_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    admin_username TEXT NOT NULL,
    reason VARCHAR(1000),
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS gateway_announcement_audit_history_idx
    ON gateway_announcement_audit (announcement_id, audit_id DESC);

INSERT INTO gateway_permissions (permission_code, permission_name, description)
VALUES
    ('admin.announcements.read', 'Read announcements', 'View and preview exchange announcements.'),
    ('admin.announcements.write', 'Write announcements', 'Create and edit announcement drafts.'),
    ('admin.announcements.publish', 'Publish announcements', 'Publish, schedule and withdraw exchange announcements.')
ON CONFLICT (permission_code) DO UPDATE
SET permission_name = EXCLUDED.permission_name,
    description = EXCLUDED.description;

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code IN (
      'admin.announcements.read','admin.announcements.write','admin.announcements.publish'
  )
 WHERE r.role_code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
