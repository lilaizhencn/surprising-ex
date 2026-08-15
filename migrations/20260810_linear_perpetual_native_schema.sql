BEGIN;

ALTER TABLE trading_orders
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS reservation_account_type TEXT,
    ADD COLUMN IF NOT EXISTS reservation_asset TEXT,
    ADD COLUMN IF NOT EXISTS reserved_units BIGINT NOT NULL DEFAULT 0;

ALTER TABLE account_product_deficits
    ADD COLUMN IF NOT EXISTS reserved_units BIGINT NOT NULL DEFAULT 0;

ALTER TABLE insurance_fund_balances
    ADD COLUMN IF NOT EXISTS reserved_units BIGINT NOT NULL DEFAULT 0;

ALTER TABLE risk_liquidation_candidates
    ADD COLUMN IF NOT EXISTS position_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE funding_settlements
    ADD COLUMN IF NOT EXISTS instrument_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS mark_price_ticks BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS expected_payment_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS applied_payment_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rejected_payment_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS scan_user_id BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS scan_margin_mode TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS scan_position_side TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS scan_completed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE funding_settlements
    DROP CONSTRAINT IF EXISTS funding_settlements_status_check;

ALTER TABLE funding_settlements
    ADD CONSTRAINT funding_settlements_status_check CHECK (
        status IN ('PROCESSING', 'WAITING_ACCOUNTS', 'COMPLETED', 'FAILED')
    );

CREATE TABLE IF NOT EXISTS account_risk_state_revisions (
    product_line TEXT NOT NULL,
    user_id      BIGINT NOT NULL,
    revision     BIGINT NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, user_id),
    CONSTRAINT account_risk_state_revisions_user_positive CHECK (user_id > 0),
    CONSTRAINT account_risk_state_revisions_revision_positive CHECK (revision > 0)
);

CREATE TABLE IF NOT EXISTS account_state_order_locks (
    product_line TEXT NOT NULL,
    user_id      BIGINT NOT NULL,
    asset        TEXT NOT NULL,
    locked_units BIGINT NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, user_id, asset),
    CONSTRAINT account_state_order_locks_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_state_order_locks_user_positive CHECK (user_id > 0),
    CONSTRAINT account_state_order_locks_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_state_order_locks_non_negative CHECK (locked_units >= 0)
);

CREATE INDEX IF NOT EXISTS account_state_order_locks_user_idx
    ON account_state_order_locks (product_line, user_id);

