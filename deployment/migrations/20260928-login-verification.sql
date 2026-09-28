-- 登录二次验证：联系方式验证状态与一次性挑战由数据库持有。
CREATE TABLE IF NOT EXISTS gateway_login_factors (
 user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
 method TEXT NOT NULL CHECK (method IN ('EMAIL','PHONE')),
 destination TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 verified_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id, method)
);
CREATE TABLE IF NOT EXISTS gateway_login_challenges (
 user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
 purpose TEXT NOT NULL CHECK (purpose IN ('LOGIN','EMAIL','PHONE','TOTP')),
 token_hash TEXT NOT NULL UNIQUE,
 fingerprint TEXT NOT NULL,
 methods TEXT NOT NULL,
 email_hash TEXT,
 phone_hash TEXT,
 destination TEXT,
 expires_at TIMESTAMPTZ NOT NULL,
 target_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 consumed BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INTEGER NOT NULL DEFAULT 0,
 issued INTEGER NOT NULL DEFAULT 0,
 window_start TIMESTAMPTZ NOT NULL,
 issued_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(user_id,purpose)
);
ALTER TABLE gateway_user_mfa ADD COLUMN IF NOT EXISTS last_login_step BIGINT NOT NULL DEFAULT -1;
