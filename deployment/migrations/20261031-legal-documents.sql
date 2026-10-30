-- Published revisions and their translations are immutable. New text requires a new draft revision.
CREATE TABLE IF NOT EXISTS gateway_legal_revisions (
    revision_id BIGSERIAL PRIMARY KEY,
    document_type VARCHAR(32) NOT NULL CHECK(document_type IN ('USER_AGREEMENT','PRIVACY_POLICY','COOKIE_POLICY','TRADING_RISK','DERIVATIVES_RISK','FUNDS_AND_WITHDRAWALS','FEES','AML_KYC','COMPLAINTS','SECURITY_NOTICE')),
    revision_label VARCHAR(40) NOT NULL CHECK(length(trim(revision_label))>0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','PUBLISHED','SUPERSEDED','WITHDRAWN')),
    version BIGINT NOT NULL DEFAULT 1 CHECK(version>0),
    created_by BIGINT NOT NULL REFERENCES gateway_users(user_id),
    updated_by BIGINT NOT NULL REFERENCES gateway_users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    UNIQUE(document_type,revision_label)
);
CREATE UNIQUE INDEX IF NOT EXISTS gateway_legal_current_idx ON gateway_legal_revisions(document_type) WHERE status='PUBLISHED';
CREATE TABLE IF NOT EXISTS gateway_legal_translations (
    revision_id BIGINT NOT NULL REFERENCES gateway_legal_revisions(revision_id),
    locale VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL CHECK(length(trim(title))>0),
    body_html VARCHAR(64000) NOT NULL CHECK(length(trim(body_html))>0),
    PRIMARY KEY(revision_id,locale)
);
CREATE TABLE IF NOT EXISTS gateway_legal_consents (
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    revision_id BIGINT NOT NULL REFERENCES gateway_legal_revisions(revision_id),
    locale VARCHAR(32) NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id,revision_id)
);
CREATE TABLE IF NOT EXISTS gateway_legal_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    revision_id BIGINT NOT NULL REFERENCES gateway_legal_revisions(revision_id),
    action VARCHAR(24) NOT NULL,
    admin_user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    reason VARCHAR(1000),
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS gateway_legal_audit_revision_idx ON gateway_legal_audit(revision_id,audit_id DESC);

INSERT INTO gateway_permissions(permission_code,permission_name,description) VALUES
 ('admin.legal.read','Read legal documents','View legal revisions and audit history.'),
 ('admin.legal.write','Write legal documents','Create and edit unpublished legal revisions.'),
 ('admin.legal.publish','Publish legal documents','Publish replacement legal revisions or withdraw optional notices.'),
 ('admin.content.media.write','Upload article media','Upload public media for announcements and legal articles.')
ON CONFLICT(permission_code) DO UPDATE SET permission_name=EXCLUDED.permission_name,description=EXCLUDED.description;
INSERT INTO gateway_role_permissions(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM gateway_roles r CROSS JOIN gateway_permissions p
WHERE r.role_code='ADMIN' AND p.permission_code IN ('admin.legal.read','admin.legal.write','admin.legal.publish','admin.content.media.write')
ON CONFLICT DO NOTHING;
INSERT INTO gateway_role_permissions(role_id,permission_id)
SELECT DISTINCT rp.role_id,media.permission_id FROM gateway_role_permissions rp
JOIN gateway_permissions source ON source.permission_id=rp.permission_id
CROSS JOIN gateway_permissions media
WHERE source.permission_code IN ('admin.announcements.write','admin.legal.write') AND media.permission_code='admin.content.media.write'
ON CONFLICT DO NOTHING;