CREATE TABLE IF NOT EXISTS account_commands (
    command_id              VARCHAR(160) PRIMARY KEY,
    product_line            TEXT NOT NULL,
    user_id                 BIGINT NOT NULL,
    command_type            TEXT NOT NULL,
    source                  VARCHAR(64) NOT NULL,
    source_reference        VARCHAR(160) NOT NULL,
    depends_on_command_id   VARCHAR(160),
    payload                 JSONB NOT NULL,
    payload_sha256          CHAR(64) NOT NULL,
    status                  TEXT NOT NULL,
    result_payload          JSONB,
    error_code              VARCHAR(80),
    error_message           VARCHAR(1000),
    occurred_at             TIMESTAMPTZ NOT NULL,
    started_at              TIMESTAMPTZ NOT NULL,
    completed_at            TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL,
    trace_id                VARCHAR(160),
    CONSTRAINT account_commands_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_commands_user_positive CHECK (user_id > 0),
    CONSTRAINT account_commands_status_check CHECK (
        status IN ('WAITING_DEPENDENCY', 'PROCESSING', 'APPLIED', 'REJECTED')
    ),
    CONSTRAINT account_commands_terminal_check CHECK (
        (status IN ('WAITING_DEPENDENCY', 'PROCESSING') AND completed_at IS NULL)
        OR (status IN ('APPLIED', 'REJECTED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT account_commands_dependency_not_self CHECK (
        depends_on_command_id IS NULL OR depends_on_command_id <> command_id
    )
);

CREATE INDEX IF NOT EXISTS account_commands_user_time_idx
    ON account_commands (product_line, user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS account_commands_source_idx
    ON account_commands (source, source_reference, command_type);

CREATE INDEX IF NOT EXISTS account_commands_processing_idx
    ON account_commands (product_line, started_at)
    WHERE status IN ('WAITING_DEPENDENCY', 'PROCESSING');

CREATE INDEX IF NOT EXISTS account_commands_dependency_idx
    ON account_commands (depends_on_command_id, started_at)
    WHERE status = 'WAITING_DEPENDENCY';

ALTER TABLE funding_payments
    ADD COLUMN IF NOT EXISTS command_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS applied_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE funding_payments
   SET command_id = 'funding-payment:' || payment_id
 WHERE command_id IS NULL;

ALTER TABLE funding_payments
    ALTER COLUMN command_id SET NOT NULL;

ALTER TABLE funding_payments
    DROP CONSTRAINT IF EXISTS funding_payments_status_check,
    DROP CONSTRAINT IF EXISTS funding_payments_terminal_check;

ALTER TABLE funding_payments
    ADD CONSTRAINT funding_payments_status_check CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    ADD CONSTRAINT funding_payments_terminal_check CHECK (
        (status = 'PENDING' AND applied_at IS NULL AND rejected_at IS NULL)
        OR (status = 'APPLIED' AND applied_at IS NOT NULL AND rejected_at IS NULL)
        OR (status = 'REJECTED' AND applied_at IS NULL AND rejected_at IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS funding_payments_settlement_user_uidx
    ON funding_payments (settlement_id, user_id, symbol, margin_mode, position_side);

CREATE UNIQUE INDEX IF NOT EXISTS funding_payments_command_uidx
    ON funding_payments (command_id);

CREATE INDEX IF NOT EXISTS funding_payments_user_time_idx
    ON funding_payments (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS funding_payments_pending_idx
    ON funding_payments (settlement_id, payment_id)
    WHERE status = 'PENDING';

ALTER TABLE insurance_deficit_coverages
    ADD COLUMN IF NOT EXISTS reserve_command_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS finalize_command_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

UPDATE insurance_deficit_coverages
   SET reserve_command_id = COALESCE(reserve_command_id, 'reserve:' || coverage_id),
       finalize_command_id = COALESCE(finalize_command_id, 'finalize:' || coverage_id)
 WHERE reserve_command_id IS NULL
    OR finalize_command_id IS NULL;

ALTER TABLE insurance_deficit_coverages
    ALTER COLUMN reserve_command_id SET NOT NULL,
    ALTER COLUMN finalize_command_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS insurance_coverages_reserve_command_uidx
    ON insurance_deficit_coverages (reserve_command_id);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_coverages_finalize_command_uidx
    ON insurance_deficit_coverages (finalize_command_id);

CREATE TABLE IF NOT EXISTS adl_execution_sagas (
    execution_id                BIGINT PRIMARY KEY,
    product_line                TEXT NOT NULL,
    account_type                TEXT NOT NULL,
    deficit_user_id             BIGINT NOT NULL,
    target_user_id              BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    symbol                      TEXT NOT NULL,
    target_side                 TEXT NOT NULL,
    target_margin_mode          TEXT NOT NULL,
    target_position_side        TEXT NOT NULL,
    expected_signed_steps       BIGINT NOT NULL,
    closed_quantity_steps       BIGINT NOT NULL,
    entry_price_ticks            BIGINT NOT NULL,
    mark_price_ticks              BIGINT NOT NULL,
    requested_deficit_units      BIGINT NOT NULL,
    realized_profit_units        BIGINT NOT NULL,
    covered_units                BIGINT NOT NULL,
    priority_score_ppm           BIGINT NOT NULL,
    reserve_command_id           VARCHAR(160) NOT NULL UNIQUE,
    target_command_id            VARCHAR(160) NOT NULL UNIQUE,
    finalize_command_id          VARCHAR(160) NOT NULL UNIQUE,
    release_command_id           VARCHAR(160) UNIQUE,
    status                       TEXT NOT NULL,
    error_code                   VARCHAR(80),
    error_message               VARCHAR(1000),
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    completed_at                TIMESTAMPTZ,
    CONSTRAINT adl_execution_product_line_check CHECK (
        product_line IN ('LINEAR_PERPETUAL', 'INVERSE_PERPETUAL')
    ),
    CONSTRAINT adl_execution_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL')
    ),
    CONSTRAINT adl_execution_users_check CHECK (
        deficit_user_id > 0 AND target_user_id > 0 AND deficit_user_id <> target_user_id
    ),
    CONSTRAINT adl_execution_status_check CHECK (
        status IN ('PENDING', 'RELEASING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT adl_execution_values_check CHECK (
        expected_signed_steps <> 0 AND closed_quantity_steps > 0
        AND requested_deficit_units > 0 AND realized_profit_units > 0
        AND covered_units > 0 AND covered_units <= realized_profit_units
    )
);

CREATE INDEX IF NOT EXISTS adl_execution_sagas_pending_idx
    ON adl_execution_sagas (product_line, created_at, execution_id)
    WHERE status IN ('PENDING', 'RELEASING');

COMMIT;
