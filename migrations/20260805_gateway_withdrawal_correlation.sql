BEGIN;

CREATE TABLE IF NOT EXISTS gateway_wallet_withdrawals (
    withdrawal_id        UUID PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES gateway_users(user_id),
    idempotency_key      TEXT NOT NULL,
    request_sha256       TEXT NOT NULL,
    chain                TEXT NOT NULL,
    asset_symbol         TEXT NOT NULL,
    custody_address_id   UUID NOT NULL,
    to_address           TEXT NOT NULL,
    amount               TEXT NOT NULL,
    amount_units         BIGINT NOT NULL,
    usdt_value            NUMERIC(38,18) NOT NULL,
    external_reference   TEXT,
    spot_debit_reference TEXT NOT NULL,
    request_payload      JSONB NOT NULL,
    status                TEXT NOT NULL,
    wallet_response      JSONB,
    wallet_withdrawal_id TEXT,
    error_code           TEXT,
    error_message        TEXT,
    admin_user_id        BIGINT REFERENCES gateway_users(user_id),
    admin_username       TEXT,
    admin_reason         TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at         TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    CONSTRAINT gateway_wallet_withdrawal_status_check CHECK (
        status IN ('PENDING_APPROVAL', 'PROCESSING', 'DEBIT_UNKNOWN', 'DEBITED', 'SUBMITTED', 'FAILED_PENDING',
                   'BROADCAST_UNKNOWN', 'COMPLETED', 'REJECTED', 'REFUND_PENDING', 'REFUNDED')
    ),
    CONSTRAINT gateway_wallet_withdrawal_amount_check CHECK (amount_units > 0 AND usdt_value > 0),
    CONSTRAINT gateway_wallet_withdrawal_idempotency_uq UNIQUE (user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS gateway_wallet_withdrawals_user_status_idx
    ON gateway_wallet_withdrawals (user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_wallet_withdrawals_wallet_id_idx
    ON gateway_wallet_withdrawals (wallet_withdrawal_id)
    WHERE wallet_withdrawal_id IS NOT NULL;

ALTER TABLE gateway_wallet_withdrawals
    DROP CONSTRAINT IF EXISTS gateway_wallet_withdrawal_status_check;
ALTER TABLE gateway_wallet_withdrawals
    ADD CONSTRAINT gateway_wallet_withdrawal_status_check CHECK (
        status IN ('PENDING_APPROVAL', 'PROCESSING', 'DEBIT_UNKNOWN', 'DEBITED', 'SUBMITTED', 'FAILED_PENDING',
                   'BROADCAST_UNKNOWN', 'COMPLETED', 'REJECTED', 'REFUND_PENDING', 'REFUNDED')
    );

DO $$
BEGIN
    EXECUTE 'ALTER TABLE gateway_wallet_withdrawals
             ADD COLUMN IF NOT EXISTS external_reference TEXT';
    EXECUTE $sql$
        UPDATE gateway_wallet_withdrawals
           SET external_reference = 'custody-wallet-withdrawal:legacy:' || withdrawal_id::text
         WHERE external_reference IS NULL
    $sql$;

    IF EXISTS (
        SELECT 1
          FROM gateway_wallet_withdrawals
         GROUP BY external_reference
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'gateway_wallet_withdrawals contains duplicate external_reference values';
    END IF;

    EXECUTE 'ALTER TABLE gateway_wallet_withdrawals
             ALTER COLUMN external_reference SET NOT NULL';
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'gateway_wallet_withdrawal_external_reference_uq'
           AND conrelid = 'public.gateway_wallet_withdrawals'::regclass
    ) THEN
        EXECUTE 'ALTER TABLE gateway_wallet_withdrawals
                 ADD CONSTRAINT gateway_wallet_withdrawal_external_reference_uq
                 UNIQUE (external_reference)';
    END IF;
END
$$;

INSERT INTO gateway_permissions (permission_code, permission_name, description)
VALUES
    ('admin.wallet.read', 'Read wallet withdrawals', 'View exchange withdrawal intents and custody status.'),
    ('admin.wallet.write', 'Write wallet withdrawals', 'Approve, reject and retry exchange withdrawals.')
ON CONFLICT (permission_code) DO UPDATE
   SET permission_name = EXCLUDED.permission_name,
       description = EXCLUDED.description;

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code IN ('admin.wallet.read', 'admin.wallet.write')
 WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
