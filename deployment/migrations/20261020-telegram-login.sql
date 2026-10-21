-- Telegram subject is the identity key. Nicknames are presentation only.
ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS display_name TEXT;
ALTER TABLE gateway_users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_telegram_identities (
    subject TEXT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES gateway_users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS gateway_telegram_login_flows (
    state_hash TEXT PRIMARY KEY,
    verifier_hash TEXT NOT NULL,
    nonce TEXT NOT NULL,
    code_verifier TEXT NOT NULL,
    device_id TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    callback_claimed BOOLEAN NOT NULL DEFAULT false,
    subject TEXT,
    display_name TEXT,
    failed BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS gateway_telegram_login_flows_expiry_idx
    ON gateway_telegram_login_flows(expires_at);
