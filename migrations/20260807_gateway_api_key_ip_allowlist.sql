ALTER TABLE gateway_api_keys
    ADD COLUMN IF NOT EXISTS ip_allowlist TEXT NOT NULL DEFAULT '';
