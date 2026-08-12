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
