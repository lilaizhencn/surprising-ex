-- Security changes keep withdrawals unavailable for 24 hours.
ALTER TABLE gateway_users
    ADD COLUMN IF NOT EXISTS withdrawal_restricted_until TIMESTAMPTZ;

ALTER TABLE gateway_auth_challenges DROP CONSTRAINT IF EXISTS gateway_auth_challenges_purpose_check;
ALTER TABLE gateway_auth_challenges ADD CONSTRAINT gateway_auth_challenges_purpose_check
    CHECK (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET', 'LOGIN', 'SENSITIVE_ACTION', 'MFA_RECOVERY'));

CREATE TABLE IF NOT EXISTS gateway_mfa_recovery_requests (
    request_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES gateway_users(user_id),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by_user_id BIGINT REFERENCES gateway_users(user_id),
    reviewed_at TIMESTAMPTZ,
    decision_reason TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS gateway_mfa_recovery_pending_user_uidx
    ON gateway_mfa_recovery_requests(user_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS gateway_mfa_recovery_queue_idx
    ON gateway_mfa_recovery_requests(status, submitted_at);
