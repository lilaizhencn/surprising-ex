-- Surprising Exchange initial PostgreSQL schema.
-- This project is new, so schema initialization is kept in one root SQL file.

CREATE TABLE IF NOT EXISTS instruments (
    symbol                      TEXT NOT NULL,
    version                     BIGINT NOT NULL,
    instrument_type             TEXT NOT NULL,
    contract_type               TEXT NOT NULL,
    base_asset                  TEXT NOT NULL,
    quote_asset                 TEXT NOT NULL,
    settle_asset                TEXT NOT NULL,
    contract_multiplier_ppm     BIGINT NOT NULL,
    contract_value_asset        TEXT NOT NULL,
    price_tick_units            BIGINT NOT NULL,
    quantity_step_units         BIGINT NOT NULL,
    min_quantity_steps          BIGINT NOT NULL,
    max_quantity_steps          BIGINT NOT NULL,
    min_notional_units          BIGINT NOT NULL,
    max_notional_units          BIGINT NOT NULL,
    notional_multiplier_units   BIGINT NOT NULL,
    price_precision             INTEGER NOT NULL,
    quantity_precision          INTEGER NOT NULL,
    supported_order_types       TEXT NOT NULL,
    supported_time_in_force     TEXT NOT NULL,
    post_only_enabled           BOOLEAN NOT NULL,
    reduce_only_enabled         BOOLEAN NOT NULL,
    market_order_enabled        BOOLEAN NOT NULL,
    max_leverage_ppm            BIGINT NOT NULL,
    initial_margin_rate_ppm     BIGINT NOT NULL,
    maintenance_margin_rate_ppm BIGINT NOT NULL,
    maker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    taker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    max_position_notional_units BIGINT NOT NULL,
    funding_interval_hours      INTEGER NOT NULL,
    interest_rate_ppm           BIGINT NOT NULL,
    funding_rate_cap_ppm        BIGINT NOT NULL,
    funding_rate_floor_ppm      BIGINT NOT NULL,
    impact_notional_units       BIGINT NOT NULL,
    min_valid_index_sources     INTEGER NOT NULL DEFAULT 3,
    status                      TEXT NOT NULL,
    effective_time              TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (symbol, version),
    CONSTRAINT instruments_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT instruments_type_check CHECK (instrument_type IN ('PERPETUAL')),
    CONSTRAINT instruments_contract_type_check CHECK (contract_type IN ('LINEAR_PERPETUAL', 'INVERSE_PERPETUAL')),
    CONSTRAINT instruments_status_check CHECK (status IN ('PRE_TRADING', 'TRADING', 'HALT', 'SETTLING', 'CLOSED')),
    CONSTRAINT instruments_positive_values CHECK (
        contract_multiplier_ppm > 0
        AND price_tick_units > 0
        AND quantity_step_units > 0
        AND min_quantity_steps > 0
        AND max_quantity_steps >= min_quantity_steps
        AND min_notional_units > 0
        AND max_notional_units >= min_notional_units
        AND notional_multiplier_units > 0
        AND max_leverage_ppm > 0
        AND initial_margin_rate_ppm > 0
        AND maintenance_margin_rate_ppm > 0
        AND maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND max_position_notional_units > 0
        AND funding_interval_hours > 0
        AND funding_rate_cap_ppm >= funding_rate_floor_ppm
        AND impact_notional_units > 0
        AND min_valid_index_sources > 0
    )
);

CREATE INDEX IF NOT EXISTS instruments_status_idx
    ON instruments (status, instrument_type, symbol);

CREATE TABLE IF NOT EXISTS instrument_current_versions (
    symbol              TEXT PRIMARY KEY,
    version             BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT instrument_current_versions_symbol_version_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version)
);

CREATE TABLE IF NOT EXISTS instrument_symbol_sequences (
    symbol              TEXT PRIMARY KEY,
    version             BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT instrument_symbol_sequences_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT instrument_symbol_sequences_positive CHECK (version > 0)
);

CREATE TABLE IF NOT EXISTS instrument_risk_brackets (
    symbol                  TEXT NOT NULL,
    version                 BIGINT NOT NULL,
    bracket_no              INTEGER NOT NULL,
    notional_floor_units    BIGINT NOT NULL,
    notional_cap_units      BIGINT NOT NULL,
    max_leverage_ppm        BIGINT NOT NULL,
    initial_margin_rate_ppm BIGINT NOT NULL,
    maintenance_margin_rate_ppm BIGINT NOT NULL,
    PRIMARY KEY (symbol, version, bracket_no),
    CONSTRAINT instrument_risk_brackets_instrument_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version),
    CONSTRAINT instrument_risk_brackets_positive CHECK (
        bracket_no > 0
        AND notional_floor_units >= 0
        AND notional_cap_units > notional_floor_units
        AND max_leverage_ppm > 0
        AND initial_margin_rate_ppm > 0
        AND maintenance_margin_rate_ppm > 0
    )
);

