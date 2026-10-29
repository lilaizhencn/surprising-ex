ALTER TABLE gateway_users ADD COLUMN IF NOT EXISTS display_name TEXT;
ALTER TABLE gateway_users ALTER COLUMN password_hash DROP NOT NULL;

-- Google sub is the only identity key; provider email never auto-links an existing account.
CREATE TABLE IF NOT EXISTS gateway_google_identities (
    subject VARCHAR(255) PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES gateway_users(user_id),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE IF NOT EXISTS gateway_google_login_challenges (
    nonce_hash VARCHAR(43) PRIMARY KEY,
    device_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS gateway_google_challenge_expiry ON gateway_google_login_challenges(expires_at);
CREATE INDEX IF NOT EXISTS gateway_google_challenge_device ON gateway_google_login_challenges(device_id);
