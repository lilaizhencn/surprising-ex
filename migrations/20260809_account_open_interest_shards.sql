BEGIN;

CREATE SEQUENCE IF NOT EXISTS account_open_interest_revision_seq
    AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = 'trading_symbol_open_interest'
           AND c.relkind = 'r'
    ) THEN
        IF to_regclass('public.trading_symbol_open_interest_legacy') IS NULL THEN
            ALTER TABLE trading_symbol_open_interest RENAME TO trading_symbol_open_interest_legacy;
        END IF;
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS trading_symbol_open_interest_shards (
    product_line         TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    symbol               TEXT NOT NULL,
    shard_id             SMALLINT NOT NULL,
    long_quantity_steps  BIGINT NOT NULL DEFAULT 0,
    short_quantity_steps BIGINT NOT NULL DEFAULT 0,
    cache_revision       BIGINT NOT NULL DEFAULT nextval('account_open_interest_revision_seq'),
    updated_at           TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, symbol, shard_id),
    CONSTRAINT trading_symbol_open_interest_shards_symbol_fk
        FOREIGN KEY (symbol) REFERENCES instrument_current_versions(symbol),
    CONSTRAINT trading_symbol_open_interest_shards_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_symbol_open_interest_shards_shard_check CHECK (shard_id >= 0 AND shard_id < 64),
    CONSTRAINT trading_symbol_open_interest_shards_non_negative CHECK (
        long_quantity_steps >= 0 AND short_quantity_steps >= 0
    )
);

ALTER TABLE trading_symbol_open_interest_shards
    ADD COLUMN IF NOT EXISTS cache_revision BIGINT NOT NULL DEFAULT nextval('account_open_interest_revision_seq');

DO $$
BEGIN
    IF to_regclass('public.trading_symbol_open_interest_legacy') IS NOT NULL THEN
        INSERT INTO trading_symbol_open_interest_shards (
            product_line, symbol, shard_id, long_quantity_steps, short_quantity_steps, updated_at
        )
        SELECT product_line, symbol, 0, long_quantity_steps, short_quantity_steps, updated_at
          FROM trading_symbol_open_interest_legacy
        ON CONFLICT (product_line, symbol, shard_id) DO UPDATE
            SET long_quantity_steps = EXCLUDED.long_quantity_steps,
                short_quantity_steps = EXCLUDED.short_quantity_steps,
                updated_at = EXCLUDED.updated_at;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS trading_symbol_open_interest_shards_revision_idx
    ON trading_symbol_open_interest_shards (product_line, cache_revision);

CREATE OR REPLACE VIEW trading_symbol_open_interest AS
SELECT product_line,
       symbol,
       COALESCE(SUM(long_quantity_steps), 0)::BIGINT AS long_quantity_steps,
       COALESCE(SUM(short_quantity_steps), 0)::BIGINT AS short_quantity_steps,
       GREATEST(
           COALESCE(SUM(long_quantity_steps), 0),
           COALESCE(SUM(short_quantity_steps), 0)
       )::BIGINT AS open_quantity_steps,
       MAX(updated_at) AS updated_at
  FROM trading_symbol_open_interest_shards
 GROUP BY product_line, symbol;

COMMIT;