CREATE TABLE IF NOT EXISTS instrument_index_sources (
    symbol                      TEXT NOT NULL,
    version                     BIGINT NOT NULL,
    source                      TEXT NOT NULL,
    enabled                     BOOLEAN NOT NULL,
    base_url                    TEXT NOT NULL,
    path                        TEXT NOT NULL,
    source_symbol               TEXT NOT NULL,
    parser                      TEXT NOT NULL,
    quote_currency              TEXT NOT NULL DEFAULT 'USDT',
    target_quote_currency       TEXT NOT NULL DEFAULT 'USDT',
    conversion_base_url         TEXT,
    conversion_path             TEXT,
    conversion_parser           TEXT,
    conversion_mode             TEXT NOT NULL DEFAULT 'DISCOUNT',
    conversion_operation        TEXT NOT NULL DEFAULT 'MULTIPLY',
    fallback_weight_multiplier_ppm BIGINT NOT NULL DEFAULT 500000,
    websocket_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    websocket_url               TEXT,
    websocket_subscribe_message TEXT,
    websocket_parser            TEXT,
    weight_ppm                  BIGINT NOT NULL,
    PRIMARY KEY (symbol, version, source),
    CONSTRAINT instrument_index_sources_instrument_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version),
    CONSTRAINT instrument_index_sources_positive_weight CHECK (
        weight_ppm > 0 AND fallback_weight_multiplier_ppm >= 0
    ),
    CONSTRAINT instrument_index_sources_conversion_mode CHECK (conversion_mode IN ('DISCOUNT', 'DISABLE')),
    CONSTRAINT instrument_index_sources_conversion_operation CHECK (conversion_operation IN ('MULTIPLY', 'DIVIDE'))
);

CREATE INDEX IF NOT EXISTS instrument_index_sources_enabled_idx
    ON instrument_index_sources (symbol, version, enabled);

INSERT INTO instruments (
    symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
    contract_multiplier_ppm, contract_value_asset, price_tick_units, quantity_step_units,
    min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,
    notional_multiplier_units,
    price_precision, quantity_precision, supported_order_types, supported_time_in_force,
    post_only_enabled, reduce_only_enabled, market_order_enabled,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm,
    maker_fee_rate_ppm, taker_fee_rate_ppm, max_position_notional_units,
    funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
    impact_notional_units, min_valid_index_sources, status, effective_time, created_at, updated_at
) VALUES
('BTC-USDT', 1, 'PERPETUAL', 'LINEAR_PERPETUAL', 'BTC', 'USDT', 'USDT',
 1000000, 'USDT', 10000000, 100000, 1, 100000, 500000000, 1000000000000000, 10000, 1, 3,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 500000000000000, 8, 100, 3000, -3000,
 1000000000000, 3, 'TRADING', now(), now(), now()),
('ETH-USDT', 1, 'PERPETUAL', 'LINEAR_PERPETUAL', 'ETH', 'USDT', 'USDT',
 1000000, 'USDT', 1000000, 10000000000000000, 1, 500000, 500000000, 1000000000000000, 10000, 2, 2,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 500000000000000, 8, 100, 3000, -3000,
 1000000000000, 3, 'TRADING', now(), now(), now())
ON CONFLICT (symbol, version) DO NOTHING;

