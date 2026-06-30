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
    contract_size               NUMERIC(38, 18) NOT NULL,
    contract_value_asset        TEXT NOT NULL,
    price_tick_size             NUMERIC(38, 18) NOT NULL,
    quantity_step_size          NUMERIC(38, 18) NOT NULL,
    min_order_qty               NUMERIC(38, 18) NOT NULL,
    max_order_qty               NUMERIC(38, 18) NOT NULL,
    min_notional                NUMERIC(38, 18) NOT NULL,
    max_notional                NUMERIC(38, 18) NOT NULL,
    price_precision             INTEGER NOT NULL,
    quantity_precision          INTEGER NOT NULL,
    supported_order_types       TEXT NOT NULL,
    supported_time_in_force     TEXT NOT NULL,
    post_only_enabled           BOOLEAN NOT NULL,
    reduce_only_enabled         BOOLEAN NOT NULL,
    market_order_enabled        BOOLEAN NOT NULL,
    max_leverage                NUMERIC(38, 18) NOT NULL,
    initial_margin_rate         NUMERIC(38, 18) NOT NULL,
    maintenance_margin_rate     NUMERIC(38, 18) NOT NULL,
    max_position_notional       NUMERIC(38, 18) NOT NULL,
    funding_interval_hours      INTEGER NOT NULL,
    interest_rate               NUMERIC(38, 18) NOT NULL,
    funding_rate_cap            NUMERIC(38, 18) NOT NULL,
    funding_rate_floor          NUMERIC(38, 18) NOT NULL,
    impact_notional             NUMERIC(38, 18) NOT NULL,
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
        contract_size > 0
        AND price_tick_size > 0
        AND quantity_step_size > 0
        AND min_order_qty > 0
        AND max_order_qty >= min_order_qty
        AND min_notional > 0
        AND max_notional >= min_notional
        AND max_leverage > 0
        AND initial_margin_rate > 0
        AND maintenance_margin_rate > 0
        AND max_position_notional > 0
        AND funding_interval_hours > 0
        AND funding_rate_cap >= funding_rate_floor
        AND impact_notional > 0
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
    notional_floor          NUMERIC(38, 18) NOT NULL,
    notional_cap            NUMERIC(38, 18) NOT NULL,
    max_leverage            NUMERIC(38, 18) NOT NULL,
    initial_margin_rate     NUMERIC(38, 18) NOT NULL,
    maintenance_margin_rate NUMERIC(38, 18) NOT NULL,
    PRIMARY KEY (symbol, version, bracket_no),
    CONSTRAINT instrument_risk_brackets_instrument_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version),
    CONSTRAINT instrument_risk_brackets_positive CHECK (
        bracket_no > 0
        AND notional_floor >= 0
        AND notional_cap > notional_floor
        AND max_leverage > 0
        AND initial_margin_rate > 0
        AND maintenance_margin_rate > 0
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
    fallback_weight_multiplier  NUMERIC(38, 18) NOT NULL DEFAULT 0.50,
    websocket_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    websocket_url               TEXT,
    websocket_subscribe_message TEXT,
    websocket_parser            TEXT,
    weight                      NUMERIC(38, 18) NOT NULL,
    PRIMARY KEY (symbol, version, source),
    CONSTRAINT instrument_index_sources_instrument_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version),
    CONSTRAINT instrument_index_sources_positive_weight CHECK (weight > 0),
    CONSTRAINT instrument_index_sources_conversion_mode CHECK (conversion_mode IN ('DISCOUNT', 'DISABLE')),
    CONSTRAINT instrument_index_sources_conversion_operation CHECK (conversion_operation IN ('MULTIPLY', 'DIVIDE'))
);

CREATE INDEX IF NOT EXISTS instrument_index_sources_enabled_idx
    ON instrument_index_sources (symbol, version, enabled);

INSERT INTO instruments (
    symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
    contract_size, contract_value_asset, price_tick_size, quantity_step_size,
    min_order_qty, max_order_qty, min_notional, max_notional,
    price_precision, quantity_precision, supported_order_types, supported_time_in_force,
    post_only_enabled, reduce_only_enabled, market_order_enabled,
    max_leverage, initial_margin_rate, maintenance_margin_rate, max_position_notional,
    funding_interval_hours, interest_rate, funding_rate_cap, funding_rate_floor,
    impact_notional, min_valid_index_sources, status, effective_time, created_at, updated_at
) VALUES
('BTC-USDT', 1, 'PERPETUAL', 'LINEAR_PERPETUAL', 'BTC', 'USDT', 'USDT',
 1, 'USDT', 0.1, 0.001, 0.001, 100, 5, 10000000, 1, 3,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100, 0.01, 0.005, 5000000, 8, 0.0001, 0.003, -0.003,
 10000, 3, 'TRADING', now(), now(), now()),
