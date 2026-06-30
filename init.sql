-- Surprising Exchange initial PostgreSQL schema.
-- This project is new, so schema initialization is kept in one root SQL file.

CREATE TABLE IF NOT EXISTS candlestick_symbols (
    symbol              TEXT PRIMARY KEY,
    base_asset          TEXT,
    quote_asset         TEXT,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT candlestick_symbols_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$')
);

CREATE TABLE IF NOT EXISTS candlestick_candles (
    symbol              TEXT NOT NULL,
    period              TEXT NOT NULL,
    open_time           TIMESTAMPTZ NOT NULL,
    close_time          TIMESTAMPTZ NOT NULL,
    open_price          NUMERIC(38, 18) NOT NULL,
    high_price          NUMERIC(38, 18) NOT NULL,
    low_price           NUMERIC(38, 18) NOT NULL,
    close_price         NUMERIC(38, 18) NOT NULL,
    base_volume         NUMERIC(38, 18) NOT NULL,
    quote_volume        NUMERIC(38, 18) NOT NULL,
    trade_count         BIGINT NOT NULL,
    first_trade_id      TEXT,
    last_trade_id       TEXT,
    first_sequence      BIGINT,
    last_sequence       BIGINT,
    status              TEXT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    source_partition    INTEGER,
    source_offset       BIGINT,
    PRIMARY KEY (symbol, period, open_time),
    CONSTRAINT candlestick_candles_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT candlestick_candles_period_format CHECK (period ~ '^[0-9]+[mhdw]$'),
    CONSTRAINT candlestick_candles_positive_prices CHECK (
        open_price > 0 AND high_price > 0 AND low_price > 0 AND close_price > 0
    ),
    CONSTRAINT candlestick_candles_non_negative_volume CHECK (
        base_volume >= 0 AND quote_volume >= 0 AND trade_count >= 0
    ),
    CONSTRAINT candlestick_candles_valid_ohlc CHECK (
        high_price >= open_price AND high_price >= close_price AND high_price >= low_price
        AND low_price <= open_price AND low_price <= close_price AND low_price <= high_price
    ),
    CONSTRAINT candlestick_candles_status CHECK (status IN ('PARTIAL', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS candlestick_candles_query_desc_idx
    ON candlestick_candles (symbol, period, open_time DESC);

CREATE INDEX IF NOT EXISTS candlestick_candles_updated_idx
    ON candlestick_candles (updated_at DESC);

CREATE TABLE IF NOT EXISTS price_index_ticks (
    symbol                  TEXT NOT NULL,
    sequence                BIGINT NOT NULL,
    index_price             NUMERIC(38, 18),
    status                  TEXT NOT NULL,
    component_count         INTEGER NOT NULL,
    valid_component_count   INTEGER NOT NULL,
    total_configured_weight NUMERIC(38, 18) NOT NULL,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, sequence),
    CONSTRAINT price_index_ticks_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT price_index_ticks_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'STALE', 'INSUFFICIENT_SOURCES', 'CLAMPED'))
);

CREATE INDEX IF NOT EXISTS price_index_ticks_query_idx
    ON price_index_ticks (symbol, event_time DESC);

CREATE TABLE IF NOT EXISTS price_index_components (
    symbol              TEXT NOT NULL,
    sequence            BIGINT NOT NULL,
    source              TEXT NOT NULL,
    source_symbol       TEXT NOT NULL,
    price               NUMERIC(38, 18),
    bid_price           NUMERIC(38, 18),
    ask_price           NUMERIC(38, 18),
    configured_weight   NUMERIC(38, 18) NOT NULL,
    effective_weight    NUMERIC(38, 18) NOT NULL,
    status              TEXT NOT NULL,
    reason              TEXT,
    source_time         TIMESTAMPTZ,
    received_at         TIMESTAMPTZ,
    latency_millis      BIGINT,
    PRIMARY KEY (symbol, sequence, source),
    CONSTRAINT price_index_components_status CHECK (status IN ('HEALTHY', 'DISABLED', 'STALE', 'OUTLIER', 'ERROR'))
);

CREATE INDEX IF NOT EXISTS price_index_components_query_idx
    ON price_index_components (symbol, sequence);

CREATE TABLE IF NOT EXISTS price_symbol_leases (
    module              TEXT NOT NULL,
    symbol              TEXT NOT NULL,
    owner_id            TEXT NOT NULL,
    lease_until         TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (module, symbol),
    CONSTRAINT price_symbol_leases_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$')
);

CREATE INDEX IF NOT EXISTS price_symbol_leases_expiry_idx
    ON price_symbol_leases (module, lease_until);

CREATE TABLE IF NOT EXISTS price_symbol_sequences (
    module              TEXT NOT NULL,
    symbol              TEXT NOT NULL,
    sequence            BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (module, symbol),
    CONSTRAINT price_symbol_sequences_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT price_symbol_sequences_positive CHECK (sequence > 0)
);

CREATE TABLE IF NOT EXISTS price_exchange_rates (
    base_currency       TEXT NOT NULL,
    quote_currency      TEXT NOT NULL,
    rate                NUMERIC(38, 18) NOT NULL,
    provider            TEXT NOT NULL,
    rate_time           TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (base_currency, quote_currency),
    CONSTRAINT price_exchange_rates_currency_format CHECK (
        base_currency ~ '^[A-Z]{2,10}$' AND quote_currency ~ '^[A-Z]{2,10}$'
    ),
    CONSTRAINT price_exchange_rates_positive_rate CHECK (rate > 0)
);

CREATE INDEX IF NOT EXISTS price_exchange_rates_base_idx
    ON price_exchange_rates (base_currency, quote_currency);

CREATE INDEX IF NOT EXISTS price_exchange_rates_updated_idx
    ON price_exchange_rates (updated_at DESC);

CREATE TABLE IF NOT EXISTS price_mark_ticks (
    symbol                      TEXT NOT NULL,
    sequence                    BIGINT NOT NULL,
    mark_price                  NUMERIC(38, 18) NOT NULL,
    index_price                 NUMERIC(38, 18) NOT NULL,
    price1                      NUMERIC(38, 18) NOT NULL,
    price2                      NUMERIC(38, 18) NOT NULL,
    last_trade_price            NUMERIC(38, 18) NOT NULL,
    best_bid_price              NUMERIC(38, 18) NOT NULL,
    best_ask_price              NUMERIC(38, 18) NOT NULL,
    funding_rate                NUMERIC(38, 18) NOT NULL,
    next_funding_time           TIMESTAMPTZ NOT NULL,
    time_until_funding_seconds  BIGINT NOT NULL,
    basis_average               NUMERIC(38, 18) NOT NULL,
    basis_window_seconds        BIGINT NOT NULL,
    clamp_low                   NUMERIC(38, 18) NOT NULL,
    clamp_high                  NUMERIC(38, 18) NOT NULL,
    status                      TEXT NOT NULL,
    event_time                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, sequence),
    CONSTRAINT price_mark_ticks_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT price_mark_ticks_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'STALE', 'INSUFFICIENT_SOURCES', 'CLAMPED')),
    CONSTRAINT price_mark_ticks_valid_book CHECK (best_bid_price <= best_ask_price),
    CONSTRAINT price_mark_ticks_valid_clamp CHECK (clamp_low <= mark_price AND mark_price <= clamp_high)
);

CREATE INDEX IF NOT EXISTS price_mark_ticks_query_idx
    ON price_mark_ticks (symbol, event_time DESC);