INSERT INTO instrument_current_versions (symbol, version, updated_at)
VALUES ('BTC-USDT', 1, now()), ('ETH-USDT', 1, now())
ON CONFLICT (symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_symbol_sequences (symbol, version, updated_at)
VALUES ('BTC-USDT', 1, now()), ('ETH-USDT', 1, now())
ON CONFLICT (symbol) DO UPDATE SET
    version = GREATEST(instrument_symbol_sequences.version, EXCLUDED.version),
    updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_risk_brackets (
    symbol, version, bracket_no, notional_floor_units, notional_cap_units,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
) VALUES
('BTC-USDT', 1, 1, 0, 5000000000000, 100000000, 10000, 5000),
('BTC-USDT', 1, 2, 5000000000000, 25000000000000, 50000000, 20000, 10000),
('BTC-USDT', 1, 3, 25000000000000, 1000000000000000, 20000000, 50000, 25000),
('ETH-USDT', 1, 1, 0, 5000000000000, 100000000, 10000, 5000),
('ETH-USDT', 1, 2, 5000000000000, 25000000000000, 50000000, 20000, 10000),
('ETH-USDT', 1, 3, 25000000000000, 1000000000000000, 20000000, 50000, 25000)
ON CONFLICT (symbol, version, bracket_no) DO NOTHING;

INSERT INTO instrument_index_sources (
    symbol, version, source, enabled, base_url, path, source_symbol, parser,
    quote_currency, target_quote_currency, conversion_base_url, conversion_path,
    conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier_ppm,
    websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight_ppm
) VALUES
('BTC-USDT', 1, 'BINANCE', TRUE, 'https://api.binance.com', '/api/v3/ticker/bookTicker?symbol=BTCUSDT', 'BTCUSDT', 'BINANCE_BOOK_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://stream.binance.com:9443/ws', '{"method":"SUBSCRIBE","params":["btcusdt@bookTicker"],"id":1}', 'BINANCE_BOOK_TICKER', 1000000),
('BTC-USDT', 1, 'OKX', TRUE, 'https://www.okx.com', '/api/v5/market/ticker?instId=BTC-USDT', 'BTC-USDT', 'OKX_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://ws.okx.com:8443/ws/v5/public', '{"op":"subscribe","args":[{"channel":"tickers","instId":"BTC-USDT"}]}', 'OKX_TICKER', 1000000),
('BTC-USDT', 1, 'BYBIT', TRUE, 'https://api.bybit.com', '/v5/market/tickers?category=spot&symbol=BTCUSDT', 'BTCUSDT', 'BYBIT_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://stream.bybit.com/v5/public/spot', '{"op":"subscribe","args":["tickers.BTCUSDT"]}', 'BYBIT_TICKER', 1000000),
('BTC-USDT', 1, 'COINBASE', TRUE, 'https://api.exchange.coinbase.com', '/products/BTC-USD/ticker', 'BTC-USD', 'COINBASE_TICKER',
 'USD', 'USDT', 'https://api.exchange.coinbase.com', '/products/USDT-USD/ticker', 'COINBASE_TICKER', 'DISCOUNT', 'DIVIDE', 500000,
 TRUE, 'wss://ws-feed.exchange.coinbase.com', '{"type":"subscribe","product_ids":["BTC-USD"],"channels":["ticker"]}', 'COINBASE_TICKER', 1000000),
('BTC-USDT', 1, 'KRAKEN', TRUE, 'https://api.kraken.com', '/0/public/Ticker?pair=XBTUSDT', 'XBTUSDT', 'KRAKEN_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://ws.kraken.com/v2', '{"method":"subscribe","params":{"channel":"ticker","symbol":["BTC/USDT"]}}', 'KRAKEN_TICKER', 1000000),
('ETH-USDT', 1, 'BINANCE', TRUE, 'https://api.binance.com', '/api/v3/ticker/bookTicker?symbol=ETHUSDT', 'ETHUSDT', 'BINANCE_BOOK_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://stream.binance.com:9443/ws', '{"method":"SUBSCRIBE","params":["ethusdt@bookTicker"],"id":2}', 'BINANCE_BOOK_TICKER', 1000000),
('ETH-USDT', 1, 'OKX', TRUE, 'https://www.okx.com', '/api/v5/market/ticker?instId=ETH-USDT', 'ETH-USDT', 'OKX_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://ws.okx.com:8443/ws/v5/public', '{"op":"subscribe","args":[{"channel":"tickers","instId":"ETH-USDT"}]}', 'OKX_TICKER', 1000000),
('ETH-USDT', 1, 'BYBIT', TRUE, 'https://api.bybit.com', '/v5/market/tickers?category=spot&symbol=ETHUSDT', 'ETHUSDT', 'BYBIT_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://stream.bybit.com/v5/public/spot', '{"op":"subscribe","args":["tickers.ETHUSDT"]}', 'BYBIT_TICKER', 1000000),
('ETH-USDT', 1, 'COINBASE', TRUE, 'https://api.exchange.coinbase.com', '/products/ETH-USD/ticker', 'ETH-USD', 'COINBASE_TICKER',
 'USD', 'USDT', 'https://api.exchange.coinbase.com', '/products/USDT-USD/ticker', 'COINBASE_TICKER', 'DISCOUNT', 'DIVIDE', 500000,
 TRUE, 'wss://ws-feed.exchange.coinbase.com', '{"type":"subscribe","product_ids":["ETH-USD"],"channels":["ticker"]}', 'COINBASE_TICKER', 1000000),
('ETH-USDT', 1, 'KRAKEN', TRUE, 'https://api.kraken.com', '/0/public/Ticker?pair=ETHUSDT', 'ETHUSDT', 'KRAKEN_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 TRUE, 'wss://ws.kraken.com/v2', '{"method":"subscribe","params":{"channel":"ticker","symbol":["ETH/USDT"]}}', 'KRAKEN_TICKER', 1000000)
ON CONFLICT (symbol, version, source) DO NOTHING;

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
    mark_price_units            BIGINT NOT NULL,
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
    CONSTRAINT price_mark_ticks_positive_units CHECK (mark_price_units > 0),
    CONSTRAINT price_mark_ticks_valid_book CHECK (best_bid_price <= best_ask_price),
    CONSTRAINT price_mark_ticks_valid_clamp CHECK (clamp_low <= mark_price AND mark_price <= clamp_high)
);

CREATE INDEX IF NOT EXISTS price_mark_ticks_query_idx
    ON price_mark_ticks (symbol, event_time DESC);

CREATE TABLE IF NOT EXISTS funding_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT funding_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS funding_rate_ticks (
    symbol                  TEXT NOT NULL,
    sequence                BIGINT NOT NULL,
    funding_time            TIMESTAMPTZ NOT NULL,
    funding_interval_hours  INTEGER NOT NULL,
    premium_rate_ppm        BIGINT NOT NULL,
    interest_rate_ppm       BIGINT NOT NULL,
    funding_rate_ppm        BIGINT NOT NULL,
    status                  TEXT NOT NULL,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, sequence),
    CONSTRAINT funding_rate_ticks_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT funding_rate_ticks_interval_positive CHECK (funding_interval_hours > 0),
    CONSTRAINT funding_rate_ticks_status_check CHECK (status IN ('PREDICTED', 'FINAL'))
);

CREATE INDEX IF NOT EXISTS funding_rate_ticks_symbol_time_idx
    ON funding_rate_ticks (symbol, funding_time DESC, sequence DESC);

