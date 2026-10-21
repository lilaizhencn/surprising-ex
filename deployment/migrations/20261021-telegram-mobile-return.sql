-- Preserve web flows; only mobile-initiated authorization returns to the app.
ALTER TABLE gateway_telegram_login_flows
    ADD COLUMN IF NOT EXISTS return_to_app BOOLEAN NOT NULL DEFAULT false;
