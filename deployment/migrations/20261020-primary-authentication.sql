-- Preserve the original primary verification time across refresh rotation.
-- Existing sessions intentionally remain NULL: refresh cannot confer a fresh proof.
ALTER TABLE gateway_refresh_sessions ADD COLUMN IF NOT EXISTS authenticated_at TIMESTAMPTZ;
COMMENT ON COLUMN gateway_refresh_sessions.authenticated_at IS
    'Original completed primary login verification time; inherited unchanged on refresh rotation.';