CREATE TABLE IF NOT EXISTS funding_settlements (
    settlement_id               BIGINT PRIMARY KEY,
    symbol                      TEXT NOT NULL,
    funding_time                TIMESTAMPTZ NOT NULL,
    funding_rate_ppm            BIGINT NOT NULL,
    total_long_payment_units    BIGINT NOT NULL DEFAULT 0,
    total_short_payment_units   BIGINT NOT NULL DEFAULT 0,
    position_count              INTEGER NOT NULL DEFAULT 0,
    status                      TEXT NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT funding_settlements_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT funding_settlements_position_count_non_negative CHECK (position_count >= 0),
    CONSTRAINT funding_settlements_status_check CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS funding_settlements_symbol_time_uidx
    ON funding_settlements (symbol, funding_time);

CREATE INDEX IF NOT EXISTS funding_settlements_status_idx
    ON funding_settlements (status, funding_time DESC);

CREATE TABLE IF NOT EXISTS funding_payments (
    payment_id              BIGINT PRIMARY KEY,
    settlement_id           BIGINT NOT NULL,
    user_id                 BIGINT NOT NULL,
    symbol                  TEXT NOT NULL,
    asset                   TEXT NOT NULL,
    signed_quantity_steps   BIGINT NOT NULL,
    notional_units          BIGINT NOT NULL,
    funding_rate_ppm        BIGINT NOT NULL,
    amount_units            BIGINT NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT funding_payments_settlement_fk
        FOREIGN KEY (settlement_id) REFERENCES funding_settlements(settlement_id),
    CONSTRAINT funding_payments_user_positive CHECK (user_id > 0),
    CONSTRAINT funding_payments_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT funding_payments_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT funding_payments_notional_non_negative CHECK (notional_units >= 0),
    CONSTRAINT funding_payments_amount_non_zero CHECK (amount_units <> 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS funding_payments_settlement_user_uidx
    ON funding_payments (settlement_id, user_id);

CREATE INDEX IF NOT EXISTS funding_payments_user_time_idx
    ON funding_payments (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS funding_outbox_events (
    id                  BIGINT PRIMARY KEY,
    topic               TEXT NOT NULL,
    event_key           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    published_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts            INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT funding_outbox_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS funding_outbox_pending_idx
    ON funding_outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS trading_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS trading_fee_schedules (
    fee_schedule_id     BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    symbol              TEXT,
    maker_fee_rate_ppm  BIGINT NOT NULL,
    taker_fee_rate_ppm  BIGINT NOT NULL,
    reason              TEXT NOT NULL,
    status              TEXT NOT NULL,
    effective_time      TIMESTAMPTZ NOT NULL,
    expire_time         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_fee_schedules_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_fee_schedules_symbol_format CHECK (
        symbol IS NULL OR symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    ),
    CONSTRAINT trading_fee_schedules_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT trading_fee_schedules_fee_range CHECK (
        maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT trading_fee_schedules_time_check CHECK (
        expire_time IS NULL OR expire_time > effective_time
    )
);

CREATE INDEX IF NOT EXISTS trading_fee_schedules_user_symbol_idx
    ON trading_fee_schedules (user_id, symbol, status, effective_time DESC, fee_schedule_id DESC);

CREATE INDEX IF NOT EXISTS trading_fee_schedules_user_global_idx
    ON trading_fee_schedules (user_id, status, effective_time DESC, fee_schedule_id DESC)
    WHERE symbol IS NULL;

CREATE TABLE IF NOT EXISTS trading_orders (
    order_id                    BIGINT PRIMARY KEY,
    user_id                     BIGINT NOT NULL,
    client_order_id             TEXT,
    symbol                      TEXT NOT NULL,
    instrument_version          BIGINT,
    side                        TEXT NOT NULL,
    order_type                  TEXT NOT NULL,
    time_in_force               TEXT NOT NULL,
    price_ticks                 BIGINT NOT NULL,
    quantity_steps              BIGINT NOT NULL,
    executed_quantity_steps     BIGINT NOT NULL DEFAULT 0,
    remaining_quantity_steps    BIGINT NOT NULL,
    margin_mode                 TEXT NOT NULL DEFAULT 'CROSS',
    maker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    taker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    reduce_only                 BOOLEAN NOT NULL DEFAULT FALSE,
    post_only                   BOOLEAN NOT NULL DEFAULT FALSE,
    status                      TEXT NOT NULL,
    reject_reason               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_orders_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_orders_client_id_length CHECK (client_order_id IS NULL OR length(client_order_id) <= 64),
    CONSTRAINT trading_orders_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT trading_orders_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT trading_orders_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT trading_orders_fee_rate_check CHECK (
        maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT trading_orders_type_check CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT trading_orders_tif_check CHECK (time_in_force IN ('GTC', 'IOC', 'FOK', 'GTX')),
    CONSTRAINT trading_orders_status_check CHECK (
        status IN ('ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
    ),
    CONSTRAINT trading_orders_long_values CHECK (
        price_ticks >= 0
        AND quantity_steps > 0
        AND executed_quantity_steps >= 0
        AND remaining_quantity_steps >= 0
        AND executed_quantity_steps + remaining_quantity_steps <= quantity_steps
    ),
    CONSTRAINT trading_orders_market_price_zero CHECK (
        order_type <> 'MARKET' OR price_ticks = 0
    ),
    CONSTRAINT trading_orders_instrument_version_check CHECK (
        status = 'REJECTED' OR instrument_version IS NOT NULL
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS trading_orders_user_client_order_uidx
    ON trading_orders (user_id, client_order_id)
    WHERE client_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_orders_user_status_idx
    ON trading_orders (user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS trading_orders_symbol_status_idx
    ON trading_orders (symbol, status, created_at DESC);

CREATE INDEX IF NOT EXISTS trading_orders_open_query_idx
    ON trading_orders (user_id, symbol, created_at DESC)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED');

CREATE INDEX IF NOT EXISTS trading_orders_stp_open_idx
    ON trading_orders (user_id, symbol, side, price_ticks)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
      AND remaining_quantity_steps > 0;

CREATE INDEX IF NOT EXISTS trading_orders_recovery_idx
    ON trading_orders (created_at ASC, order_id ASC)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
      AND order_type = 'LIMIT'
      AND time_in_force IN ('GTC', 'GTX')
      AND remaining_quantity_steps > 0;

CREATE TABLE IF NOT EXISTS trading_order_events (
    event_id            BIGINT PRIMARY KEY,
    order_id            BIGINT NOT NULL,
    user_id             BIGINT NOT NULL,
    symbol              TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    status              TEXT NOT NULL,
    reason              TEXT,
    trace_id            TEXT,
    event_time          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_order_events_order_fk
        FOREIGN KEY (order_id) REFERENCES trading_orders(order_id),
    CONSTRAINT trading_order_events_type_check CHECK (event_type IN ('ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED')),
    CONSTRAINT trading_order_events_status_check CHECK (
        status IN ('ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
    )
);

CREATE INDEX IF NOT EXISTS trading_order_events_order_idx
    ON trading_order_events (order_id, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_order_events_symbol_time_idx
    ON trading_order_events (symbol, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_order_events_trace_idx
    ON trading_order_events (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS trading_outbox_events (
    id                  BIGINT PRIMARY KEY,
    aggregate_type      TEXT NOT NULL,
    aggregate_id        BIGINT NOT NULL,
    topic               TEXT NOT NULL,
    event_key           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    published_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts            INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_outbox_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS trading_outbox_pending_idx
    ON trading_outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS trading_outbox_aggregate_idx
    ON trading_outbox_events (aggregate_type, aggregate_id);

CREATE TABLE IF NOT EXISTS trading_matching_assets (
    asset               TEXT PRIMARY KEY,
    asset_id            INTEGER NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_matching_assets_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT trading_matching_assets_asset_id_positive CHECK (asset_id > 0)
);

CREATE TABLE IF NOT EXISTS trading_matching_symbols (
    symbol              TEXT PRIMARY KEY,
    symbol_id           INTEGER NOT NULL UNIQUE,
    base_asset          TEXT NOT NULL,
    quote_asset         TEXT NOT NULL,
    settle_asset        TEXT NOT NULL,
    base_currency_id    INTEGER NOT NULL,
    quote_currency_id   INTEGER NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_matching_symbols_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT trading_matching_symbols_symbol_id_positive CHECK (symbol_id > 0),
    CONSTRAINT trading_matching_symbols_base_asset_fk
        FOREIGN KEY (base_asset) REFERENCES trading_matching_assets(asset),
    CONSTRAINT trading_matching_symbols_quote_asset_fk
        FOREIGN KEY (quote_asset) REFERENCES trading_matching_assets(asset),
    CONSTRAINT trading_matching_symbols_settle_asset_fk
        FOREIGN KEY (settle_asset) REFERENCES trading_matching_assets(asset)
);

CREATE INDEX IF NOT EXISTS trading_matching_symbols_enabled_idx
    ON trading_matching_symbols (enabled, symbol_id);

CREATE TABLE IF NOT EXISTS trading_match_results (
    command_id              BIGINT PRIMARY KEY,
    order_id                BIGINT NOT NULL,
    user_id                 BIGINT NOT NULL,
    symbol                  TEXT NOT NULL,
    instrument_version      BIGINT NOT NULL,
    command_type            TEXT NOT NULL,
    result_code             TEXT NOT NULL,
    filled_quantity_steps   BIGINT NOT NULL,
    order_status            TEXT NOT NULL,
    trace_id                TEXT,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_match_results_order_fk
        FOREIGN KEY (order_id) REFERENCES trading_orders(order_id),
    CONSTRAINT trading_match_results_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_match_results_command_type_check CHECK (command_type IN ('PLACE', 'CANCEL')),
    CONSTRAINT trading_match_results_quantity_non_negative CHECK (filled_quantity_steps >= 0),
    CONSTRAINT trading_match_results_status_check CHECK (
        order_status IN ('ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
    )
);

CREATE INDEX IF NOT EXISTS trading_match_results_order_idx
    ON trading_match_results (order_id, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_results_symbol_time_idx
    ON trading_match_results (symbol, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_results_trace_idx
    ON trading_match_results (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_match_results_success_place_idx
    ON trading_match_results (order_id)
    WHERE command_type = 'PLACE'
      AND result_code = 'SUCCESS';

CREATE TABLE IF NOT EXISTS trading_match_trades (
    trade_id                BIGINT NOT NULL,
    command_id              BIGINT NOT NULL,
    symbol                  TEXT NOT NULL,
    taker_order_id          BIGINT NOT NULL,
    taker_instrument_version BIGINT NOT NULL,
    taker_user_id           BIGINT NOT NULL,
    taker_side              TEXT NOT NULL,
    taker_margin_mode       TEXT NOT NULL DEFAULT 'CROSS',
    maker_order_id          BIGINT NOT NULL,
    maker_instrument_version BIGINT NOT NULL,
    maker_user_id           BIGINT NOT NULL,
    maker_margin_mode       TEXT NOT NULL DEFAULT 'CROSS',
    price_ticks             BIGINT NOT NULL,
    quantity_steps          BIGINT NOT NULL,
    taker_order_completed   BOOLEAN NOT NULL,
    maker_order_completed   BOOLEAN NOT NULL,
    trace_id                TEXT,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, trade_id),
    CONSTRAINT trading_match_trades_command_fk
        FOREIGN KEY (command_id) REFERENCES trading_match_results(command_id),
    CONSTRAINT trading_match_trades_taker_instrument_fk
        FOREIGN KEY (symbol, taker_instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_match_trades_maker_instrument_fk
        FOREIGN KEY (symbol, maker_instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_match_trades_side_check CHECK (taker_side IN ('BUY', 'SELL')),
    CONSTRAINT trading_match_trades_margin_mode_check CHECK (
        taker_margin_mode IN ('CROSS', 'ISOLATED') AND maker_margin_mode IN ('CROSS', 'ISOLATED')
    ),
    CONSTRAINT trading_match_trades_positive_values CHECK (price_ticks > 0 AND quantity_steps > 0)
);

CREATE INDEX IF NOT EXISTS trading_match_trades_symbol_time_idx
    ON trading_match_trades (symbol, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_trades_taker_order_idx
    ON trading_match_trades (taker_order_id, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_trades_maker_order_idx
    ON trading_match_trades (maker_order_id, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_trades_trace_idx
    ON trading_match_trades (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS account_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS account_asset_scales (
    asset               TEXT PRIMARY KEY,
    scale_units         BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_asset_scales_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_asset_scales_positive CHECK (scale_units > 0)
);

INSERT INTO account_asset_scales (asset, scale_units, created_at, updated_at)
VALUES
('USDT', 100000000, now(), now()),
('USD', 100000000, now(), now()),
('BTC', 100000000, now(), now()),
('ETH', 1000000000000000000, now(), now())
ON CONFLICT (asset) DO NOTHING;

CREATE TABLE IF NOT EXISTS account_balances (
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    available_units     BIGINT NOT NULL DEFAULT 0,
    locked_units        BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, asset),
    CONSTRAINT account_balances_user_positive CHECK (user_id > 0),
    CONSTRAINT account_balances_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_balances_non_negative CHECK (available_units >= 0 AND locked_units >= 0)
);

CREATE INDEX IF NOT EXISTS account_balances_user_idx
    ON account_balances (user_id);

CREATE TABLE IF NOT EXISTS account_deficits (
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    deficit_units       BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, asset),
    CONSTRAINT account_deficits_user_positive CHECK (user_id > 0),
    CONSTRAINT account_deficits_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_deficits_non_negative CHECK (deficit_units >= 0)
);

CREATE INDEX IF NOT EXISTS account_deficits_user_idx
    ON account_deficits (user_id);

CREATE TABLE IF NOT EXISTS account_ledger_entries (
    entry_id            BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    trade_id            BIGINT,
    order_id            BIGINT,
    symbol              TEXT,
    fee_rate_ppm        BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_ledger_user_positive CHECK (user_id > 0),
    CONSTRAINT account_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT account_ledger_trade_fee_metadata_check CHECK (
        reference_type <> 'TRADE_FEE'
        OR (trade_id IS NOT NULL AND order_id IS NOT NULL AND symbol IS NOT NULL AND fee_rate_ppm IS NOT NULL)
    ),
    CONSTRAINT account_ledger_fee_rate_range CHECK (
        fee_rate_ppm IS NULL OR fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT account_ledger_symbol_format CHECK (
        symbol IS NULL OR symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS account_ledger_reference_uidx
    ON account_ledger_entries (reference_type, reference_id, user_id, asset);

CREATE INDEX IF NOT EXISTS account_ledger_user_time_idx
    ON account_ledger_entries (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS account_ledger_trade_fee_order_idx
    ON account_ledger_entries (order_id, trade_id)
    WHERE reference_type = 'TRADE_FEE';

CREATE TABLE IF NOT EXISTS account_margin_reservations (
    reservation_id      BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    order_id            BIGINT NOT NULL UNIQUE,
    symbol              TEXT NOT NULL,
    margin_mode         TEXT NOT NULL DEFAULT 'CROSS',
    reserved_units      BIGINT NOT NULL,
    released_units      BIGINT NOT NULL DEFAULT 0,
    position_margin_units BIGINT NOT NULL DEFAULT 0,
    status              TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_margin_reservations_user_positive CHECK (user_id > 0),
    CONSTRAINT account_margin_reservations_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_margin_reservations_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_margin_reservations_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT account_margin_reservations_non_negative CHECK (
        reserved_units >= 0
        AND released_units >= 0
        AND position_margin_units >= 0
        AND released_units + position_margin_units <= reserved_units
    ),
    CONSTRAINT account_margin_reservations_status_check CHECK (
        status IN ('ACTIVE', 'PARTIALLY_RELEASED', 'PARTIALLY_CONSUMED', 'CONSUMED', 'RELEASED')
    ),
    CONSTRAINT account_margin_reservations_order_fk
        FOREIGN KEY (order_id) REFERENCES trading_orders(order_id)
);

CREATE INDEX IF NOT EXISTS account_margin_reservations_user_idx
    ON account_margin_reservations (user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS account_margin_reservations_symbol_idx
    ON account_margin_reservations (symbol, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS account_position_margins (
    user_id             BIGINT NOT NULL,
    symbol              TEXT NOT NULL,
    asset               TEXT NOT NULL,
    margin_mode         TEXT NOT NULL DEFAULT 'CROSS',
    margin_units        BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, symbol, asset, margin_mode),
    CONSTRAINT account_position_margins_user_positive CHECK (user_id > 0),
    CONSTRAINT account_position_margins_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_position_margins_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_position_margins_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT account_position_margins_non_negative CHECK (margin_units >= 0)
);

CREATE INDEX IF NOT EXISTS account_position_margins_user_idx
    ON account_position_margins (user_id, symbol, margin_mode);

CREATE TABLE IF NOT EXISTS account_positions (
    user_id                 BIGINT NOT NULL,
    symbol                  TEXT NOT NULL,
    margin_mode             TEXT NOT NULL DEFAULT 'CROSS',
    instrument_version      BIGINT,
    signed_quantity_steps   BIGINT NOT NULL,
    entry_price_ticks       BIGINT NOT NULL,
    realized_pnl_units      BIGINT NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, symbol, margin_mode),
    CONSTRAINT account_positions_user_positive CHECK (user_id > 0),
    CONSTRAINT account_positions_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_positions_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT account_positions_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT account_positions_entry_price_check CHECK (
        (signed_quantity_steps = 0 AND entry_price_ticks = 0 AND instrument_version IS NULL)
        OR (signed_quantity_steps <> 0 AND entry_price_ticks > 0 AND instrument_version IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS account_positions_user_idx
    ON account_positions (user_id);

CREATE INDEX IF NOT EXISTS account_positions_symbol_idx
    ON account_positions (symbol, margin_mode);

CREATE INDEX IF NOT EXISTS account_positions_open_scan_idx
    ON account_positions (user_id, symbol, margin_mode)
    WHERE signed_quantity_steps <> 0;

CREATE TABLE IF NOT EXISTS account_processed_trades (
    trade_id            BIGINT NOT NULL,
    symbol              TEXT NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, trade_id),
    CONSTRAINT account_processed_trades_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$')
);

CREATE INDEX IF NOT EXISTS account_processed_trades_symbol_idx
    ON account_processed_trades (symbol, processed_at DESC);

CREATE TABLE IF NOT EXISTS account_outbox_events (
    id                  BIGINT PRIMARY KEY,
    aggregate_type      TEXT NOT NULL,
    aggregate_id        BIGINT NOT NULL,
    topic               TEXT NOT NULL,
    event_key           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT account_outbox_topic_non_empty CHECK (length(topic) > 0),
    CONSTRAINT account_outbox_event_key_non_empty CHECK (length(event_key) > 0)
);

CREATE INDEX IF NOT EXISTS account_outbox_pending_idx
    ON account_outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS account_outbox_aggregate_idx
    ON account_outbox_events (aggregate_type, aggregate_id);

CREATE TABLE IF NOT EXISTS risk_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT risk_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS risk_scan_leases (
    user_id             BIGINT NOT NULL,
    settle_asset        TEXT NOT NULL,
    owner_id            TEXT NOT NULL,
    lease_until         TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, settle_asset),
    CONSTRAINT risk_scan_leases_user_positive CHECK (user_id > 0),
    CONSTRAINT risk_scan_leases_asset_format CHECK (settle_asset ~ '^[A-Z0-9]{2,20}$')
);

CREATE INDEX IF NOT EXISTS risk_scan_leases_expiry_idx
    ON risk_scan_leases (lease_until);

CREATE TABLE IF NOT EXISTS risk_account_snapshots (
    snapshot_id                 BIGINT PRIMARY KEY,
    user_id                     BIGINT NOT NULL,
    settle_asset                TEXT NOT NULL,
    wallet_balance_units        BIGINT NOT NULL,
    unrealized_pnl_units        BIGINT NOT NULL,
    equity_units                BIGINT NOT NULL,
    maintenance_margin_units    BIGINT NOT NULL,
    margin_ratio_ppm            BIGINT NOT NULL,
    status                      TEXT NOT NULL,
    event_time                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT risk_account_snapshots_user_positive CHECK (user_id > 0),
    CONSTRAINT risk_account_snapshots_asset_format CHECK (settle_asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT risk_account_snapshots_non_negative CHECK (
        maintenance_margin_units >= 0 AND margin_ratio_ppm >= 0
    ),
    CONSTRAINT risk_account_snapshots_status_check CHECK (status IN ('NORMAL', 'WARNING', 'LIQUIDATION'))
);

CREATE INDEX IF NOT EXISTS risk_account_snapshots_query_idx
    ON risk_account_snapshots (user_id, settle_asset, event_time DESC);

CREATE INDEX IF NOT EXISTS risk_account_snapshots_status_idx
    ON risk_account_snapshots (status, event_time DESC);

CREATE TABLE IF NOT EXISTS risk_position_snapshots (
    snapshot_id                 BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    symbol                      TEXT NOT NULL,
    instrument_version          BIGINT NOT NULL,
    settle_asset                TEXT NOT NULL,
    signed_quantity_steps       BIGINT NOT NULL,
    entry_price_ticks           BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    notional_units              BIGINT NOT NULL,
    unrealized_pnl_units        BIGINT NOT NULL,
    maintenance_margin_units    BIGINT NOT NULL,
    margin_ratio_ppm            BIGINT NOT NULL,
    status                      TEXT NOT NULL,
    event_time                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (snapshot_id, user_id, symbol),
    CONSTRAINT risk_position_snapshots_snapshot_fk
        FOREIGN KEY (snapshot_id) REFERENCES risk_account_snapshots(snapshot_id),
    CONSTRAINT risk_position_snapshots_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT risk_position_snapshots_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT risk_position_snapshots_price_check CHECK (
        (signed_quantity_steps = 0 AND entry_price_ticks = 0 AND mark_price_ticks = 0)
        OR (signed_quantity_steps <> 0 AND entry_price_ticks > 0 AND mark_price_ticks > 0)
    ),
    CONSTRAINT risk_position_snapshots_non_negative CHECK (
        notional_units >= 0 AND maintenance_margin_units >= 0 AND margin_ratio_ppm >= 0
    ),
    CONSTRAINT risk_position_snapshots_status_check CHECK (status IN ('NORMAL', 'WARNING', 'LIQUIDATION'))
);

CREATE INDEX IF NOT EXISTS risk_position_snapshots_user_idx
    ON risk_position_snapshots (user_id, event_time DESC);

CREATE INDEX IF NOT EXISTS risk_position_snapshots_symbol_idx
    ON risk_position_snapshots (symbol, event_time DESC);

CREATE TABLE IF NOT EXISTS risk_liquidation_candidates (
    candidate_id                BIGINT PRIMARY KEY,
    snapshot_id                 BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    symbol                      TEXT NOT NULL,
    instrument_version          BIGINT NOT NULL,
    settle_asset                TEXT NOT NULL,
    signed_quantity_steps       BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    equity_units                BIGINT NOT NULL,
    maintenance_margin_units    BIGINT NOT NULL,
    margin_ratio_ppm            BIGINT NOT NULL,
    status                      TEXT NOT NULL,
    event_time                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT risk_liquidation_candidates_snapshot_fk
        FOREIGN KEY (snapshot_id) REFERENCES risk_account_snapshots(snapshot_id),
    CONSTRAINT risk_liquidation_candidates_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT risk_liquidation_candidates_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT risk_liquidation_candidates_status_check CHECK (status IN ('NEW', 'PROCESSING', 'COMPLETED', 'CANCELED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS risk_liquidation_candidates_snapshot_uidx
    ON risk_liquidation_candidates (snapshot_id, user_id, symbol);

CREATE UNIQUE INDEX IF NOT EXISTS risk_liquidation_candidates_active_uidx
    ON risk_liquidation_candidates (user_id, symbol)
    WHERE status IN ('NEW', 'PROCESSING');

CREATE INDEX IF NOT EXISTS risk_liquidation_candidates_status_idx
    ON risk_liquidation_candidates (status, event_time ASC);

CREATE TABLE IF NOT EXISTS risk_outbox_events (
    id                  BIGINT PRIMARY KEY,
    topic               TEXT NOT NULL,
    event_key           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    payload             JSONB NOT NULL,
    published_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts            INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT risk_outbox_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS risk_outbox_pending_idx
    ON risk_outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS liquidation_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT liquidation_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS liquidation_orders (
    liquidation_order_id    BIGINT PRIMARY KEY,
    candidate_id            BIGINT NOT NULL,
    order_id                BIGINT NOT NULL,
    user_id                 BIGINT NOT NULL,
    symbol                  TEXT NOT NULL,
    side                    TEXT NOT NULL,
    quantity_steps          BIGINT NOT NULL,
    status                  TEXT NOT NULL,
    reason                  TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT liquidation_orders_candidate_fk
        FOREIGN KEY (candidate_id) REFERENCES risk_liquidation_candidates(candidate_id),
    CONSTRAINT liquidation_orders_user_positive CHECK (user_id > 0),
    CONSTRAINT liquidation_orders_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT liquidation_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT liquidation_orders_quantity_check CHECK (
        quantity_steps >= 0
        AND (status <> 'SUBMITTED' OR quantity_steps > 0)
    ),
    CONSTRAINT liquidation_orders_status_check CHECK (
        status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED', 'FAILED')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS liquidation_orders_candidate_uidx
    ON liquidation_orders (candidate_id);

CREATE INDEX IF NOT EXISTS liquidation_orders_user_time_idx
    ON liquidation_orders (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS liquidation_orders_symbol_time_idx
    ON liquidation_orders (symbol, created_at DESC);

CREATE TABLE IF NOT EXISTS insurance_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS insurance_fund_balances (
    asset               TEXT PRIMARY KEY,
    balance_units       BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT insurance_fund_balances_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_fund_balances_non_negative CHECK (balance_units >= 0)
);

CREATE TABLE IF NOT EXISTS insurance_fund_ledger (
    entry_id            BIGINT PRIMARY KEY,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_fund_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_fund_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT insurance_fund_ledger_balance_non_negative CHECK (balance_after_units >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_fund_ledger_reference_uidx
    ON insurance_fund_ledger (reference_type, reference_id, asset);

CREATE INDEX IF NOT EXISTS insurance_fund_ledger_asset_time_idx
    ON insurance_fund_ledger (asset, created_at DESC);

CREATE TABLE IF NOT EXISTS insurance_deficit_coverages (
    coverage_id                 BIGINT PRIMARY KEY,
    user_id                     BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    requested_units             BIGINT NOT NULL,
    covered_units               BIGINT NOT NULL,
    remaining_deficit_units     BIGINT NOT NULL,
    status                      TEXT NOT NULL,
    reason                      TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_coverages_user_positive CHECK (user_id > 0),
    CONSTRAINT insurance_coverages_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_coverages_non_negative CHECK (
        requested_units > 0
        AND covered_units > 0
        AND remaining_deficit_units >= 0
        AND covered_units <= requested_units
    ),
    CONSTRAINT insurance_coverages_status_check CHECK (status IN ('COVERED', 'PARTIALLY_COVERED'))
);

CREATE INDEX IF NOT EXISTS insurance_coverages_user_time_idx
    ON insurance_deficit_coverages (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS insurance_coverages_asset_time_idx
    ON insurance_deficit_coverages (asset, created_at DESC);

CREATE TABLE IF NOT EXISTS adl_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT adl_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS adl_events (
    event_id                    BIGINT PRIMARY KEY,
    deficit_user_id             BIGINT NOT NULL,
    target_user_id              BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    symbol                      TEXT NOT NULL,
    target_side                 TEXT NOT NULL,
    closed_quantity_steps       BIGINT NOT NULL,
    entry_price_ticks           BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    requested_deficit_units     BIGINT NOT NULL,
    realized_profit_units       BIGINT NOT NULL,
    covered_units               BIGINT NOT NULL,
    remaining_deficit_units     BIGINT NOT NULL,
    priority_score_ppm          BIGINT NOT NULL,
    reason                      TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT adl_events_users_positive CHECK (deficit_user_id > 0 AND target_user_id > 0),
    CONSTRAINT adl_events_distinct_users CHECK (deficit_user_id <> target_user_id),
    CONSTRAINT adl_events_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT adl_events_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT adl_events_side_check CHECK (target_side IN ('LONG', 'SHORT')),
    CONSTRAINT adl_events_positive_values CHECK (
        closed_quantity_steps > 0
        AND entry_price_ticks > 0
        AND mark_price_ticks > 0
        AND requested_deficit_units > 0
        AND realized_profit_units > 0
        AND covered_units > 0
        AND remaining_deficit_units >= 0
        AND priority_score_ppm >= 0
    )
);

CREATE INDEX IF NOT EXISTS adl_events_deficit_user_time_idx
    ON adl_events (deficit_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS adl_events_target_user_time_idx
    ON adl_events (target_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS adl_events_asset_symbol_time_idx
    ON adl_events (asset, symbol, created_at DESC);
