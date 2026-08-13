ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS phone TEXT;
ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ;
ALTER TABLE gateway_users ALTER COLUMN username DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS gateway_users_phone_uidx
    ON gateway_users (phone)
    WHERE phone IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_auth_challenges (
    challenge_id        BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    purpose             TEXT NOT NULL,
    channel             TEXT NOT NULL,
    destination         TEXT NOT NULL,
    code_hash           TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    request_ip          INET,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_auth_challenges_purpose_check CHECK (
        purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET', 'LOGIN', 'SENSITIVE_ACTION')
    ),
    CONSTRAINT gateway_auth_challenges_channel_check CHECK (channel IN ('EMAIL', 'PHONE')),
    CONSTRAINT gateway_auth_challenges_attempts_check CHECK (attempts BETWEEN 0 AND 5)
);

CREATE INDEX IF NOT EXISTS gateway_auth_challenges_active_idx
    ON gateway_auth_challenges (user_id, purpose, destination, created_at DESC)
    WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS gateway_user_security_scenes (
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    scene_code          TEXT NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, scene_code),
    CONSTRAINT gateway_user_security_scene_code_check CHECK (
        scene_code IN ('LOGIN', 'CHANGE_PASSWORD', 'SECURITY_SETTINGS', 'WITHDRAWAL',
                       'API_WITHDRAWAL', 'WHITELIST', 'LARGE_TRANSFER', 'TRANSFER')
    )
);

CREATE INDEX IF NOT EXISTS gateway_user_security_scenes_updated_idx
    ON gateway_user_security_scenes (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_api_keys (
    api_key_id          UUID PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    api_key             TEXT NOT NULL UNIQUE,
    secret_ciphertext   TEXT NOT NULL,
    label               TEXT NOT NULL,
    permissions         TEXT NOT NULL DEFAULT 'READ',
    ip_allowlist        TEXT NOT NULL DEFAULT '',
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    CONSTRAINT gateway_api_key_status_check CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT gateway_api_key_label_check CHECK (length(label) BETWEEN 1 AND 80),
    CONSTRAINT gateway_api_key_permissions_check CHECK (permissions ~ '^(READ|TRADE|WITHDRAW)(,(READ|TRADE|WITHDRAW))*$')
);

CREATE INDEX IF NOT EXISTS gateway_api_keys_user_status_idx
    ON gateway_api_keys (user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS gateway_wallet_webhook_events (
    event_id            TEXT PRIMARY KEY,
    event_type          TEXT NOT NULL,
    body_sha256         TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PROCESSING',
    attempts            INTEGER NOT NULL DEFAULT 1,
    error_message       TEXT,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    CONSTRAINT gateway_wallet_webhook_status_check CHECK (
        status IN ('PROCESSING', 'PROCESSED', 'FAILED')
    )
);

CREATE INDEX IF NOT EXISTS gateway_wallet_webhook_events_status_idx
    ON gateway_wallet_webhook_events (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_user_kyc_documents (
    document_id         BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    document_type       TEXT NOT NULL,
    original_filename   TEXT NOT NULL,
    content_type        TEXT NOT NULL,
    file_size           BIGINT NOT NULL,
    sha256              TEXT NOT NULL,
    object_key          TEXT NOT NULL UNIQUE,
    status              TEXT NOT NULL DEFAULT 'UPLOADED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT gateway_user_kyc_documents_type_check CHECK (
        document_type IN ('ID_CARD', 'PASSPORT', 'ADDRESS_PROOF', 'BUSINESS_LICENSE', 'FACE_IMAGE')
    ),
    CONSTRAINT gateway_user_kyc_documents_content_type_check CHECK (
        content_type IN ('application/pdf', 'image/jpeg', 'image/png')
    ),
    CONSTRAINT gateway_user_kyc_documents_size_check CHECK (file_size > 0),
    CONSTRAINT gateway_user_kyc_documents_sha256_check CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT gateway_user_kyc_documents_status_check CHECK (
        status IN ('UPLOADED', 'SUBMITTED', 'DELETED')
    )
);

CREATE INDEX IF NOT EXISTS gateway_user_kyc_documents_user_idx
    ON gateway_user_kyc_documents (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_kyc_documents_status_idx
    ON gateway_user_kyc_documents (status, created_at DESC);

CREATE SEQUENCE IF NOT EXISTS gateway_user_notification_seq;

CREATE TABLE IF NOT EXISTS gateway_user_notifications (
    notification_id BIGINT PRIMARY KEY DEFAULT nextval('gateway_user_notification_seq'),
    user_id         BIGINT NOT NULL REFERENCES gateway_users(user_id),
    category        VARCHAR(32) NOT NULL,
    title           VARCHAR(160) NOT NULL,
    body            VARCHAR(4000) NOT NULL,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_notifications_category_check CHECK (
        category IN ('SYSTEM', 'SECURITY', 'TRADING', 'FUNDING', 'ACCOUNT', 'COMPLIANCE')
    )
);

CREATE INDEX IF NOT EXISTS gateway_user_notifications_user_time_idx
    ON gateway_user_notifications (user_id, created_at DESC, notification_id DESC);

CREATE INDEX IF NOT EXISTS gateway_user_notifications_unread_idx
    ON gateway_user_notifications (user_id, notification_id)
    WHERE read_at IS NULL;