('ETH-USDT', 1, 'PERPETUAL', 'LINEAR_PERPETUAL', 'ETH', 'USDT', 'USDT',
 1, 'USDT', 0.01, 0.01, 0.01, 5000, 5, 10000000, 2, 2,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100, 0.01, 0.005, 5000000, 8, 0.0001, 0.003, -0.003,
 10000, 3, 'TRADING', now(), now(), now())
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
    symbol, version, bracket_no, notional_floor, notional_cap,
    max_leverage, initial_margin_rate, maintenance_margin_rate
) VALUES
('BTC-USDT', 1, 1, 0, 50000, 100, 0.01, 0.005),
('BTC-USDT', 1, 2, 50000, 250000, 50, 0.02, 0.01),
('BTC-USDT', 1, 3, 250000, 10000000, 20, 0.05, 0.025),
('ETH-USDT', 1, 1, 0, 50000, 100, 0.01, 0.005),
('ETH-USDT', 1, 2, 50000, 250000, 50, 0.02, 0.01),
('ETH-USDT', 1, 3, 250000, 10000000, 20, 0.05, 0.025)
ON CONFLICT (symbol, version, bracket_no) DO NOTHING;

INSERT INTO instrument_index_sources (
    symbol, version, source, enabled, base_url, path, source_symbol, parser,
    quote_currency, target_quote_currency, conversion_base_url, conversion_path,
    conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier,
    websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight
) VALUES
('BTC-USDT', 1, 'BINANCE', TRUE, 'https://api.binance.com', '/api/v3/ticker/bookTicker?symbol=BTCUSDT', 'BTCUSDT', 'BINANCE_BOOK_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://stream.binance.com:9443/ws', '{"method":"SUBSCRIBE","params":["btcusdt@bookTicker"],"id":1}', 'BINANCE_BOOK_TICKER', 1),
('BTC-USDT', 1, 'OKX', TRUE, 'https://www.okx.com', '/api/v5/market/ticker?instId=BTC-USDT', 'BTC-USDT', 'OKX_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://ws.okx.com:8443/ws/v5/public', '{"op":"subscribe","args":[{"channel":"tickers","instId":"BTC-USDT"}]}', 'OKX_TICKER', 1),
('BTC-USDT', 1, 'BYBIT', TRUE, 'https://api.bybit.com', '/v5/market/tickers?category=spot&symbol=BTCUSDT', 'BTCUSDT', 'BYBIT_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://stream.bybit.com/v5/public/spot', '{"op":"subscribe","args":["tickers.BTCUSDT"]}', 'BYBIT_TICKER', 1),
('BTC-USDT', 1, 'COINBASE', TRUE, 'https://api.exchange.coinbase.com', '/products/BTC-USD/ticker', 'BTC-USD', 'COINBASE_TICKER',
 'USD', 'USDT', 'https://api.exchange.coinbase.com', '/products/USDT-USD/ticker', 'COINBASE_TICKER', 'DISCOUNT', 'DIVIDE', 0.50,
 TRUE, 'wss://ws-feed.exchange.coinbase.com', '{"type":"subscribe","product_ids":["BTC-USD"],"channels":["ticker"]}', 'COINBASE_TICKER', 1),
('BTC-USDT', 1, 'KRAKEN', TRUE, 'https://api.kraken.com', '/0/public/Ticker?pair=XBTUSDT', 'XBTUSDT', 'KRAKEN_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://ws.kraken.com/v2', '{"method":"subscribe","params":{"channel":"ticker","symbol":["BTC/USDT"]}}', 'KRAKEN_TICKER', 1),
('ETH-USDT', 1, 'BINANCE', TRUE, 'https://api.binance.com', '/api/v3/ticker/bookTicker?symbol=ETHUSDT', 'ETHUSDT', 'BINANCE_BOOK_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://stream.binance.com:9443/ws', '{"method":"SUBSCRIBE","params":["ethusdt@bookTicker"],"id":2}', 'BINANCE_BOOK_TICKER', 1),
('ETH-USDT', 1, 'OKX', TRUE, 'https://www.okx.com', '/api/v5/market/ticker?instId=ETH-USDT', 'ETH-USDT', 'OKX_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://ws.okx.com:8443/ws/v5/public', '{"op":"subscribe","args":[{"channel":"tickers","instId":"ETH-USDT"}]}', 'OKX_TICKER', 1),
('ETH-USDT', 1, 'BYBIT', TRUE, 'https://api.bybit.com', '/v5/market/tickers?category=spot&symbol=ETHUSDT', 'ETHUSDT', 'BYBIT_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://stream.bybit.com/v5/public/spot', '{"op":"subscribe","args":["tickers.ETHUSDT"]}', 'BYBIT_TICKER', 1),
('ETH-USDT', 1, 'COINBASE', TRUE, 'https://api.exchange.coinbase.com', '/products/ETH-USD/ticker', 'ETH-USD', 'COINBASE_TICKER',
 'USD', 'USDT', 'https://api.exchange.coinbase.com', '/products/USDT-USD/ticker', 'COINBASE_TICKER', 'DISCOUNT', 'DIVIDE', 0.50,
 TRUE, 'wss://ws-feed.exchange.coinbase.com', '{"type":"subscribe","product_ids":["ETH-USD"],"channels":["ticker"]}', 'COINBASE_TICKER', 1),
('ETH-USDT', 1, 'KRAKEN', TRUE, 'https://api.kraken.com', '/0/public/Ticker?pair=ETHUSDT', 'ETHUSDT', 'KRAKEN_TICKER',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 0.50,
 TRUE, 'wss://ws.kraken.com/v2', '{"method":"subscribe","params":{"channel":"ticker","symbol":["ETH/USDT"]}}', 'KRAKEN_TICKER', 1)
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
