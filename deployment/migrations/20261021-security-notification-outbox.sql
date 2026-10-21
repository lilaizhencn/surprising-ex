CREATE TABLE IF NOT EXISTS gateway_security_email_notifications (
    notification_id UUID PRIMARY KEY,
    event_key TEXT NOT NULL UNIQUE,
    recipient TEXT NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','SENT')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    last_failure TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT gateway_security_email_lease_check CHECK (
        (status='SENDING') = (lease_id IS NOT NULL AND lease_until IS NOT NULL)
    ),
    CONSTRAINT gateway_security_email_sent_check CHECK ((status='SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS gateway_security_email_pending_idx
    ON gateway_security_email_notifications(next_attempt_at,notification_id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS gateway_security_email_lease_idx
    ON gateway_security_email_notifications(lease_until,notification_id) WHERE status='SENDING';
CREATE INDEX IF NOT EXISTS gateway_security_email_sent_idx
    ON gateway_security_email_notifications(sent_at,notification_id) WHERE status='SENT';
