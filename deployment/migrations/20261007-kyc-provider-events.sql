CREATE TABLE IF NOT EXISTS gateway_kyc_provider_events (
    provider TEXT NOT NULL,
    event_key TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_key)
);

CREATE INDEX IF NOT EXISTS gateway_kyc_provider_events_received_idx
    ON gateway_kyc_provider_events (received_at DESC);
