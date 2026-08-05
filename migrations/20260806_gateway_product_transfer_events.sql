CREATE SEQUENCE IF NOT EXISTS gateway_product_transfer_event_seq;

CREATE TABLE IF NOT EXISTS gateway_product_transfer_events (
    event_id       BIGINT PRIMARY KEY DEFAULT nextval('gateway_product_transfer_event_seq'),
    transfer_id    BIGINT NOT NULL REFERENCES gateway_product_transfers(transfer_id),
    from_status    VARCHAR(32),
    to_status      VARCHAR(32) NOT NULL,
    error_code     VARCHAR(64),
    error_message  VARCHAR(512),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS gateway_product_transfer_events_transfer_time_idx
    ON gateway_product_transfer_events (transfer_id, created_at, event_id);
