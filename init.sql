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
    user_open_interest_limit_rate_ppm BIGINT NOT NULL DEFAULT 300000,
    user_open_interest_limit_floor_units BIGINT NOT NULL DEFAULT 25000000000000,
    funding_interval_hours      INTEGER NOT NULL,
    interest_rate_ppm           BIGINT NOT NULL,
    funding_rate_cap_ppm        BIGINT NOT NULL,
    funding_rate_floor_ppm      BIGINT NOT NULL,
    impact_notional_units       BIGINT NOT NULL,
    min_valid_index_sources     INTEGER NOT NULL DEFAULT 3,
    expiry_time                 TIMESTAMPTZ,
    delivery_time               TIMESTAMPTZ,
    underlying_symbol           TEXT,
    strike_price_units          BIGINT,
    option_type                 TEXT,
    option_exercise_style       TEXT,
    settlement_method           TEXT,
    status                      TEXT NOT NULL,
    effective_time              TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (symbol, version),
    CONSTRAINT instruments_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT instruments_type_check CHECK (instrument_type IN ('SPOT', 'PERPETUAL', 'DELIVERY', 'OPTION')),
    CONSTRAINT instruments_contract_type_check CHECK (
        contract_type IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                          'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'VANILLA_OPTION')
    ),
    CONSTRAINT instruments_product_contract_check CHECK (
        (instrument_type = 'SPOT' AND contract_type = 'SPOT')
        OR (instrument_type = 'PERPETUAL' AND contract_type IN ('LINEAR_PERPETUAL', 'INVERSE_PERPETUAL'))
        OR (instrument_type = 'DELIVERY' AND contract_type IN ('LINEAR_DELIVERY', 'INVERSE_DELIVERY'))
        OR (instrument_type = 'OPTION' AND contract_type = 'VANILLA_OPTION')
    ),
    CONSTRAINT instruments_underlying_symbol_check CHECK (
        underlying_symbol IS NULL OR underlying_symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    ),
    CONSTRAINT instruments_expiry_metadata_check CHECK (
        (
            instrument_type IN ('SPOT', 'PERPETUAL')
            AND expiry_time IS NULL
            AND delivery_time IS NULL
            AND settlement_method IS NULL
        )
        OR (
            instrument_type IN ('DELIVERY', 'OPTION')
            AND expiry_time IS NOT NULL
            AND delivery_time IS NOT NULL
            AND delivery_time >= expiry_time
            AND settlement_method IN ('CASH', 'PHYSICAL')
        )
    ),
    CONSTRAINT instruments_option_metadata_check CHECK (
        (
            instrument_type <> 'OPTION'
            AND strike_price_units IS NULL
            AND option_type IS NULL
            AND option_exercise_style IS NULL
        )
        OR (
            instrument_type = 'OPTION'
            AND underlying_symbol IS NOT NULL
            AND strike_price_units > 0
            AND option_type IN ('CALL', 'PUT')
            AND option_exercise_style IN ('EUROPEAN', 'AMERICAN')
        )
    ),
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
        AND user_open_interest_limit_rate_ppm >= 0
        AND user_open_interest_limit_floor_units > 0
        AND funding_interval_hours >= 0
        AND (instrument_type <> 'PERPETUAL' OR funding_interval_hours > 0)
        AND funding_rate_cap_ppm >= funding_rate_floor_ppm
        AND impact_notional_units > 0
        AND min_valid_index_sources > 0
    )
);

CREATE INDEX IF NOT EXISTS instruments_status_idx
    ON instruments (status, instrument_type, symbol);

CREATE INDEX IF NOT EXISTS instruments_updated_page_idx
    ON instruments (updated_at DESC, symbol DESC, version DESC);

CREATE INDEX IF NOT EXISTS instruments_created_page_idx
    ON instruments (created_at DESC, symbol DESC, version DESC);

DO $$
BEGIN
    IF to_regclass('public.instruments') IS NOT NULL THEN
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS expiry_time TIMESTAMPTZ;
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS delivery_time TIMESTAMPTZ;
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS underlying_symbol TEXT;
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS strike_price_units BIGINT;
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS option_type TEXT;
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS option_exercise_style TEXT;
        ALTER TABLE instruments ADD COLUMN IF NOT EXISTS settlement_method TEXT;

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_type_check;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_type_check CHECK (
                instrument_type IN ('SPOT', 'PERPETUAL', 'DELIVERY', 'OPTION')
            );

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_contract_type_check;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_contract_type_check CHECK (
                contract_type IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                                  'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'VANILLA_OPTION')
            );

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_product_contract_check;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_product_contract_check CHECK (
                (instrument_type = 'SPOT' AND contract_type = 'SPOT')
                OR (instrument_type = 'PERPETUAL' AND contract_type IN ('LINEAR_PERPETUAL', 'INVERSE_PERPETUAL'))
                OR (instrument_type = 'DELIVERY' AND contract_type IN ('LINEAR_DELIVERY', 'INVERSE_DELIVERY'))
                OR (instrument_type = 'OPTION' AND contract_type = 'VANILLA_OPTION')
            );

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_underlying_symbol_check;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_underlying_symbol_check CHECK (
                underlying_symbol IS NULL OR underlying_symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
            );

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_expiry_metadata_check;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_expiry_metadata_check CHECK (
                (
                    instrument_type IN ('SPOT', 'PERPETUAL')
                    AND expiry_time IS NULL
                    AND delivery_time IS NULL
                    AND settlement_method IS NULL
                )
                OR (
                    instrument_type IN ('DELIVERY', 'OPTION')
                    AND expiry_time IS NOT NULL
                    AND delivery_time IS NOT NULL
                    AND delivery_time >= expiry_time
                    AND settlement_method IN ('CASH', 'PHYSICAL')
                )
            );

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_option_metadata_check;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_option_metadata_check CHECK (
                (
                    instrument_type <> 'OPTION'
                    AND strike_price_units IS NULL
                    AND option_type IS NULL
                    AND option_exercise_style IS NULL
                )
                OR (
                    instrument_type = 'OPTION'
                    AND underlying_symbol IS NOT NULL
                    AND strike_price_units > 0
                    AND option_type IN ('CALL', 'PUT')
                    AND option_exercise_style IN ('EUROPEAN', 'AMERICAN')
                )
            );

        ALTER TABLE instruments DROP CONSTRAINT IF EXISTS instruments_positive_values;
        ALTER TABLE instruments
            ADD CONSTRAINT instruments_positive_values CHECK (
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
                AND user_open_interest_limit_rate_ppm >= 0
                AND user_open_interest_limit_floor_units > 0
                AND funding_interval_hours >= 0
                AND (instrument_type <> 'PERPETUAL' OR funding_interval_hours > 0)
                AND funding_rate_cap_ppm >= funding_rate_floor_ppm
                AND impact_notional_units > 0
                AND min_valid_index_sources > 0
            );
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS instrument_current_versions (
    symbol              TEXT PRIMARY KEY,
    version             BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT instrument_current_versions_symbol_version_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version)
);

CREATE TABLE IF NOT EXISTS instrument_product_current_versions (
    product_line        TEXT NOT NULL,
    symbol              TEXT NOT NULL,
    version             BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, symbol),
    CONSTRAINT instrument_product_current_versions_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT instrument_product_current_versions_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT instrument_product_current_versions_symbol_version_fk
        FOREIGN KEY (symbol, version) REFERENCES instruments(symbol, version)
);

CREATE INDEX IF NOT EXISTS instrument_product_current_versions_symbol_idx
    ON instrument_product_current_versions (symbol, product_line);

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
    user_open_interest_limit_rate_ppm, user_open_interest_limit_floor_units,
    funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
    impact_notional_units, min_valid_index_sources,
    expiry_time, delivery_time, underlying_symbol, strike_price_units,
    option_type, option_exercise_style, settlement_method,
    status, effective_time, created_at, updated_at
) VALUES
('BTC-USDT', 1, 'PERPETUAL', 'LINEAR_PERPETUAL', 'BTC', 'USDT', 'USDT',
 1000000, 'USDT', 10000000, 100000, 1, 100000, 500000000, 1000000000000000, 10000, 1, 3,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 1000000000000000000, 1000000, 1000000000000000000, 8, 100, 3000, -3000,
 1000000000000, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'TRADING', now(), now(), now()),
('ETH-USDT', 1, 'PERPETUAL', 'LINEAR_PERPETUAL', 'ETH', 'USDT', 'USDT',
 1000000, 'USDT', 1000000, 10000000000000000, 1, 500000, 500000000, 1000000000000000, 10000, 2, 2,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 1000000000000000000, 1000000, 1000000000000000000, 8, 100, 3000, -3000,
 1000000000000, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'TRADING', now(), now(), now())
ON CONFLICT (symbol, version) DO NOTHING;

INSERT INTO instrument_current_versions (symbol, version, updated_at)
VALUES ('BTC-USDT', 1, now()), ('ETH-USDT', 1, now())
ON CONFLICT (symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_product_current_versions (product_line, symbol, version, updated_at)
SELECT CASE i.contract_type
           WHEN 'SPOT' THEN 'SPOT'
           WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
           WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
           WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
           WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
           WHEN 'VANILLA_OPTION' THEN 'OPTION'
           ELSE 'LINEAR_PERPETUAL'
       END,
       c.symbol,
       c.version,
       c.updated_at
  FROM instrument_current_versions c
  JOIN instruments i ON i.symbol = c.symbol AND i.version = c.version
ON CONFLICT (product_line, symbol) DO UPDATE SET
    version = EXCLUDED.version,
    updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_symbol_sequences (symbol, version, updated_at)
VALUES ('BTC-USDT', 1, now()), ('ETH-USDT', 1, now())
ON CONFLICT (symbol) DO UPDATE SET
    version = GREATEST(instrument_symbol_sequences.version, EXCLUDED.version),
    updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_risk_brackets (
    symbol, version, bracket_no, notional_floor_units, notional_cap_units,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
) VALUES
('BTC-USDT', 1, 1, 0, 100000000000000, 100000000, 10000, 5000),
('BTC-USDT', 1, 2, 100000000000000, 500000000000000, 50000000, 20000, 10000),
('BTC-USDT', 1, 3, 500000000000000, 1000000000000000000, 20000000, 50000, 25000),
('ETH-USDT', 1, 1, 0, 100000000000000, 100000000, 10000, 5000),
('ETH-USDT', 1, 2, 100000000000000, 500000000000000, 50000000, 20000, 10000),
('ETH-USDT', 1, 3, 500000000000000, 1000000000000000000, 20000000, 50000, 25000)
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

CREATE INDEX IF NOT EXISTS price_index_ticks_event_time_brin
    ON price_index_ticks USING BRIN (event_time);

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
    product_line                TEXT NOT NULL,
    symbol                      TEXT NOT NULL,
    instrument_version          BIGINT NOT NULL,
    sequence                    BIGINT NOT NULL,
    mark_price                  NUMERIC(38, 18) NOT NULL,
    mark_price_units            BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
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
    published_at                TIMESTAMPTZ NOT NULL,
    calculation_inputs          JSONB NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, sequence),
    CONSTRAINT price_mark_ticks_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT price_mark_ticks_status CHECK (status IN ('HEALTHY', 'DEGRADED', 'STALE', 'INSUFFICIENT_SOURCES', 'CLAMPED')),
    CONSTRAINT price_mark_ticks_positive_units CHECK (mark_price_units > 0),
    CONSTRAINT price_mark_ticks_positive_ticks CHECK (mark_price_ticks > 0),
    CONSTRAINT price_mark_ticks_valid_book CHECK (best_bid_price <= best_ask_price),
    CONSTRAINT price_mark_ticks_valid_clamp CHECK (clamp_low <= mark_price AND mark_price <= clamp_high)
);

CREATE INDEX IF NOT EXISTS price_mark_ticks_query_idx
    ON price_mark_ticks (symbol, event_time DESC);

CREATE INDEX IF NOT EXISTS price_mark_ticks_retention_idx
    ON price_mark_ticks USING BRIN (event_time) WITH (pages_per_range = 64);

CREATE SEQUENCE IF NOT EXISTS funding_settlement_id_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;
CREATE SEQUENCE IF NOT EXISTS funding_payment_id_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 4096;

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
    instrument_version          BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    total_long_payment_units    BIGINT NOT NULL DEFAULT 0,
    total_short_payment_units   BIGINT NOT NULL DEFAULT 0,
    position_count              INTEGER NOT NULL DEFAULT 0,
    expected_payment_count      INTEGER NOT NULL DEFAULT 0,
    applied_payment_count       INTEGER NOT NULL DEFAULT 0,
    rejected_payment_count      INTEGER NOT NULL DEFAULT 0,
    scan_user_id                BIGINT NOT NULL DEFAULT 0,
    scan_margin_mode            TEXT NOT NULL DEFAULT '',
    scan_position_side          TEXT NOT NULL DEFAULT '',
    scan_completed              BOOLEAN NOT NULL DEFAULT FALSE,
    status                      TEXT NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT funding_settlements_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT funding_settlements_market_input_positive CHECK (
        instrument_version > 0 AND mark_price_ticks > 0
    ),
    CONSTRAINT funding_settlements_scan_cursor_check CHECK (
        scan_user_id >= 0
        AND scan_margin_mode IN ('', 'CROSS', 'ISOLATED')
        AND scan_position_side IN ('', 'NET', 'LONG', 'SHORT')
    ),
    CONSTRAINT funding_settlements_position_count_non_negative CHECK (
        position_count >= 0 AND expected_payment_count >= 0
        AND applied_payment_count >= 0 AND rejected_payment_count >= 0
        AND applied_payment_count + rejected_payment_count <= expected_payment_count
    ),
    CONSTRAINT funding_settlements_status_check CHECK (
        status IN ('PROCESSING', 'WAITING_ACCOUNTS', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT funding_settlements_completion_check CHECK (
        (status = 'COMPLETED'
            AND applied_payment_count = expected_payment_count
            AND rejected_payment_count = 0)
        OR (status = 'FAILED' AND rejected_payment_count > 0)
        OR status IN ('PROCESSING', 'WAITING_ACCOUNTS')
    )
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
    margin_mode             TEXT NOT NULL DEFAULT 'CROSS',
    position_side           TEXT NOT NULL DEFAULT 'NET',
    asset                   TEXT NOT NULL,
    signed_quantity_steps   BIGINT NOT NULL,
    notional_units          BIGINT NOT NULL,
    funding_rate_ppm        BIGINT NOT NULL,
    amount_units            BIGINT NOT NULL,
    command_id              VARCHAR(160) NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'PENDING',
    applied_at              TIMESTAMPTZ,
    rejected_at             TIMESTAMPTZ,
    error_code              VARCHAR(80),
    error_message           VARCHAR(1000),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT funding_payments_settlement_fk
        FOREIGN KEY (settlement_id) REFERENCES funding_settlements(settlement_id),
    CONSTRAINT funding_payments_user_positive CHECK (user_id > 0),
    CONSTRAINT funding_payments_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT funding_payments_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT funding_payments_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT funding_payments_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT funding_payments_notional_non_negative CHECK (notional_units >= 0),
    CONSTRAINT funding_payments_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT funding_payments_status_check CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED')),
    CONSTRAINT funding_payments_terminal_check CHECK (
        (status = 'PENDING' AND applied_at IS NULL AND rejected_at IS NULL)
        OR (status = 'APPLIED' AND applied_at IS NOT NULL AND rejected_at IS NULL)
        OR (status = 'REJECTED' AND applied_at IS NULL AND rejected_at IS NOT NULL)
    )
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

CREATE INDEX IF NOT EXISTS funding_payments_pending_command_idx
    ON funding_payments (command_id)
    INCLUDE (settlement_id, user_id)
    WHERE status = 'PENDING';

CREATE SEQUENCE IF NOT EXISTS trading_order_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS trading_event_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS trading_command_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS trading_outbox_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS trading_margin_reservation_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS trading_spot_reservation_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS trading_fee_schedule_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;
CREATE SEQUENCE IF NOT EXISTS trading_matching_symbol_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;
CREATE SEQUENCE IF NOT EXISTS trading_matching_asset_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;
CREATE SEQUENCE IF NOT EXISTS trading_trigger_order_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;
CREATE SEQUENCE IF NOT EXISTS trading_algo_order_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;

CREATE TABLE IF NOT EXISTS trading_fee_schedules (
    fee_schedule_id     BIGINT PRIMARY KEY,
    product_line        TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id             BIGINT NOT NULL,
    symbol              TEXT,
    maker_fee_rate_ppm  BIGINT NOT NULL,
    taker_fee_rate_ppm  BIGINT NOT NULL,
    source_type         TEXT NOT NULL DEFAULT 'USER_OVERRIDE',
    tier_code           TEXT,
    reason              TEXT NOT NULL,
    status              TEXT NOT NULL,
    effective_time      TIMESTAMPTZ NOT NULL,
    expire_time         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_fee_schedules_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_fee_schedules_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_fee_schedules_symbol_format CHECK (
        symbol IS NULL OR symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    ),
    CONSTRAINT trading_fee_schedules_source_type_check CHECK (
        source_type IN ('USER_OVERRIDE', 'VIP', 'MARKET_MAKER', 'PROMOTION', 'RISK_OVERRIDE')
    ),
    CONSTRAINT trading_fee_schedules_tier_code_format CHECK (
        tier_code IS NULL OR tier_code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'
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

DO $$
BEGIN
    ALTER TABLE trading_fee_schedules
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_fee_schedules
        DROP CONSTRAINT IF EXISTS trading_fee_schedules_product_line_check;
    ALTER TABLE trading_fee_schedules
        ADD CONSTRAINT trading_fee_schedules_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS trading_fee_schedules_user_symbol_idx;
CREATE INDEX IF NOT EXISTS trading_fee_schedules_user_symbol_idx
    ON trading_fee_schedules (product_line, user_id, symbol, status, effective_time DESC, fee_schedule_id DESC);

DROP INDEX IF EXISTS trading_fee_schedules_user_global_idx;
CREATE INDEX IF NOT EXISTS trading_fee_schedules_user_global_idx
    ON trading_fee_schedules (product_line, user_id, status, effective_time DESC, fee_schedule_id DESC)
    WHERE symbol IS NULL;

CREATE TABLE IF NOT EXISTS trading_fee_tiers (
    tier_code               TEXT PRIMARY KEY,
    source_type             TEXT NOT NULL DEFAULT 'VIP',
    qualification_mode      TEXT NOT NULL DEFAULT 'VOLUME_OR_BALANCE',
    min_30d_volume_units    BIGINT NOT NULL DEFAULT 0,
    min_asset_balance_units BIGINT NOT NULL DEFAULT 0,
    maker_fee_rate_ppm      BIGINT NOT NULL,
    taker_fee_rate_ppm      BIGINT NOT NULL,
    priority                INTEGER NOT NULL,
    status                  TEXT NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_fee_tiers_source_type_check CHECK (source_type IN ('VIP', 'MARKET_MAKER')),
    CONSTRAINT trading_fee_tiers_qualification_mode_check CHECK (
        qualification_mode IN ('VOLUME_ONLY', 'BALANCE_ONLY', 'VOLUME_OR_BALANCE', 'VOLUME_AND_BALANCE')
    ),
    CONSTRAINT trading_fee_tiers_non_negative_thresholds CHECK (
        min_30d_volume_units >= 0 AND min_asset_balance_units >= 0 AND priority >= 0
    ),
    CONSTRAINT trading_fee_tiers_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT trading_fee_tiers_fee_range CHECK (
        maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND maker_fee_rate_ppm <= taker_fee_rate_ppm
    )
);

CREATE INDEX IF NOT EXISTS trading_fee_tiers_active_idx
    ON trading_fee_tiers (status, priority DESC, min_30d_volume_units DESC, min_asset_balance_units DESC);

INSERT INTO trading_fee_tiers (
    tier_code, source_type, qualification_mode, min_30d_volume_units, min_asset_balance_units,
    maker_fee_rate_ppm, taker_fee_rate_ppm, priority, status, created_at, updated_at
) VALUES
('VIP1', 'VIP', 'VOLUME_OR_BALANCE', 1000000000000000, 10000000000000, 180, 450, 10, 'ACTIVE', now(), now()),
('VIP2', 'VIP', 'VOLUME_OR_BALANCE', 5000000000000000, 25000000000000, 160, 400, 20, 'ACTIVE', now(), now()),
('VIP3', 'VIP', 'VOLUME_OR_BALANCE', 10000000000000000, 50000000000000, 140, 350, 30, 'ACTIVE', now(), now()),
('VIP4', 'VIP', 'VOLUME_OR_BALANCE', 25000000000000000, 100000000000000, 100, 300, 40, 'ACTIVE', now(), now()),
('VIP5', 'VIP', 'VOLUME_OR_BALANCE', 50000000000000000, 200000000000000, 0, 250, 50, 'ACTIVE', now(), now())
ON CONFLICT (tier_code) DO UPDATE SET
    source_type = EXCLUDED.source_type,
    qualification_mode = EXCLUDED.qualification_mode,
    min_30d_volume_units = EXCLUDED.min_30d_volume_units,
    min_asset_balance_units = EXCLUDED.min_asset_balance_units,
    maker_fee_rate_ppm = EXCLUDED.maker_fee_rate_ppm,
    taker_fee_rate_ppm = EXCLUDED.taker_fee_rate_ppm,
    priority = EXCLUDED.priority,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

CREATE TABLE IF NOT EXISTS trading_user_fee_tiers (
    product_line              TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id                   BIGINT NOT NULL,
    tier_code                 TEXT,
    source_type               TEXT,
    fee_schedule_id           BIGINT NOT NULL UNIQUE,
    maker_fee_rate_ppm        BIGINT NOT NULL DEFAULT 0,
    taker_fee_rate_ppm        BIGINT NOT NULL DEFAULT 0,
    trailing_30d_volume_units BIGINT NOT NULL DEFAULT 0,
    total_asset_balance_units BIGINT NOT NULL DEFAULT 0,
    status                    TEXT NOT NULL,
    effective_time            TIMESTAMPTZ NOT NULL,
    calculated_at             TIMESTAMPTZ NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, user_id),
    CONSTRAINT trading_user_fee_tiers_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_user_fee_tiers_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_user_fee_tiers_tier_fk
        FOREIGN KEY (tier_code) REFERENCES trading_fee_tiers(tier_code),
    CONSTRAINT trading_user_fee_tiers_source_type_check CHECK (
        source_type IS NULL OR source_type IN ('VIP', 'MARKET_MAKER')
    ),
    CONSTRAINT trading_user_fee_tiers_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT trading_user_fee_tiers_non_negative_values CHECK (
        fee_schedule_id > 0
        AND maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND maker_fee_rate_ppm <= taker_fee_rate_ppm
        AND trailing_30d_volume_units >= 0
        AND total_asset_balance_units >= 0
    )
);

DO $$
BEGIN
    ALTER TABLE trading_user_fee_tiers
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_user_fee_tiers
        DROP CONSTRAINT IF EXISTS trading_user_fee_tiers_product_line_check;
    ALTER TABLE trading_user_fee_tiers
        ADD CONSTRAINT trading_user_fee_tiers_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
    ALTER TABLE trading_user_fee_tiers
        DROP CONSTRAINT IF EXISTS trading_user_fee_tiers_pkey;
    ALTER TABLE trading_user_fee_tiers
        ADD CONSTRAINT trading_user_fee_tiers_pkey PRIMARY KEY (product_line, user_id);
END $$;

DROP INDEX IF EXISTS trading_user_fee_tiers_status_idx;
CREATE INDEX IF NOT EXISTS trading_user_fee_tiers_status_idx
    ON trading_user_fee_tiers (product_line, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS trading_leverage_settings (
    product_line        TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id             BIGINT NOT NULL,
    symbol              TEXT NOT NULL,
    margin_mode         TEXT NOT NULL DEFAULT 'CROSS',
    leverage_ppm        BIGINT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, user_id, symbol, margin_mode),
    CONSTRAINT trading_leverage_settings_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_leverage_settings_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_leverage_settings_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT trading_leverage_settings_symbol_fk
        FOREIGN KEY (symbol) REFERENCES instrument_current_versions(symbol),
    CONSTRAINT trading_leverage_settings_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT trading_leverage_settings_leverage_range CHECK (
        leverage_ppm BETWEEN 1000000 AND 1000000000
    )
);

DO $$
BEGIN
    ALTER TABLE trading_leverage_settings
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_leverage_settings
        DROP CONSTRAINT IF EXISTS trading_leverage_settings_product_line_check;
    ALTER TABLE trading_leverage_settings
        ADD CONSTRAINT trading_leverage_settings_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
    ALTER TABLE trading_leverage_settings
        DROP CONSTRAINT IF EXISTS trading_leverage_settings_pkey;
    ALTER TABLE trading_leverage_settings
        ADD CONSTRAINT trading_leverage_settings_pkey PRIMARY KEY (
            product_line, user_id, symbol, margin_mode
        );
END $$;

DROP INDEX IF EXISTS trading_leverage_settings_symbol_idx;
CREATE INDEX IF NOT EXISTS trading_leverage_settings_symbol_idx
    ON trading_leverage_settings (product_line, symbol, margin_mode, updated_at DESC);

CREATE TABLE IF NOT EXISTS account_position_modes (
    product_line        TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id             BIGINT NOT NULL,
    position_mode       TEXT NOT NULL DEFAULT 'ONE_WAY',
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, user_id),
    CONSTRAINT account_position_modes_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_position_modes_user_positive CHECK (user_id > 0),
    CONSTRAINT account_position_modes_mode_check CHECK (position_mode IN ('ONE_WAY', 'HEDGE'))
);

DO $$
BEGIN
    ALTER TABLE account_position_modes
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE account_position_modes
        DROP CONSTRAINT IF EXISTS account_position_modes_product_line_check;
    ALTER TABLE account_position_modes
        ADD CONSTRAINT account_position_modes_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
    ALTER TABLE account_position_modes
        DROP CONSTRAINT IF EXISTS account_position_modes_pkey;
    ALTER TABLE account_position_modes
        ADD CONSTRAINT account_position_modes_pkey PRIMARY KEY (product_line, user_id);
END $$;

CREATE TABLE IF NOT EXISTS trading_orders (
    order_id                    BIGINT PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
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
    position_side               TEXT NOT NULL DEFAULT 'NET',
    maker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    taker_fee_rate_ppm          BIGINT NOT NULL DEFAULT 0,
    reduce_only                 BOOLEAN NOT NULL DEFAULT FALSE,
    post_only                   BOOLEAN NOT NULL DEFAULT FALSE,
    status                      TEXT NOT NULL,
    reject_reason               TEXT,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    revision                    BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT trading_orders_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_orders_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_orders_revision_positive CHECK (revision > 0),
    CONSTRAINT trading_orders_client_id_length CHECK (client_order_id IS NULL OR length(client_order_id) <= 64),
    CONSTRAINT trading_orders_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT trading_orders_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT trading_orders_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT trading_orders_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT trading_orders_fee_rate_check CHECK (
        maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT trading_orders_type_check CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT trading_orders_tif_check CHECK (time_in_force IN ('GTC', 'IOC', 'FOK', 'GTX')),
    CONSTRAINT trading_orders_status_check CHECK (
        status IN ('PENDING_RESERVE', 'ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
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

DO $$
BEGIN
    ALTER TABLE trading_orders
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_orders
        ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1;
    UPDATE trading_orders SET revision = 1 WHERE revision < 1;
    ALTER TABLE trading_orders
        DROP CONSTRAINT IF EXISTS trading_orders_revision_positive;
    ALTER TABLE trading_orders
        ADD CONSTRAINT trading_orders_revision_positive CHECK (revision > 0);
    ALTER TABLE trading_orders
        DROP CONSTRAINT IF EXISTS trading_orders_product_line_check;
    ALTER TABLE trading_orders
        ADD CONSTRAINT trading_orders_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
    UPDATE trading_orders o
       SET product_line = CASE i.contract_type
           WHEN 'SPOT' THEN 'SPOT'
           WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
           WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
           WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
           WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
           WHEN 'VANILLA_OPTION' THEN 'OPTION'
           ELSE o.product_line
       END
      FROM instruments i
     WHERE i.symbol = o.symbol
       AND i.version = o.instrument_version;
END $$;

DROP INDEX IF EXISTS trading_orders_user_client_order_uidx;
CREATE UNIQUE INDEX IF NOT EXISTS trading_orders_user_client_order_uidx
    ON trading_orders (product_line, user_id, client_order_id)
    WHERE client_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_orders_user_status_idx
    ON trading_orders (user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS trading_orders_symbol_status_idx
    ON trading_orders (symbol, status, created_at DESC);

CREATE INDEX IF NOT EXISTS trading_orders_open_query_idx
    ON trading_orders (user_id, symbol, created_at DESC)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED');

-- Redis open-order projection rebuilds and PostgreSQL fallback use product-scoped order-id keysets.
CREATE INDEX IF NOT EXISTS trading_orders_open_view_user_idx
    ON trading_orders (product_line, user_id, order_id DESC)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED');

CREATE INDEX IF NOT EXISTS trading_orders_open_view_user_symbol_idx
    ON trading_orders (product_line, user_id, symbol, order_id DESC)
    WHERE status IN ('ACCEPTED', 'PARTIALLY_FILLED');

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

CREATE TABLE IF NOT EXISTS trading_cancel_all_after (
    product_line                    TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id                         BIGINT NOT NULL,
    symbol_scope                    TEXT NOT NULL,
    countdown_ms                    BIGINT NOT NULL,
    status                          TEXT NOT NULL,
    trigger_at                      TIMESTAMPTZ,
    last_heartbeat_at               TIMESTAMPTZ NOT NULL,
    triggered_at                    TIMESTAMPTZ,
    canceled_order_count            INTEGER NOT NULL DEFAULT 0,
    canceled_trigger_order_count    INTEGER NOT NULL DEFAULT 0,
    trace_id                        TEXT,
    last_error                      TEXT,
    created_at                      TIMESTAMPTZ NOT NULL,
    updated_at                      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, user_id, symbol_scope),
    CONSTRAINT trading_cancel_all_after_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_cancel_all_after_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_cancel_all_after_scope_check CHECK (
        symbol_scope = '*' OR symbol_scope ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    ),
    CONSTRAINT trading_cancel_all_after_countdown_check CHECK (countdown_ms BETWEEN 0 AND 120000),
    CONSTRAINT trading_cancel_all_after_status_check CHECK (
        status IN ('ACTIVE', 'TRIGGERING', 'TRIGGERED', 'DISABLED')
    ),
    CONSTRAINT trading_cancel_all_after_trigger_check CHECK (
        (status IN ('ACTIVE', 'TRIGGERING') AND trigger_at IS NOT NULL AND countdown_ms > 0)
        OR (status IN ('TRIGGERED', 'DISABLED'))
    ),
    CONSTRAINT trading_cancel_all_after_counts_non_negative CHECK (
        canceled_order_count >= 0 AND canceled_trigger_order_count >= 0
    )
);

DO $$
BEGIN
    ALTER TABLE trading_cancel_all_after
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_cancel_all_after
        DROP CONSTRAINT IF EXISTS trading_cancel_all_after_product_line_check;
    ALTER TABLE trading_cancel_all_after
        ADD CONSTRAINT trading_cancel_all_after_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
    ALTER TABLE trading_cancel_all_after
        DROP CONSTRAINT IF EXISTS trading_cancel_all_after_pkey;
    ALTER TABLE trading_cancel_all_after
        ADD CONSTRAINT trading_cancel_all_after_pkey PRIMARY KEY (product_line, user_id, symbol_scope);
END $$;

DROP INDEX IF EXISTS trading_cancel_all_after_due_idx;
CREATE INDEX IF NOT EXISTS trading_cancel_all_after_due_idx
    ON trading_cancel_all_after (product_line, trigger_at, user_id, symbol_scope)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS trading_algo_orders (
    algo_order_id              BIGINT PRIMARY KEY,
    product_line               TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id                    BIGINT NOT NULL,
    client_algo_order_id       TEXT,
    symbol                     TEXT NOT NULL,
    algo_type                  TEXT NOT NULL,
    side                       TEXT NOT NULL,
    price_ticks                BIGINT NOT NULL,
    quantity_steps             BIGINT NOT NULL,
    child_quantity_steps       BIGINT NOT NULL,
    interval_seconds           BIGINT NOT NULL,
    duration_seconds           BIGINT NOT NULL,
    margin_mode                TEXT NOT NULL DEFAULT 'CROSS',
    position_side              TEXT NOT NULL DEFAULT 'NET',
    reduce_only                BOOLEAN NOT NULL DEFAULT FALSE,
    post_only                  BOOLEAN NOT NULL DEFAULT FALSE,
    time_in_force              TEXT NOT NULL,
    status                     TEXT NOT NULL,
    current_order_id           BIGINT,
    reject_reason              TEXT,
    trace_id                   TEXT,
    start_at                   TIMESTAMPTZ NOT NULL,
    next_slice_at              TIMESTAMPTZ,
    completed_at               TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_algo_orders_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_algo_orders_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_algo_orders_client_id_length CHECK (
        client_algo_order_id IS NULL OR length(client_algo_order_id) <= 64
    ),
    CONSTRAINT trading_algo_orders_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT trading_algo_orders_symbol_fk
        FOREIGN KEY (symbol) REFERENCES instrument_current_versions(symbol),
    CONSTRAINT trading_algo_orders_type_check CHECK (algo_type IN ('TWAP', 'ICEBERG')),
    CONSTRAINT trading_algo_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT trading_algo_orders_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT trading_algo_orders_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT trading_algo_orders_tif_check CHECK (time_in_force IN ('GTC', 'IOC', 'GTX')),
    CONSTRAINT trading_algo_orders_status_check CHECK (
        status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'CANCELED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT trading_algo_orders_long_values CHECK (
        price_ticks >= 0
        AND quantity_steps > 0
        AND child_quantity_steps > 0
        AND child_quantity_steps <= quantity_steps
        AND interval_seconds > 0
        AND duration_seconds > 0
    ),
    CONSTRAINT trading_algo_orders_algo_rules CHECK (
        (algo_type = 'TWAP' AND time_in_force = 'IOC' AND post_only = FALSE)
        OR (algo_type = 'ICEBERG' AND price_ticks > 0 AND time_in_force IN ('GTC', 'GTX'))
    ),
    CONSTRAINT trading_algo_orders_current_order_fk
        FOREIGN KEY (current_order_id) REFERENCES trading_orders(order_id)
);

DO $$
BEGIN
    ALTER TABLE trading_algo_orders
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE trading_algo_orders
        DROP CONSTRAINT IF EXISTS trading_algo_orders_product_line_check;
    ALTER TABLE trading_algo_orders
        ADD CONSTRAINT trading_algo_orders_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
    UPDATE trading_algo_orders a
       SET product_line = CASE i.contract_type
           WHEN 'SPOT' THEN 'SPOT'
           WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
           WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
           WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
           WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
           WHEN 'VANILLA_OPTION' THEN 'OPTION'
           ELSE a.product_line
       END
      FROM instrument_current_versions c
      JOIN instruments i ON i.symbol = c.symbol AND i.version = c.version
     WHERE c.symbol = a.symbol;
END $$;

DROP INDEX IF EXISTS trading_algo_orders_user_client_uidx;
CREATE UNIQUE INDEX IF NOT EXISTS trading_algo_orders_user_client_uidx
    ON trading_algo_orders (product_line, user_id, client_algo_order_id)
    WHERE client_algo_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_algo_orders_due_idx
    ON trading_algo_orders (product_line, next_slice_at, algo_order_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX IF NOT EXISTS trading_algo_orders_user_status_idx
    ON trading_algo_orders (user_id, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS trading_algo_order_children (
    algo_order_id              BIGINT NOT NULL,
    slice_index                INTEGER NOT NULL,
    order_id                   BIGINT NOT NULL,
    client_order_id            TEXT NOT NULL,
    quantity_steps             BIGINT NOT NULL,
    price_ticks                BIGINT NOT NULL,
    order_type                 TEXT NOT NULL,
    time_in_force              TEXT NOT NULL,
    status                     TEXT NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (algo_order_id, slice_index),
    CONSTRAINT trading_algo_order_children_algo_fk
        FOREIGN KEY (algo_order_id) REFERENCES trading_algo_orders(algo_order_id),
    CONSTRAINT trading_algo_order_children_order_fk
        FOREIGN KEY (order_id) REFERENCES trading_orders(order_id),
    CONSTRAINT trading_algo_order_children_client_id_unique UNIQUE (client_order_id),
    CONSTRAINT trading_algo_order_children_values CHECK (
        slice_index > 0
        AND order_id > 0
        AND quantity_steps > 0
        AND price_ticks >= 0
    ),
    CONSTRAINT trading_algo_order_children_type_check CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT trading_algo_order_children_tif_check CHECK (time_in_force IN ('GTC', 'IOC', 'GTX')),
    CONSTRAINT trading_algo_order_children_status_check CHECK (
        status IN ('PENDING_RESERVE', 'ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
    )
);

CREATE INDEX IF NOT EXISTS trading_algo_order_children_order_idx
    ON trading_algo_order_children (order_id);

CREATE TABLE IF NOT EXISTS trading_symbol_open_interest_shards (
    product_line            TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    symbol                  TEXT NOT NULL,
    shard_id                SMALLINT NOT NULL,
    long_quantity_steps     BIGINT NOT NULL DEFAULT 0,
    short_quantity_steps    BIGINT NOT NULL DEFAULT 0,
    updated_at              TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (product_line, symbol, shard_id),
    CONSTRAINT trading_symbol_open_interest_shards_symbol_fk
        FOREIGN KEY (symbol) REFERENCES instrument_current_versions(symbol),
    CONSTRAINT trading_symbol_open_interest_shards_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_symbol_open_interest_shards_shard_check CHECK (shard_id >= 0 AND shard_id < 64),
    CONSTRAINT trading_symbol_open_interest_shards_non_negative CHECK (
        long_quantity_steps >= 0
        AND short_quantity_steps >= 0
    )
);

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

CREATE TABLE IF NOT EXISTS trading_trigger_orders (
    trigger_order_id           BIGINT PRIMARY KEY,
    product_line               TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id                    BIGINT NOT NULL,
    client_trigger_order_id    TEXT,
    oco_group_id               TEXT,
    symbol                     TEXT NOT NULL,
    side                       TEXT NOT NULL,
    trigger_type               TEXT NOT NULL,
    trigger_condition          TEXT NOT NULL,
    trigger_price_ticks        BIGINT NOT NULL,
    activation_price_ticks     BIGINT,
    callback_rate_ppm          BIGINT,
    highest_price_ticks        BIGINT,
    lowest_price_ticks         BIGINT,
    activated_at               TIMESTAMPTZ,
    order_type                 TEXT NOT NULL,
    time_in_force              TEXT NOT NULL,
    price_ticks                BIGINT NOT NULL,
    quantity_steps             BIGINT NOT NULL,
    margin_mode                TEXT NOT NULL DEFAULT 'CROSS',
    position_side              TEXT NOT NULL DEFAULT 'NET',
    status                     TEXT NOT NULL,
    placed_order_id            BIGINT,
    trigger_sequence           BIGINT,
    triggered_price_ticks      BIGINT,
    reject_reason              TEXT,
    trace_id                   TEXT,
    expires_at                 TIMESTAMPTZ,
    triggered_at               TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT trading_trigger_orders_user_positive CHECK (user_id > 0),
    CONSTRAINT trading_trigger_orders_product_line_check CHECK (
        product_line IN (
            'SPOT',
            'LINEAR_PERPETUAL',
            'INVERSE_PERPETUAL',
            'LINEAR_DELIVERY',
            'INVERSE_DELIVERY',
            'OPTION'
        )
    ),
    CONSTRAINT trading_trigger_orders_client_id_length CHECK (
        client_trigger_order_id IS NULL OR length(client_trigger_order_id) <= 64
    ),
    CONSTRAINT trading_trigger_orders_oco_group_length CHECK (
        oco_group_id IS NULL OR length(oco_group_id) <= 64
    ),
    CONSTRAINT trading_trigger_orders_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT trading_trigger_orders_symbol_fk
        FOREIGN KEY (symbol) REFERENCES instrument_current_versions(symbol),
    CONSTRAINT trading_trigger_orders_placed_order_fk
        FOREIGN KEY (placed_order_id) REFERENCES trading_orders(order_id),
    CONSTRAINT trading_trigger_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT trading_trigger_orders_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT trading_trigger_orders_type_check CHECK (
        trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS', 'TRAILING_STOP')
    ),
    CONSTRAINT trading_trigger_orders_condition_check CHECK (
        trigger_condition IN ('GREATER_OR_EQUAL', 'LESS_OR_EQUAL')
    ),
    CONSTRAINT trading_trigger_orders_order_type_check CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT trading_trigger_orders_tif_check CHECK (time_in_force IN ('GTC', 'IOC', 'FOK')),
    CONSTRAINT trading_trigger_orders_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT trading_trigger_orders_status_check CHECK (
        status IN ('PENDING', 'TRIGGERING', 'TRIGGERED', 'TRIGGER_FAILED', 'CANCELED', 'EXPIRED')
    ),
    CONSTRAINT trading_trigger_orders_long_values CHECK (
        (
            (trigger_type = 'TRAILING_STOP' AND trigger_price_ticks >= 0)
            OR (trigger_type <> 'TRAILING_STOP' AND trigger_price_ticks > 0)
        )
        AND (activation_price_ticks IS NULL OR activation_price_ticks >= 0)
        AND (callback_rate_ppm IS NULL OR callback_rate_ppm BETWEEN 1000 AND 100000)
        AND (highest_price_ticks IS NULL OR highest_price_ticks > 0)
        AND (lowest_price_ticks IS NULL OR lowest_price_ticks > 0)
        AND price_ticks >= 0
        AND quantity_steps > 0
        AND (trigger_sequence IS NULL OR trigger_sequence > 0)
        AND (triggered_price_ticks IS NULL OR triggered_price_ticks > 0)
    ),
    CONSTRAINT trading_trigger_orders_trailing_check CHECK (
        (
            trigger_type = 'TRAILING_STOP'
            AND callback_rate_ppm IS NOT NULL
            AND order_type = 'MARKET'
        )
        OR (
            trigger_type <> 'TRAILING_STOP'
            AND activation_price_ticks IS NULL
            AND callback_rate_ppm IS NULL
            AND highest_price_ticks IS NULL
            AND lowest_price_ticks IS NULL
            AND activated_at IS NULL
        )
    ),
    CONSTRAINT trading_trigger_orders_market_price_zero CHECK (
        order_type <> 'MARKET' OR price_ticks = 0
    ),
    CONSTRAINT trading_trigger_orders_triggered_state_check CHECK (
        (status IN ('TRIGGERED', 'TRIGGER_FAILED') AND placed_order_id IS NOT NULL)
        OR (status NOT IN ('TRIGGERED', 'TRIGGER_FAILED'))
    )
);

ALTER TABLE trading_trigger_orders
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

ALTER TABLE trading_trigger_orders
    DROP CONSTRAINT IF EXISTS trading_trigger_orders_product_line_check;

ALTER TABLE trading_trigger_orders
    ADD CONSTRAINT trading_trigger_orders_product_line_check CHECK (
        product_line IN (
            'SPOT',
            'LINEAR_PERPETUAL',
            'INVERSE_PERPETUAL',
            'LINEAR_DELIVERY',
            'INVERSE_DELIVERY',
            'OPTION'
        )
    );

UPDATE trading_trigger_orders t
   SET product_line = CASE i.contract_type
       WHEN 'SPOT' THEN 'SPOT'
       WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
       WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
       WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
       WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
       WHEN 'VANILLA_OPTION' THEN 'OPTION'
       ELSE t.product_line
   END
  FROM instrument_current_versions c
  JOIN instruments i ON i.symbol = c.symbol AND i.version = c.version
 WHERE c.symbol = t.symbol;

DROP INDEX IF EXISTS trading_trigger_orders_user_client_uidx;
DROP INDEX IF EXISTS trading_trigger_orders_user_status_idx;
DROP INDEX IF EXISTS trading_trigger_orders_user_oco_idx;
DROP INDEX IF EXISTS trading_trigger_orders_symbol_gte_idx;
DROP INDEX IF EXISTS trading_trigger_orders_symbol_lte_idx;
DROP INDEX IF EXISTS trading_trigger_orders_trailing_pending_idx;
DROP INDEX IF EXISTS trading_trigger_orders_expiry_idx;
DROP INDEX IF EXISTS trading_trigger_orders_triggering_idx;

CREATE UNIQUE INDEX IF NOT EXISTS trading_trigger_orders_user_client_uidx
    ON trading_trigger_orders (product_line, user_id, client_trigger_order_id)
    WHERE client_trigger_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_trigger_orders_user_status_idx
    ON trading_trigger_orders (product_line, user_id, status, created_at DESC, trigger_order_id DESC);

CREATE INDEX IF NOT EXISTS trading_trigger_orders_user_oco_idx
    ON trading_trigger_orders (product_line, user_id, symbol, margin_mode, oco_group_id, status, updated_at DESC)
    WHERE oco_group_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_trigger_orders_symbol_gte_idx
    ON trading_trigger_orders (product_line, symbol, trigger_price_ticks, trigger_order_id)
    WHERE status = 'PENDING'
      AND trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')
      AND trigger_condition = 'GREATER_OR_EQUAL';

CREATE INDEX IF NOT EXISTS trading_trigger_orders_symbol_lte_idx
    ON trading_trigger_orders (product_line, symbol, trigger_price_ticks DESC, trigger_order_id)
    WHERE status = 'PENDING'
      AND trigger_type IN ('TAKE_PROFIT', 'STOP_LOSS')
      AND trigger_condition = 'LESS_OR_EQUAL';

CREATE INDEX IF NOT EXISTS trading_trigger_orders_trailing_pending_idx
    ON trading_trigger_orders (product_line, symbol, trigger_order_id)
    WHERE status = 'PENDING'
      AND trigger_type = 'TRAILING_STOP';

CREATE INDEX IF NOT EXISTS trading_trigger_orders_expiry_idx
    ON trading_trigger_orders (product_line, expires_at, trigger_order_id)
    WHERE status = 'PENDING'
      AND expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_trigger_orders_triggering_idx
    ON trading_trigger_orders (product_line, updated_at, trigger_order_id)
    WHERE status = 'TRIGGERING';

CREATE INDEX IF NOT EXISTS trading_trigger_orders_trace_idx
    ON trading_trigger_orders (trace_id)
    WHERE trace_id IS NOT NULL;

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
    CONSTRAINT trading_order_events_type_check CHECK (
        event_type IN ('RESERVE_PENDING', 'ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED')
    ),
    CONSTRAINT trading_order_events_status_check CHECK (
        status IN ('PENDING_RESERVE', 'ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
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

DROP INDEX IF EXISTS trading_outbox_pending_stream_idx;
CREATE INDEX trading_outbox_pending_stream_idx
    ON trading_outbox_events (aggregate_type, topic, event_key, id)
    INCLUDE (next_attempt_at)
    WHERE published_at IS NULL;

DROP INDEX IF EXISTS trading_outbox_published_cleanup_idx;
CREATE INDEX IF NOT EXISTS trading_outbox_published_owner_cleanup_idx
    ON trading_outbox_events (aggregate_type, published_at, id)
    WHERE published_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS trading_outbox_aggregate_idx
    ON trading_outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS trading_outbox_events_trace_idx
    ON trading_outbox_events ((payload ->> 'traceId'))
    WHERE payload ? 'traceId';

CREATE TABLE IF NOT EXISTS trading_matching_assets (
    asset               TEXT PRIMARY KEY,
    asset_id            INTEGER NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trading_matching_assets_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT trading_matching_assets_asset_id_positive CHECK (asset_id > 0)
);

CREATE TABLE IF NOT EXISTS trading_matching_symbols (
    product_line        TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
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
    CONSTRAINT trading_matching_symbols_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_matching_symbols_symbol_id_positive CHECK (symbol_id > 0),
    CONSTRAINT trading_matching_symbols_base_asset_fk
        FOREIGN KEY (base_asset) REFERENCES trading_matching_assets(asset),
    CONSTRAINT trading_matching_symbols_quote_asset_fk
        FOREIGN KEY (quote_asset) REFERENCES trading_matching_assets(asset),
    CONSTRAINT trading_matching_symbols_settle_asset_fk
        FOREIGN KEY (settle_asset) REFERENCES trading_matching_assets(asset)
);

ALTER TABLE trading_matching_symbols
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

UPDATE trading_matching_symbols s
   SET product_line = CASE i.contract_type
       WHEN 'SPOT' THEN 'SPOT'
       WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
       WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
       WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
       WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
       WHEN 'VANILLA_OPTION' THEN 'OPTION'
       ELSE s.product_line
   END
  FROM instruments i
  JOIN instrument_current_versions c
    ON c.symbol = i.symbol AND c.version = i.version
 WHERE i.symbol = s.symbol;

ALTER TABLE trading_matching_symbols
    DROP CONSTRAINT IF EXISTS trading_matching_symbols_pkey;

ALTER TABLE trading_matching_symbols
    DROP CONSTRAINT IF EXISTS trading_matching_symbols_product_line_check;

ALTER TABLE trading_matching_symbols
    ADD CONSTRAINT trading_matching_symbols_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    );

ALTER TABLE trading_matching_symbols
    ADD CONSTRAINT trading_matching_symbols_pkey PRIMARY KEY (product_line, symbol);

DROP INDEX IF EXISTS trading_matching_symbols_enabled_idx;
CREATE INDEX IF NOT EXISTS trading_matching_symbols_enabled_idx
    ON trading_matching_symbols (product_line, enabled, symbol_id);

CREATE INDEX IF NOT EXISTS trading_matching_symbols_symbol_idx
    ON trading_matching_symbols (symbol);

CREATE TABLE IF NOT EXISTS trading_match_results (
    command_id              BIGINT PRIMARY KEY,
    product_line            TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
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
    CONSTRAINT trading_match_results_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_match_results_quantity_non_negative CHECK (filled_quantity_steps >= 0),
    CONSTRAINT trading_match_results_status_check CHECK (
        order_status IN ('PENDING_RESERVE', 'ACCEPTED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELED', 'PARTIALLY_FILLED', 'FILLED')
    )
);

ALTER TABLE trading_match_results
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

UPDATE trading_match_results r
   SET product_line = o.product_line
  FROM trading_orders o
 WHERE o.order_id = r.order_id;

ALTER TABLE trading_match_results
    DROP CONSTRAINT IF EXISTS trading_match_results_product_line_check;

ALTER TABLE trading_match_results
    ADD CONSTRAINT trading_match_results_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    );

DROP INDEX IF EXISTS trading_match_results_order_idx;
CREATE INDEX IF NOT EXISTS trading_match_results_order_idx
    ON trading_match_results (product_line, order_id, event_time DESC);

DROP INDEX IF EXISTS trading_match_results_symbol_time_idx;
CREATE INDEX IF NOT EXISTS trading_match_results_symbol_time_idx
    ON trading_match_results (product_line, symbol, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_results_trace_idx
    ON trading_match_results (trace_id)
    WHERE trace_id IS NOT NULL;

DROP INDEX IF EXISTS trading_match_results_success_place_idx;
CREATE INDEX IF NOT EXISTS trading_match_results_success_place_idx
    ON trading_match_results (product_line, order_id)
    WHERE command_type = 'PLACE'
      AND result_code = 'SUCCESS';

CREATE TABLE IF NOT EXISTS trading_match_trades (
    trade_id                BIGINT NOT NULL,
    command_id              BIGINT NOT NULL,
    product_line            TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    symbol                  TEXT NOT NULL,
    taker_order_id          BIGINT NOT NULL,
    taker_instrument_version BIGINT NOT NULL,
    taker_user_id           BIGINT NOT NULL,
    taker_side              TEXT NOT NULL,
    taker_margin_mode       TEXT NOT NULL DEFAULT 'CROSS',
    taker_position_side     TEXT NOT NULL DEFAULT 'NET',
    maker_order_id          BIGINT NOT NULL,
    maker_instrument_version BIGINT NOT NULL,
    maker_user_id           BIGINT NOT NULL,
    maker_margin_mode       TEXT NOT NULL DEFAULT 'CROSS',
    maker_position_side     TEXT NOT NULL DEFAULT 'NET',
    taker_fee_rate_ppm      BIGINT NOT NULL DEFAULT 0,
    maker_fee_rate_ppm      BIGINT NOT NULL DEFAULT 0,
    price_ticks             BIGINT NOT NULL,
    quantity_steps          BIGINT NOT NULL,
    taker_order_completed   BOOLEAN NOT NULL,
    maker_order_completed   BOOLEAN NOT NULL,
    trace_id                TEXT,
    event_time              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_line, symbol, trade_id),
    CONSTRAINT trading_match_trades_command_fk
        FOREIGN KEY (command_id) REFERENCES trading_match_results(command_id),
    CONSTRAINT trading_match_trades_taker_instrument_fk
        FOREIGN KEY (symbol, taker_instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_match_trades_maker_instrument_fk
        FOREIGN KEY (symbol, maker_instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT trading_match_trades_side_check CHECK (taker_side IN ('BUY', 'SELL')),
    CONSTRAINT trading_match_trades_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT trading_match_trades_margin_mode_check CHECK (
        taker_margin_mode IN ('CROSS', 'ISOLATED') AND maker_margin_mode IN ('CROSS', 'ISOLATED')
    ),
    CONSTRAINT trading_match_trades_position_side_check CHECK (
        taker_position_side IN ('NET', 'LONG', 'SHORT') AND maker_position_side IN ('NET', 'LONG', 'SHORT')
    ),
    CONSTRAINT trading_match_trades_fee_rate_check CHECK (
        taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    ),
    CONSTRAINT trading_match_trades_positive_values CHECK (price_ticks > 0 AND quantity_steps > 0)
);

ALTER TABLE trading_match_trades
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

ALTER TABLE trading_match_trades
    ADD COLUMN IF NOT EXISTS taker_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE trading_match_trades
    ADD COLUMN IF NOT EXISTS maker_fee_rate_ppm BIGINT NOT NULL DEFAULT 0;

ALTER TABLE trading_match_trades
    DROP CONSTRAINT IF EXISTS trading_match_trades_fee_rate_check;

ALTER TABLE trading_match_trades
    ADD CONSTRAINT trading_match_trades_fee_rate_check CHECK (
        taker_fee_rate_ppm BETWEEN -1000000 AND 1000000
        AND maker_fee_rate_ppm BETWEEN -1000000 AND 1000000
    );

UPDATE trading_match_trades t
   SET product_line = r.product_line
  FROM trading_match_results r
 WHERE r.command_id = t.command_id;

ALTER TABLE trading_match_trades
    DROP CONSTRAINT IF EXISTS trading_match_trades_product_line_check;

ALTER TABLE trading_match_trades
    ADD CONSTRAINT trading_match_trades_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    );

ALTER TABLE trading_match_trades
    DROP CONSTRAINT IF EXISTS trading_match_trades_pkey;

ALTER TABLE trading_match_trades
    ADD CONSTRAINT trading_match_trades_pkey PRIMARY KEY (product_line, symbol, trade_id);

DROP INDEX IF EXISTS trading_match_trades_symbol_time_idx;
CREATE INDEX IF NOT EXISTS trading_match_trades_symbol_time_idx
    ON trading_match_trades (product_line, symbol, event_time DESC);

DROP INDEX IF EXISTS trading_match_trades_taker_order_idx;
CREATE INDEX IF NOT EXISTS trading_match_trades_taker_order_idx
    ON trading_match_trades (product_line, taker_order_id, event_time DESC);

DROP INDEX IF EXISTS trading_match_trades_maker_order_idx;
CREATE INDEX IF NOT EXISTS trading_match_trades_maker_order_idx
    ON trading_match_trades (product_line, maker_order_id, event_time DESC);

CREATE INDEX IF NOT EXISTS trading_match_trades_trace_idx
    ON trading_match_trades (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE SEQUENCE IF NOT EXISTS account_ledger_entry_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_product_ledger_entry_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_product_transfer_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 128;
CREATE SEQUENCE IF NOT EXISTS account_margin_reservation_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_spot_reservation_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_position_event_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_liquidation_fee_event_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_command_result_event_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_command_retry_event_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;
CREATE SEQUENCE IF NOT EXISTS account_user_command_outbox_event_seq AS BIGINT START WITH 1 INCREMENT BY 1 CACHE 1024;

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

CREATE TABLE IF NOT EXISTS account_product_balances (
    account_type        TEXT NOT NULL,
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    available_units     BIGINT NOT NULL DEFAULT 0,
    locked_units        BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_type, user_id, asset),
    CONSTRAINT account_product_balances_type_check CHECK (
        account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_product_balances_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_balances_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_product_balances_non_negative CHECK (available_units >= 0 AND locked_units >= 0)
);

CREATE INDEX IF NOT EXISTS account_product_balances_user_idx
    ON account_product_balances (user_id, account_type);

CREATE TABLE IF NOT EXISTS account_product_deficits (
    account_type        TEXT NOT NULL,
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    deficit_units       BIGINT NOT NULL DEFAULT 0,
    reserved_units      BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_type, user_id, asset),
    CONSTRAINT account_product_deficits_type_check CHECK (
        account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_product_deficits_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_deficits_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_product_deficits_non_negative CHECK (
        deficit_units >= 0 AND reserved_units >= 0 AND reserved_units <= deficit_units
    )
);

CREATE INDEX IF NOT EXISTS account_product_deficits_user_idx
    ON account_product_deficits (user_id, account_type);

CREATE TABLE IF NOT EXISTS account_product_ledger_entries (
    entry_id            BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    account_type        TEXT NOT NULL,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    symbol              TEXT,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_product_ledger_type_check CHECK (
        account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_product_ledger_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_product_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT account_product_ledger_symbol_format CHECK (
        symbol IS NULL OR symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    )
);

ALTER TABLE account_product_ledger_entries
    ADD COLUMN IF NOT EXISTS symbol TEXT;

ALTER TABLE account_product_ledger_entries
    DROP CONSTRAINT IF EXISTS account_product_ledger_symbol_format;

ALTER TABLE account_product_ledger_entries
    ADD CONSTRAINT account_product_ledger_symbol_format CHECK (
        symbol IS NULL OR symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    );

CREATE UNIQUE INDEX IF NOT EXISTS account_product_ledger_reference_uidx
    ON account_product_ledger_entries (reference_type, reference_id, user_id, account_type, asset);

CREATE INDEX IF NOT EXISTS account_product_ledger_user_time_idx
    ON account_product_ledger_entries (user_id, account_type, created_at DESC);

CREATE TABLE IF NOT EXISTS account_product_transfers (
    transfer_id         BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    source_account_type TEXT NOT NULL,
    target_account_type TEXT NOT NULL,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    reference_id        TEXT NOT NULL,
    status              TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_product_transfers_type_check CHECK (
        source_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        AND target_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                    'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        AND source_account_type <> target_account_type
    ),
    CONSTRAINT account_product_transfers_user_positive CHECK (user_id > 0),
    CONSTRAINT account_product_transfers_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_product_transfers_amount_positive CHECK (amount_units > 0),
    CONSTRAINT account_product_transfers_reference_present CHECK (length(reference_id) > 0),
    CONSTRAINT account_product_transfers_status_check CHECK (status IN ('COMPLETED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS account_product_transfers_reference_uidx
    ON account_product_transfers (user_id, reference_id);

CREATE INDEX IF NOT EXISTS account_product_transfers_user_time_idx
    ON account_product_transfers (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS account_deficits (
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    deficit_units       BIGINT NOT NULL DEFAULT 0,
    reserved_units      BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, asset),
    CONSTRAINT account_deficits_user_positive CHECK (user_id > 0),
    CONSTRAINT account_deficits_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_deficits_non_negative CHECK (
        deficit_units >= 0 AND reserved_units >= 0 AND reserved_units <= deficit_units
    )
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
        reference_type NOT IN ('TRADE_FEE', 'LIQUIDATION_FEE')
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

CREATE INDEX IF NOT EXISTS account_ledger_liquidation_fee_order_idx
    ON account_ledger_entries (order_id, trade_id)
    WHERE reference_type = 'LIQUIDATION_FEE';

CREATE TABLE IF NOT EXISTS account_admin_balance_adjustments (
    adjustment_id       BIGSERIAL PRIMARY KEY,
    reference_key       TEXT NOT NULL,
    adjustment_kind     TEXT NOT NULL,
    admin_user_id       BIGINT NOT NULL,
    admin_username      TEXT,
    user_id             BIGINT NOT NULL,
    account_type        TEXT,
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_admin_adjustments_kind_check CHECK (adjustment_kind IN ('BASIC', 'PRODUCT')),
    CONSTRAINT account_admin_adjustments_type_check CHECK (
        account_type IS NULL OR account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_admin_adjustments_kind_type_check CHECK (
        (adjustment_kind = 'BASIC' AND account_type IS NULL)
        OR (adjustment_kind = 'PRODUCT' AND account_type IS NOT NULL)
    ),
    CONSTRAINT account_admin_adjustments_admin_positive CHECK (admin_user_id > 0),
    CONSTRAINT account_admin_adjustments_user_positive CHECK (user_id > 0),
    CONSTRAINT account_admin_adjustments_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_admin_adjustments_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT account_admin_adjustments_reference_present CHECK (length(reference_id) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS account_admin_adjustments_reference_uidx
    ON account_admin_balance_adjustments (reference_key);

CREATE INDEX IF NOT EXISTS account_admin_adjustments_admin_time_idx
    ON account_admin_balance_adjustments (admin_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS account_admin_adjustments_user_time_idx
    ON account_admin_balance_adjustments (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS account_margin_reservations (
    reservation_id      BIGINT PRIMARY KEY,
    account_type        TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    user_id             BIGINT NOT NULL,
    asset               TEXT NOT NULL,
    order_id            BIGINT NOT NULL UNIQUE,
    symbol              TEXT NOT NULL,
    margin_mode         TEXT NOT NULL DEFAULT 'CROSS',
    position_side       TEXT NOT NULL DEFAULT 'NET',
    order_quantity_steps BIGINT NOT NULL,
    reduce_only         BOOLEAN NOT NULL,
    reserved_units      BIGINT NOT NULL,
    released_units      BIGINT NOT NULL DEFAULT 0,
    position_margin_units BIGINT NOT NULL DEFAULT 0,
    status              TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_margin_reservations_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_margin_reservations_user_positive CHECK (user_id > 0),
    CONSTRAINT account_margin_reservations_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_margin_reservations_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_margin_reservations_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT account_margin_reservations_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT account_margin_reservations_non_negative CHECK (
        order_quantity_steps > 0
        AND reserved_units >= 0
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

DO $$
BEGIN
    IF to_regclass('public.account_product_balances') IS NOT NULL THEN
        ALTER TABLE account_product_balances DROP CONSTRAINT IF EXISTS account_product_balances_type_check;
        ALTER TABLE account_product_balances
            ADD CONSTRAINT account_product_balances_type_check CHECK (
                account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;

    IF to_regclass('public.account_product_deficits') IS NOT NULL THEN
        ALTER TABLE account_product_deficits DROP CONSTRAINT IF EXISTS account_product_deficits_type_check;
        ALTER TABLE account_product_deficits
            ADD CONSTRAINT account_product_deficits_type_check CHECK (
                account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;

    IF to_regclass('public.account_product_ledger_entries') IS NOT NULL THEN
        ALTER TABLE account_product_ledger_entries DROP CONSTRAINT IF EXISTS account_product_ledger_type_check;
        ALTER TABLE account_product_ledger_entries
            ADD CONSTRAINT account_product_ledger_type_check CHECK (
                account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;

    IF to_regclass('public.account_product_transfers') IS NOT NULL THEN
        ALTER TABLE account_product_transfers DROP CONSTRAINT IF EXISTS account_product_transfers_type_check;
        ALTER TABLE account_product_transfers
            ADD CONSTRAINT account_product_transfers_type_check CHECK (
                source_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                        'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
                AND target_account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                            'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
                AND source_account_type <> target_account_type
            );
    END IF;

    IF to_regclass('public.account_admin_balance_adjustments') IS NOT NULL THEN
        ALTER TABLE account_admin_balance_adjustments DROP CONSTRAINT IF EXISTS account_admin_adjustments_type_check;
        ALTER TABLE account_admin_balance_adjustments
            ADD CONSTRAINT account_admin_adjustments_type_check CHECK (
                account_type IS NULL OR account_type IN ('FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;

    IF to_regclass('public.account_margin_reservations') IS NOT NULL THEN
        ALTER TABLE account_margin_reservations DROP CONSTRAINT IF EXISTS account_margin_reservations_type_check;
        ALTER TABLE account_margin_reservations
            ADD CONSTRAINT account_margin_reservations_type_check CHECK (
                account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL',
                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS account_spot_order_reservations (
    reservation_id      BIGINT PRIMARY KEY,
    order_id            BIGINT NOT NULL UNIQUE,
    user_id             BIGINT NOT NULL,
    symbol              TEXT NOT NULL,
    side                TEXT NOT NULL,
    asset               TEXT NOT NULL,
    reserved_units      BIGINT NOT NULL,
    settled_units       BIGINT NOT NULL DEFAULT 0,
    released_units      BIGINT NOT NULL DEFAULT 0,
    status              TEXT NOT NULL,
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_spot_reservations_user_positive CHECK (user_id > 0),
    CONSTRAINT account_spot_reservations_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_spot_reservations_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_spot_reservations_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT account_spot_reservations_non_negative CHECK (
        reserved_units > 0
        AND settled_units >= 0
        AND released_units >= 0
        AND settled_units + released_units <= reserved_units
    ),
    CONSTRAINT account_spot_reservations_status_check CHECK (
        status IN ('ACTIVE', 'PARTIALLY_SETTLED', 'PARTIALLY_RELEASED', 'SETTLED', 'RELEASED')
    ),
    CONSTRAINT account_spot_reservations_order_fk
        FOREIGN KEY (order_id) REFERENCES trading_orders(order_id)
);

CREATE INDEX IF NOT EXISTS account_spot_reservations_user_idx
    ON account_spot_order_reservations (user_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS account_spot_reservations_symbol_idx
    ON account_spot_order_reservations (symbol, status, updated_at DESC);

CREATE SEQUENCE IF NOT EXISTS account_position_cache_revision_seq AS BIGINT START WITH 1;
CREATE SEQUENCE IF NOT EXISTS account_outbox_id_seq AS BIGINT START WITH 1;

CREATE TABLE IF NOT EXISTS account_position_margins (
    product_line        TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id             BIGINT NOT NULL,
    symbol              TEXT NOT NULL,
    asset               TEXT NOT NULL,
    margin_mode         TEXT NOT NULL DEFAULT 'CROSS',
    position_side       TEXT NOT NULL DEFAULT 'NET',
    margin_units        BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    cache_revision      BIGINT NOT NULL DEFAULT nextval('account_position_cache_revision_seq'),
    PRIMARY KEY (product_line, user_id, symbol, asset, margin_mode, position_side),
    CONSTRAINT account_position_margins_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_position_margins_user_positive CHECK (user_id > 0),
    CONSTRAINT account_position_margins_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_position_margins_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT account_position_margins_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT account_position_margins_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT account_position_margins_non_negative CHECK (margin_units >= 0)
);

CREATE INDEX IF NOT EXISTS account_position_margins_user_idx
    ON account_position_margins (product_line, user_id, symbol, margin_mode, position_side);

CREATE TABLE IF NOT EXISTS account_positions (
    product_line            TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id                 BIGINT NOT NULL,
    symbol                  TEXT NOT NULL,
    margin_mode             TEXT NOT NULL DEFAULT 'CROSS',
    position_side           TEXT NOT NULL DEFAULT 'NET',
    instrument_version      BIGINT,
    signed_quantity_steps   BIGINT NOT NULL,
    entry_price_ticks       BIGINT NOT NULL,
    entry_value_ticks       BIGINT NOT NULL DEFAULT 0,
    realized_pnl_units      BIGINT NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    cache_revision          BIGINT NOT NULL DEFAULT nextval('account_position_cache_revision_seq'),
    PRIMARY KEY (product_line, user_id, symbol, margin_mode, position_side),
    CONSTRAINT account_positions_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_positions_user_positive CHECK (user_id > 0),
    CONSTRAINT account_positions_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT account_positions_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT account_positions_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT account_positions_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT account_positions_entry_price_check CHECK (
        (signed_quantity_steps = 0 AND entry_price_ticks = 0 AND entry_value_ticks = 0
            AND (instrument_version IS NULL OR instrument_version > 0))
        OR (signed_quantity_steps <> 0 AND entry_price_ticks > 0 AND entry_value_ticks > 0
            AND instrument_version > 0)
    )
);

CREATE INDEX IF NOT EXISTS account_positions_user_idx
    ON account_positions (product_line, user_id);

CREATE INDEX IF NOT EXISTS account_positions_symbol_idx
    ON account_positions (product_line, symbol, margin_mode, position_side);

CREATE INDEX IF NOT EXISTS account_positions_open_scan_idx
    ON account_positions (product_line, user_id, symbol, margin_mode, position_side)
    WHERE signed_quantity_steps <> 0;

CREATE INDEX IF NOT EXISTS account_positions_funding_scan_idx
    ON account_positions (product_line, symbol, instrument_version, user_id, margin_mode, position_side)
    WHERE signed_quantity_steps <> 0;

DO $$
BEGIN
    IF to_regclass('public.account_positions') IS NOT NULL THEN
        ALTER TABLE account_positions ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
        ALTER TABLE account_positions ADD COLUMN IF NOT EXISTS entry_value_ticks BIGINT NOT NULL DEFAULT 0;
        ALTER TABLE account_positions ADD COLUMN IF NOT EXISTS cache_revision
            BIGINT NOT NULL DEFAULT nextval('account_position_cache_revision_seq');
        UPDATE account_positions
           SET entry_value_ticks = abs(signed_quantity_steps) * entry_price_ticks
         WHERE signed_quantity_steps <> 0
           AND entry_value_ticks = 0;
        ALTER TABLE account_positions DROP CONSTRAINT IF EXISTS account_positions_entry_price_check;
        ALTER TABLE account_positions
            ADD CONSTRAINT account_positions_entry_price_check CHECK (
                (signed_quantity_steps = 0 AND entry_price_ticks = 0 AND entry_value_ticks = 0
                    AND (instrument_version IS NULL OR instrument_version > 0))
                OR (signed_quantity_steps <> 0 AND entry_price_ticks > 0 AND entry_value_ticks > 0
                    AND instrument_version > 0)
            );
        ALTER TABLE account_positions DROP CONSTRAINT IF EXISTS account_positions_product_line_check;
        ALTER TABLE account_positions
            ADD CONSTRAINT account_positions_product_line_check CHECK (
                product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                                 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
            );
        UPDATE account_positions p
           SET product_line = CASE i.contract_type
               WHEN 'SPOT' THEN 'SPOT'
               WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
               WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
               WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
               WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
               WHEN 'VANILLA_OPTION' THEN 'OPTION'
               ELSE p.product_line
           END
          FROM instruments i
         WHERE i.symbol = p.symbol
           AND i.version = p.instrument_version;
        ALTER TABLE account_positions DROP CONSTRAINT IF EXISTS account_positions_pkey;
        ALTER TABLE account_positions
            ADD CONSTRAINT account_positions_pkey PRIMARY KEY (product_line, user_id, symbol, margin_mode, position_side);
    END IF;

    IF to_regclass('public.account_position_margins') IS NOT NULL THEN
        ALTER TABLE account_position_margins ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
        ALTER TABLE account_position_margins ADD COLUMN IF NOT EXISTS cache_revision
            BIGINT NOT NULL DEFAULT nextval('account_position_cache_revision_seq');
        ALTER TABLE account_position_margins DROP CONSTRAINT IF EXISTS account_position_margins_product_line_check;
        ALTER TABLE account_position_margins
            ADD CONSTRAINT account_position_margins_product_line_check CHECK (
                product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                                 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
            );
        UPDATE account_position_margins m
           SET product_line = p.product_line
          FROM account_positions p
         WHERE p.user_id = m.user_id
           AND p.symbol = m.symbol
           AND p.margin_mode = m.margin_mode
           AND p.position_side = m.position_side;
        ALTER TABLE account_position_margins DROP CONSTRAINT IF EXISTS account_position_margins_pkey;
        ALTER TABLE account_position_margins
            ADD CONSTRAINT account_position_margins_pkey
            PRIMARY KEY (product_line, user_id, symbol, asset, margin_mode, position_side);
    END IF;
END $$;

DROP INDEX IF EXISTS account_position_margins_user_idx;
DROP INDEX IF EXISTS account_positions_user_idx;
DROP INDEX IF EXISTS account_positions_symbol_idx;
DROP INDEX IF EXISTS account_positions_open_scan_idx;

CREATE INDEX IF NOT EXISTS account_position_margins_user_idx
    ON account_position_margins (product_line, user_id, symbol, margin_mode, position_side);

CREATE INDEX IF NOT EXISTS account_position_margins_revision_idx
    ON account_position_margins (product_line, cache_revision);

CREATE INDEX IF NOT EXISTS account_positions_user_idx
    ON account_positions (product_line, user_id);

CREATE INDEX IF NOT EXISTS account_positions_revision_idx
    ON account_positions (product_line, cache_revision);

CREATE INDEX IF NOT EXISTS account_positions_symbol_idx
    ON account_positions (product_line, symbol, margin_mode, position_side);

CREATE INDEX IF NOT EXISTS account_positions_open_scan_idx
    ON account_positions (product_line, user_id, symbol, margin_mode, position_side)
    WHERE signed_quantity_steps <> 0;

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

CREATE TABLE IF NOT EXISTS account_command_submissions (
    command_id              VARCHAR(160) PRIMARY KEY,
    product_line            TEXT NOT NULL,
    user_id                 BIGINT NOT NULL,
    command_type            TEXT NOT NULL,
    source                  VARCHAR(64) NOT NULL,
    source_reference        VARCHAR(160) NOT NULL,
    identity_sha256         CHAR(64) NOT NULL,
    payload                 JSONB NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_command_submissions_user_positive CHECK (user_id > 0),
    CONSTRAINT account_command_submissions_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    )
);

CREATE INDEX IF NOT EXISTS account_command_submissions_user_idx
    ON account_command_submissions (product_line, user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS account_trade_settlement_sides (
    product_line            TEXT NOT NULL,
    symbol                  TEXT NOT NULL,
    trade_id                BIGINT NOT NULL,
    participant_role        TEXT NOT NULL,
    taker_user_id           BIGINT NOT NULL,
    maker_user_id           BIGINT NOT NULL,
    command_id              VARCHAR(160) NOT NULL,
    applied_at              TIMESTAMPTZ NOT NULL,
    reconciled_at           TIMESTAMPTZ,
    PRIMARY KEY (product_line, symbol, trade_id, participant_role),
    CONSTRAINT account_trade_settlement_sides_command_uk UNIQUE (product_line, command_id),
    CONSTRAINT account_trade_settlement_sides_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_trade_settlement_sides_users_positive CHECK (
        taker_user_id > 0 AND maker_user_id > 0
    ),
    CONSTRAINT account_trade_settlement_sides_role_check CHECK (participant_role IN ('TAKER', 'MAKER'))
);

CREATE INDEX IF NOT EXISTS account_trade_settlement_sides_monitor_idx
    ON account_trade_settlement_sides (product_line, applied_at, symbol, trade_id)
    WHERE reconciled_at IS NULL;

CREATE OR REPLACE VIEW account_trade_settlement_completions AS
SELECT product_line,
       symbol,
       trade_id,
       MAX(taker_user_id) AS taker_user_id,
       MAX(maker_user_id) AS maker_user_id,
       MIN(applied_at) AS first_applied_at,
       MAX(applied_at) AS completed_at
  FROM account_trade_settlement_sides
 GROUP BY product_line, symbol, trade_id
HAVING COUNT(*) = 2
   AND COUNT(DISTINCT participant_role) = 2;

CREATE TABLE IF NOT EXISTS account_outbox_events (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('account_outbox_id_seq'),
    product_line        TEXT NOT NULL,
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
    CONSTRAINT account_outbox_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT account_outbox_topic_non_empty CHECK (length(topic) > 0),
    CONSTRAINT account_outbox_event_key_non_empty CHECK (length(event_key) > 0)
);

ALTER TABLE account_outbox_events
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

ALTER TABLE account_outbox_events
    ALTER COLUMN id SET DEFAULT nextval('account_outbox_id_seq');

ALTER TABLE account_outbox_events
    DROP CONSTRAINT IF EXISTS account_outbox_product_line_check;

ALTER TABLE account_outbox_events
    ADD CONSTRAINT account_outbox_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    );

DO $$
DECLARE
    v_next_id BIGINT;
BEGIN
    SELECT GREATEST(
        COALESCE((SELECT MAX(id) FROM account_outbox_events), 0),
        (SELECT last_value FROM account_outbox_id_seq)
    ) INTO v_next_id;
    PERFORM setval('account_outbox_id_seq', GREATEST(v_next_id, 1), v_next_id > 0);
END $$;

CREATE INDEX IF NOT EXISTS account_outbox_pending_idx
    ON account_outbox_events (product_line, next_attempt_at, id)
    WHERE published_at IS NULL;

DROP INDEX IF EXISTS account_outbox_pending_key_idx;
CREATE INDEX IF NOT EXISTS account_outbox_pending_stream_idx
    ON account_outbox_events (product_line, topic, event_key, id)
    INCLUDE (next_attempt_at)
    WHERE published_at IS NULL;

DROP INDEX IF EXISTS account_outbox_published_cleanup_idx;
CREATE INDEX IF NOT EXISTS account_outbox_published_line_cleanup_idx
    ON account_outbox_events (product_line, published_at, id)
    WHERE published_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS account_outbox_aggregate_idx
    ON account_outbox_events (aggregate_type, aggregate_id);

CREATE INDEX IF NOT EXISTS account_outbox_events_trace_idx
    ON account_outbox_events ((payload ->> 'traceId'))
    WHERE payload ? 'traceId';

CREATE OR REPLACE FUNCTION account_set_position_cache_revision()
RETURNS TRIGGER AS $$
BEGIN
    NEW.cache_revision := nextval('account_position_cache_revision_seq');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS account_positions_cache_revision_trigger ON account_positions;
CREATE TRIGGER account_positions_cache_revision_trigger
BEFORE UPDATE ON account_positions
FOR EACH ROW EXECUTE FUNCTION account_set_position_cache_revision();

DROP TRIGGER IF EXISTS account_position_margins_cache_revision_trigger ON account_position_margins;
CREATE TRIGGER account_position_margins_cache_revision_trigger
BEFORE UPDATE ON account_position_margins
FOR EACH ROW EXECUTE FUNCTION account_set_position_cache_revision();

-- Position mutations are collected by account-provider and captured once per distinct position key immediately
-- before commit. After commit, the final snapshot is offered directly to the local Redis worker. Position cache
-- snapshots are deliberately not durable business events and must never consume account outbox/Kafka capacity.
DROP TRIGGER IF EXISTS account_positions_cache_outbox_trigger ON account_positions;
DROP TRIGGER IF EXISTS account_position_margins_cache_outbox_trigger ON account_position_margins;
DROP FUNCTION IF EXISTS account_emit_position_cache_from_position();
DROP FUNCTION IF EXISTS account_emit_position_cache_from_margin();
DROP FUNCTION IF EXISTS account_enqueue_position_cache_event(TEXT, BIGINT, TEXT, TEXT, TEXT, BIGINT);
DROP FUNCTION IF EXISTS account_position_cache_topic(TEXT);

DROP TABLE IF EXISTS risk_sequences;

CREATE SEQUENCE IF NOT EXISTS risk_snapshot_id_seq AS BIGINT START WITH 1;
CREATE SEQUENCE IF NOT EXISTS risk_event_id_seq AS BIGINT START WITH 1;
CREATE SEQUENCE IF NOT EXISTS risk_liquidation_candidate_id_seq AS BIGINT START WITH 1;
CREATE SEQUENCE IF NOT EXISTS risk_outbox_id_seq AS BIGINT START WITH 1;

CREATE TABLE IF NOT EXISTS risk_account_snapshots (
    snapshot_id                 BIGINT PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    user_id                     BIGINT NOT NULL,
    account_type                TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
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
    CONSTRAINT risk_account_snapshots_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT risk_account_snapshots_account_type_format CHECK (account_type ~ '^[A-Z0-9_]{2,32}$'),
    CONSTRAINT risk_account_snapshots_asset_format CHECK (settle_asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT risk_account_snapshots_non_negative CHECK (
        maintenance_margin_units >= 0 AND margin_ratio_ppm >= 0
    ),
    CONSTRAINT risk_account_snapshots_status_check CHECK (status IN ('NORMAL', 'WARNING', 'LIQUIDATION'))
);

ALTER TABLE risk_account_snapshots
    ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

UPDATE risk_account_snapshots
   SET product_line = CASE account_type
       WHEN 'SPOT' THEN 'SPOT'
       WHEN 'USDT_PERPETUAL' THEN 'LINEAR_PERPETUAL'
       WHEN 'COIN_PERPETUAL' THEN 'INVERSE_PERPETUAL'
       WHEN 'USDT_DELIVERY' THEN 'LINEAR_DELIVERY'
       WHEN 'COIN_DELIVERY' THEN 'INVERSE_DELIVERY'
       WHEN 'OPTION' THEN 'OPTION'
       ELSE product_line
   END;

DROP INDEX IF EXISTS risk_account_snapshots_query_idx;
DROP INDEX IF EXISTS risk_account_snapshots_account_query_idx;
DROP INDEX IF EXISTS risk_account_snapshots_status_idx;

CREATE INDEX IF NOT EXISTS risk_account_snapshots_query_idx
    ON risk_account_snapshots (product_line, user_id, settle_asset, event_time DESC);

CREATE INDEX IF NOT EXISTS risk_account_snapshots_account_query_idx
    ON risk_account_snapshots (product_line, user_id, account_type, settle_asset, event_time DESC);

CREATE INDEX IF NOT EXISTS risk_account_snapshots_status_idx
    ON risk_account_snapshots (product_line, status, event_time DESC);

CREATE TABLE IF NOT EXISTS risk_position_snapshots (
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    snapshot_id                 BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    symbol                      TEXT NOT NULL,
    margin_mode                 TEXT NOT NULL DEFAULT 'CROSS',
    position_side               TEXT NOT NULL DEFAULT 'NET',
    instrument_version          BIGINT NOT NULL,
    settle_asset                TEXT NOT NULL,
    signed_quantity_steps       BIGINT NOT NULL,
    entry_price_ticks           BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    notional_units              BIGINT NOT NULL,
    unrealized_pnl_units        BIGINT NOT NULL,
    maintenance_margin_units    BIGINT NOT NULL,
    position_margin_units       BIGINT NOT NULL DEFAULT 0,
    margin_ratio_ppm            BIGINT NOT NULL,
    status                      TEXT NOT NULL,
    event_time                  TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_line, snapshot_id, user_id, symbol, margin_mode, position_side),
    CONSTRAINT risk_position_snapshots_snapshot_fk
        FOREIGN KEY (snapshot_id) REFERENCES risk_account_snapshots(snapshot_id),
    CONSTRAINT risk_position_snapshots_instrument_fk
        FOREIGN KEY (symbol, instrument_version) REFERENCES instruments(symbol, version),
    CONSTRAINT risk_position_snapshots_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT risk_position_snapshots_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT risk_position_snapshots_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT risk_position_snapshots_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT risk_position_snapshots_price_check CHECK (
        (signed_quantity_steps = 0 AND entry_price_ticks = 0 AND mark_price_ticks = 0)
        OR (signed_quantity_steps <> 0 AND entry_price_ticks > 0 AND mark_price_ticks > 0)
    ),
    CONSTRAINT risk_position_snapshots_non_negative CHECK (
        notional_units >= 0 AND maintenance_margin_units >= 0 AND position_margin_units >= 0
        AND margin_ratio_ppm >= 0
    ),
    CONSTRAINT risk_position_snapshots_status_check CHECK (status IN ('NORMAL', 'WARNING', 'LIQUIDATION'))
);

ALTER TABLE risk_position_snapshots
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

UPDATE risk_position_snapshots s
   SET product_line = CASE i.contract_type
       WHEN 'SPOT' THEN 'SPOT'
       WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
       WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
       WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
       WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
       WHEN 'VANILLA_OPTION' THEN 'OPTION'
       ELSE s.product_line
   END
  FROM instruments i
 WHERE i.symbol = s.symbol
   AND i.version = s.instrument_version;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = 'risk_position_snapshots'::regclass
           AND conname = 'risk_position_snapshots_pkey'
    ) THEN
        ALTER TABLE risk_position_snapshots DROP CONSTRAINT risk_position_snapshots_pkey;
    END IF;
    ALTER TABLE risk_position_snapshots
        ADD CONSTRAINT risk_position_snapshots_pkey
        PRIMARY KEY (product_line, snapshot_id, user_id, symbol, margin_mode, position_side);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DROP INDEX IF EXISTS risk_position_snapshots_user_idx;
DROP INDEX IF EXISTS risk_position_snapshots_symbol_idx;

CREATE INDEX IF NOT EXISTS risk_position_snapshots_user_idx
    ON risk_position_snapshots (product_line, user_id, event_time DESC);

CREATE INDEX IF NOT EXISTS risk_position_snapshots_symbol_idx
    ON risk_position_snapshots (product_line, symbol, margin_mode, position_side, event_time DESC);

CREATE TABLE IF NOT EXISTS risk_liquidation_candidates (
    candidate_id                BIGINT PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    snapshot_id                 BIGINT NOT NULL,
    user_id                     BIGINT NOT NULL,
    symbol                      TEXT NOT NULL,
    margin_mode                 TEXT NOT NULL DEFAULT 'CROSS',
    position_side               TEXT NOT NULL DEFAULT 'NET',
    instrument_version          BIGINT NOT NULL,
    account_type                TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
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
    CONSTRAINT risk_liquidation_candidates_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT risk_liquidation_candidates_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT risk_liquidation_candidates_account_type_format CHECK (account_type ~ '^[A-Z0-9_]{2,32}$'),
    CONSTRAINT risk_liquidation_candidates_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT risk_liquidation_candidates_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT risk_liquidation_candidates_status_check CHECK (status IN ('NEW', 'PROCESSING', 'COMPLETED', 'CANCELED'))
);

ALTER TABLE risk_liquidation_candidates
    ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';

UPDATE risk_liquidation_candidates
   SET product_line = CASE account_type
       WHEN 'SPOT' THEN 'SPOT'
       WHEN 'USDT_PERPETUAL' THEN 'LINEAR_PERPETUAL'
       WHEN 'COIN_PERPETUAL' THEN 'INVERSE_PERPETUAL'
       WHEN 'USDT_DELIVERY' THEN 'LINEAR_DELIVERY'
       WHEN 'COIN_DELIVERY' THEN 'INVERSE_DELIVERY'
       WHEN 'OPTION' THEN 'OPTION'
       ELSE product_line
   END;

DROP INDEX IF EXISTS risk_liquidation_candidates_snapshot_uidx;
DROP INDEX IF EXISTS risk_liquidation_candidates_active_uidx;
DROP INDEX IF EXISTS risk_liquidation_candidates_status_idx;

CREATE UNIQUE INDEX IF NOT EXISTS risk_liquidation_candidates_snapshot_uidx
    ON risk_liquidation_candidates (product_line, snapshot_id, user_id, symbol, margin_mode, position_side);

CREATE UNIQUE INDEX IF NOT EXISTS risk_liquidation_candidates_active_uidx
    ON risk_liquidation_candidates (product_line, user_id, symbol, margin_mode, position_side)
    WHERE status IN ('NEW', 'PROCESSING');

CREATE INDEX IF NOT EXISTS risk_liquidation_candidates_status_idx
    ON risk_liquidation_candidates (product_line, status, event_time ASC);

CREATE TABLE IF NOT EXISTS risk_admin_rule_overrides (
    rule_code                    TEXT PRIMARY KEY,
    rule_name                    TEXT NOT NULL,
    rule_type                    TEXT NOT NULL,
    enabled                      BOOLEAN NOT NULL DEFAULT TRUE,
    warning_margin_ratio_ppm     BIGINT,
    liquidation_margin_ratio_ppm BIGINT,
    scan_delay_ms                BIGINT,
    scan_batch_size              INTEGER,
    admin_user_id                TEXT NOT NULL,
    reason                       TEXT NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT risk_admin_rule_overrides_code_check CHECK (rule_code ~ '^[A-Z0-9_.:-]{2,96}$'),
    CONSTRAINT risk_admin_rule_overrides_type_check CHECK (
        rule_type IN ('GLOBAL_MARGIN', 'SCAN_CONTROL')
    ),
    CONSTRAINT risk_admin_rule_overrides_margin_check CHECK (
        warning_margin_ratio_ppm IS NULL OR warning_margin_ratio_ppm >= 0
    ),
    CONSTRAINT risk_admin_rule_overrides_liquidation_check CHECK (
        liquidation_margin_ratio_ppm IS NULL OR liquidation_margin_ratio_ppm >= 0
    ),
    CONSTRAINT risk_admin_rule_overrides_margin_order_check CHECK (
        warning_margin_ratio_ppm IS NULL
        OR liquidation_margin_ratio_ppm IS NULL
        OR warning_margin_ratio_ppm < liquidation_margin_ratio_ppm
    ),
    CONSTRAINT risk_admin_rule_overrides_scan_delay_check CHECK (
        scan_delay_ms IS NULL OR scan_delay_ms >= 0
    ),
    CONSTRAINT risk_admin_rule_overrides_batch_check CHECK (
        scan_batch_size IS NULL OR scan_batch_size BETWEEN 1 AND 10000
    ),
    CONSTRAINT risk_admin_rule_overrides_admin_present CHECK (length(admin_user_id) > 0),
    CONSTRAINT risk_admin_rule_overrides_reason_present CHECK (length(reason) BETWEEN 1 AND 500)
);

CREATE INDEX IF NOT EXISTS risk_admin_rule_overrides_type_idx
    ON risk_admin_rule_overrides (rule_type, updated_at DESC);

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

CREATE INDEX IF NOT EXISTS risk_outbox_pending_key_idx
    ON risk_outbox_events (topic, event_key, id)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS risk_outbox_published_cleanup_idx
    ON risk_outbox_events (published_at, id)
    WHERE published_at IS NOT NULL;

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
    margin_mode             TEXT NOT NULL DEFAULT 'CROSS',
    position_side           TEXT NOT NULL DEFAULT 'NET',
    side                    TEXT NOT NULL,
    quantity_steps          BIGINT NOT NULL,
    status                  TEXT NOT NULL,
    reason                  TEXT,
    bankruptcy_price_ticks  BIGINT NOT NULL DEFAULT 0,
    takeover_price_ticks    BIGINT NOT NULL DEFAULT 0,
    liquidation_fee_rate_ppm BIGINT NOT NULL DEFAULT 0,
    liquidation_fee_units   BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT liquidation_orders_candidate_fk
        FOREIGN KEY (candidate_id) REFERENCES risk_liquidation_candidates(candidate_id),
    CONSTRAINT liquidation_orders_user_positive CHECK (user_id > 0),
    CONSTRAINT liquidation_orders_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT liquidation_orders_margin_mode_check CHECK (margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT liquidation_orders_position_side_check CHECK (position_side IN ('NET', 'LONG', 'SHORT')),
    CONSTRAINT liquidation_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT liquidation_orders_quantity_check CHECK (
        quantity_steps >= 0
        AND (status <> 'SUBMITTED' OR quantity_steps > 0)
    ),
    CONSTRAINT liquidation_orders_status_check CHECK (
        status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED', 'FAILED')
    ),
    CONSTRAINT liquidation_orders_audit_non_negative CHECK (
        bankruptcy_price_ticks >= 0
        AND takeover_price_ticks >= 0
        AND liquidation_fee_rate_ppm >= 0
        AND liquidation_fee_units >= 0
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS liquidation_orders_candidate_uidx
    ON liquidation_orders (candidate_id);

CREATE INDEX IF NOT EXISTS liquidation_orders_user_time_idx
    ON liquidation_orders (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS liquidation_orders_symbol_time_idx
    ON liquidation_orders (symbol, created_at DESC);

CREATE TABLE IF NOT EXISTS liquidation_admin_actions (
    action_id            BIGSERIAL PRIMARY KEY,
    candidate_id         BIGINT NOT NULL REFERENCES risk_liquidation_candidates(candidate_id),
    action_type          TEXT NOT NULL,
    admin_user_id        TEXT NOT NULL,
    reason               TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT liquidation_admin_actions_type_check CHECK (
        action_type IN ('CANCEL_CANDIDATE')
    ),
    CONSTRAINT liquidation_admin_actions_admin_present CHECK (length(admin_user_id) > 0),
    CONSTRAINT liquidation_admin_actions_reason_present CHECK (length(reason) BETWEEN 1 AND 500)
);

CREATE INDEX IF NOT EXISTS liquidation_admin_actions_candidate_time_idx
    ON liquidation_admin_actions (candidate_id, created_at DESC);

CREATE TABLE IF NOT EXISTS insurance_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS insurance_fund_balances (
    account_type        TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    asset               TEXT NOT NULL,
    balance_units       BIGINT NOT NULL DEFAULT 0,
    reserved_units      BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_type, asset),
    CONSTRAINT insurance_fund_balances_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT insurance_fund_balances_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_fund_balances_non_negative CHECK (
        balance_units >= 0 AND reserved_units >= 0 AND reserved_units <= balance_units
    )
);

CREATE TABLE IF NOT EXISTS insurance_fund_ledger (
    entry_id            BIGINT PRIMARY KEY,
    account_type        TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    asset               TEXT NOT NULL,
    amount_units        BIGINT NOT NULL,
    balance_after_units BIGINT NOT NULL,
    reference_type      TEXT NOT NULL,
    reference_id        TEXT NOT NULL,
    reason              TEXT,
    symbol              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_fund_ledger_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT insurance_fund_ledger_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_fund_ledger_amount_non_zero CHECK (amount_units <> 0),
    CONSTRAINT insurance_fund_ledger_balance_non_negative CHECK (balance_after_units >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_fund_ledger_reference_uidx
    ON insurance_fund_ledger (reference_type, reference_id, account_type, asset);

CREATE INDEX IF NOT EXISTS insurance_fund_ledger_asset_time_idx
    ON insurance_fund_ledger (account_type, asset, created_at DESC);

CREATE TABLE IF NOT EXISTS insurance_deficit_coverages (
    coverage_id                 BIGINT PRIMARY KEY,
    account_type                TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    user_id                     BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    requested_units             BIGINT NOT NULL,
    covered_units               BIGINT NOT NULL,
    remaining_deficit_units     BIGINT NOT NULL,
    reserve_command_id          VARCHAR(160) NOT NULL,
    finalize_command_id         VARCHAR(160) NOT NULL,
    status                      TEXT NOT NULL,
    reason                      TEXT,
    error_code                  VARCHAR(80),
    error_message               VARCHAR(1000),
    completed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT insurance_coverages_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT insurance_coverages_user_positive CHECK (user_id > 0),
    CONSTRAINT insurance_coverages_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT insurance_coverages_non_negative CHECK (
        requested_units > 0
        AND covered_units > 0
        AND remaining_deficit_units >= 0
        AND covered_units <= requested_units
    ),
    CONSTRAINT insurance_coverages_status_check CHECK (
        status IN ('PENDING_RESERVE', 'PENDING_FINALIZE', 'COVERED', 'PARTIALLY_COVERED', 'FAILED')
    ),
    CONSTRAINT insurance_coverages_completion_check CHECK (
        (status IN ('COVERED', 'PARTIALLY_COVERED', 'FAILED') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING_RESERVE', 'PENDING_FINALIZE') AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_coverages_reserve_command_uidx
    ON insurance_deficit_coverages (reserve_command_id);

CREATE UNIQUE INDEX IF NOT EXISTS insurance_coverages_finalize_command_uidx
    ON insurance_deficit_coverages (finalize_command_id);

CREATE INDEX IF NOT EXISTS insurance_coverages_user_time_idx
    ON insurance_deficit_coverages (account_type, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS insurance_coverages_asset_time_idx
    ON insurance_deficit_coverages (account_type, asset, created_at DESC);

DO $$
BEGIN
    ALTER TABLE insurance_fund_balances
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE insurance_fund_balances
        DROP CONSTRAINT IF EXISTS insurance_fund_balances_pkey;
    ALTER TABLE insurance_fund_balances
        ADD CONSTRAINT insurance_fund_balances_pkey PRIMARY KEY (account_type, asset);
    ALTER TABLE insurance_fund_balances
        DROP CONSTRAINT IF EXISTS insurance_fund_balances_type_check;
    ALTER TABLE insurance_fund_balances
        ADD CONSTRAINT insurance_fund_balances_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );

    ALTER TABLE insurance_fund_ledger
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE insurance_fund_ledger
        DROP CONSTRAINT IF EXISTS insurance_fund_ledger_type_check;
    ALTER TABLE insurance_fund_ledger
        ADD CONSTRAINT insurance_fund_ledger_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );

    ALTER TABLE insurance_deficit_coverages
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE insurance_deficit_coverages
        DROP CONSTRAINT IF EXISTS insurance_coverages_type_check;
    ALTER TABLE insurance_deficit_coverages
        ADD CONSTRAINT insurance_coverages_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS insurance_fund_ledger_reference_uidx;
CREATE UNIQUE INDEX IF NOT EXISTS insurance_fund_ledger_reference_uidx
    ON insurance_fund_ledger (reference_type, reference_id, account_type, asset);

DROP INDEX IF EXISTS insurance_fund_ledger_asset_time_idx;
CREATE INDEX IF NOT EXISTS insurance_fund_ledger_asset_time_idx
    ON insurance_fund_ledger (account_type, asset, created_at DESC);

DROP INDEX IF EXISTS insurance_coverages_user_time_idx;
CREATE INDEX IF NOT EXISTS insurance_coverages_user_time_idx
    ON insurance_deficit_coverages (account_type, user_id, created_at DESC);

DROP INDEX IF EXISTS insurance_coverages_asset_time_idx;
CREATE INDEX IF NOT EXISTS insurance_coverages_asset_time_idx
    ON insurance_deficit_coverages (account_type, asset, created_at DESC);

CREATE TABLE IF NOT EXISTS adl_sequences (
    sequence_name       TEXT PRIMARY KEY,
    sequence_value      BIGINT NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT adl_sequences_positive CHECK (sequence_value > 0)
);

CREATE TABLE IF NOT EXISTS adl_events (
    event_id                    BIGINT PRIMARY KEY,
    account_type                TEXT NOT NULL DEFAULT 'USDT_PERPETUAL',
    deficit_user_id             BIGINT NOT NULL,
    target_user_id              BIGINT NOT NULL,
    asset                       TEXT NOT NULL,
    symbol                      TEXT NOT NULL,
    target_side                 TEXT NOT NULL,
    target_position_side        TEXT NOT NULL DEFAULT 'NET',
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
    CONSTRAINT adl_events_type_check CHECK (
        account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT adl_events_users_positive CHECK (deficit_user_id > 0 AND target_user_id > 0),
    CONSTRAINT adl_events_distinct_users CHECK (deficit_user_id <> target_user_id),
    CONSTRAINT adl_events_asset_format CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT adl_events_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT adl_events_side_check CHECK (target_side IN ('LONG', 'SHORT')),
    CONSTRAINT adl_events_position_side_check CHECK (target_position_side IN ('NET', 'LONG', 'SHORT')),
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
    entry_price_ticks           BIGINT NOT NULL,
    mark_price_ticks            BIGINT NOT NULL,
    requested_deficit_units     BIGINT NOT NULL,
    realized_profit_units       BIGINT NOT NULL,
    covered_units               BIGINT NOT NULL,
    priority_score_ppm          BIGINT NOT NULL,
    reserve_command_id          VARCHAR(160) NOT NULL UNIQUE,
    target_command_id           VARCHAR(160) NOT NULL UNIQUE,
    finalize_command_id         VARCHAR(160) NOT NULL UNIQUE,
    release_command_id          VARCHAR(160) UNIQUE,
    status                      TEXT NOT NULL,
    error_code                  VARCHAR(80),
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

CREATE INDEX IF NOT EXISTS adl_events_deficit_user_time_idx
    ON adl_events (account_type, deficit_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS adl_events_target_user_time_idx
    ON adl_events (account_type, target_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS adl_events_asset_symbol_time_idx
    ON adl_events (account_type, asset, symbol, created_at DESC);

DO $$
BEGIN
    ALTER TABLE adl_events
        ADD COLUMN IF NOT EXISTS account_type TEXT NOT NULL DEFAULT 'USDT_PERPETUAL';
    ALTER TABLE adl_events
        DROP CONSTRAINT IF EXISTS adl_events_type_check;
    ALTER TABLE adl_events
        ADD CONSTRAINT adl_events_type_check CHECK (
            account_type IN ('USDT_PERPETUAL', 'COIN_PERPETUAL', 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS adl_events_deficit_user_time_idx;
CREATE INDEX IF NOT EXISTS adl_events_deficit_user_time_idx
    ON adl_events (account_type, deficit_user_id, created_at DESC);

DROP INDEX IF EXISTS adl_events_target_user_time_idx;
CREATE INDEX IF NOT EXISTS adl_events_target_user_time_idx
    ON adl_events (account_type, target_user_id, created_at DESC);

DROP INDEX IF EXISTS adl_events_asset_symbol_time_idx;
CREATE INDEX IF NOT EXISTS adl_events_asset_symbol_time_idx
    ON adl_events (account_type, asset, symbol, created_at DESC);

CREATE TABLE IF NOT EXISTS market_maker_strategy_leases (
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    symbol                      TEXT NOT NULL,
    owner_id                    TEXT NOT NULL,
    lease_until                 TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_line, strategy_id, symbol),
    CONSTRAINT market_maker_leases_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_leases_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_leases_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT market_maker_leases_owner_present CHECK (length(owner_id) > 0)
);

DROP INDEX IF EXISTS market_maker_strategy_leases_until_idx;
CREATE INDEX IF NOT EXISTS market_maker_strategy_leases_until_idx
    ON market_maker_strategy_leases (product_line, lease_until ASC);

CREATE TABLE IF NOT EXISTS market_maker_strategy_overrides (
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    enabled                     BOOLEAN,
    base_quantity_steps         BIGINT,
    margin_mode                 TEXT,
    spread_ticks                BIGINT,
    level_spacing_ticks         BIGINT,
    max_inventory_steps         BIGINT,
    max_inventory_skew_ppm      BIGINT,
    order_levels                INTEGER,
    updated_by_admin_user_id    TEXT NOT NULL,
    reason                      TEXT NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (product_line, strategy_id),
    CONSTRAINT market_maker_overrides_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_overrides_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_overrides_margin_mode CHECK (margin_mode IS NULL OR margin_mode IN ('CROSS', 'ISOLATED')),
    CONSTRAINT market_maker_overrides_base_qty CHECK (base_quantity_steps IS NULL OR base_quantity_steps > 0),
    CONSTRAINT market_maker_overrides_spread CHECK (spread_ticks IS NULL OR spread_ticks >= 0),
    CONSTRAINT market_maker_overrides_level_spacing CHECK (level_spacing_ticks IS NULL OR level_spacing_ticks >= 0),
    CONSTRAINT market_maker_overrides_inventory CHECK (max_inventory_steps IS NULL OR max_inventory_steps > 0),
    CONSTRAINT market_maker_overrides_inventory_skew CHECK (
        max_inventory_skew_ppm IS NULL OR max_inventory_skew_ppm BETWEEN 0 AND 1000000
    ),
    CONSTRAINT market_maker_overrides_order_levels CHECK (order_levels IS NULL OR order_levels BETWEEN 1 AND 20),
    CONSTRAINT market_maker_overrides_admin_present CHECK (length(updated_by_admin_user_id) > 0),
    CONSTRAINT market_maker_overrides_reason_present CHECK (length(reason) BETWEEN 1 AND 500),
    CONSTRAINT market_maker_overrides_version_positive CHECK (version > 0)
);

DROP INDEX IF EXISTS market_maker_strategy_overrides_updated_idx;
CREATE INDEX IF NOT EXISTS market_maker_strategy_overrides_updated_idx
    ON market_maker_strategy_overrides (product_line, updated_at DESC);

CREATE TABLE IF NOT EXISTS market_maker_strategy_run_events (
    event_id                    BIGSERIAL PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    symbol                      TEXT,
    account_id                  BIGINT,
    node_id                     TEXT NOT NULL,
    cycle_sequence              BIGINT NOT NULL DEFAULT 0,
    event_type                  TEXT NOT NULL,
    submitted_orders            BIGINT NOT NULL DEFAULT 0,
    canceled_orders             BIGINT NOT NULL DEFAULT 0,
    rejected_orders             BIGINT NOT NULL DEFAULT 0,
    skipped_reason              TEXT,
    error_message               TEXT,
    trace_id                    TEXT,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT market_maker_run_events_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_run_events_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_run_events_symbol_format CHECK (
        symbol IS NULL OR symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'
    ),
    CONSTRAINT market_maker_run_events_account_positive CHECK (account_id IS NULL OR account_id > 0),
    CONSTRAINT market_maker_run_events_node_present CHECK (length(node_id) > 0),
    CONSTRAINT market_maker_run_events_cycle_non_negative CHECK (cycle_sequence >= 0),
    CONSTRAINT market_maker_run_events_type_check CHECK (
        event_type IN ('CYCLE_SUCCESS', 'CYCLE_FAILED', 'QUOTE_RECONCILED', 'TRADE_SUBMITTED', 'TRADE_REJECTED', 'SKIPPED')
    ),
    CONSTRAINT market_maker_run_events_counts_non_negative CHECK (
        submitted_orders >= 0 AND canceled_orders >= 0 AND rejected_orders >= 0
    )
);

DROP INDEX IF EXISTS market_maker_run_events_strategy_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_strategy_time_idx
    ON market_maker_strategy_run_events (product_line, strategy_id, created_at DESC);

DROP INDEX IF EXISTS market_maker_run_events_symbol_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_symbol_time_idx
    ON market_maker_strategy_run_events (product_line, symbol, created_at DESC)
    WHERE symbol IS NOT NULL;

DROP INDEX IF EXISTS market_maker_run_events_account_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_account_time_idx
    ON market_maker_strategy_run_events (product_line, account_id, created_at DESC)
    WHERE account_id IS NOT NULL;

DROP INDEX IF EXISTS market_maker_run_events_trace_idx;
CREATE INDEX IF NOT EXISTS market_maker_run_events_trace_idx
    ON market_maker_strategy_run_events (product_line, trace_id)
    WHERE trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS market_maker_reference_samples (
    sample_id                   BIGSERIAL PRIMARY KEY,
    product_line                TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL',
    strategy_id                 TEXT NOT NULL,
    symbol                      TEXT NOT NULL,
    node_id                     TEXT NOT NULL,
    cycle_sequence              BIGINT NOT NULL DEFAULT 0,
    source_name                 TEXT NOT NULL,
    transport                   TEXT NOT NULL,
    bid_levels                  INTEGER NOT NULL,
    ask_levels                  INTEGER NOT NULL,
    best_bid_ticks              BIGINT NOT NULL,
    best_ask_ticks              BIGINT NOT NULL,
    mid_price_ticks             BIGINT NOT NULL,
    spread_ticks                BIGINT NOT NULL,
    received_at                 TIMESTAMPTZ NOT NULL,
    trace_id                    TEXT,
    sampled_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT market_maker_reference_samples_product_line_check CHECK (
        product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                         'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
    ),
    CONSTRAINT market_maker_reference_samples_strategy_format CHECK (strategy_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT market_maker_reference_samples_symbol_format CHECK (symbol ~ '^[A-Z0-9][A-Z0-9_-]{1,63}$'),
    CONSTRAINT market_maker_reference_samples_node_present CHECK (length(node_id) > 0),
    CONSTRAINT market_maker_reference_samples_cycle_non_negative CHECK (cycle_sequence >= 0),
    CONSTRAINT market_maker_reference_samples_source_present CHECK (length(source_name) > 0),
    CONSTRAINT market_maker_reference_samples_transport_check CHECK (transport IN ('REST', 'WEBSOCKET', 'UNKNOWN')),
    CONSTRAINT market_maker_reference_samples_depth_positive CHECK (bid_levels > 0 AND ask_levels > 0),
    CONSTRAINT market_maker_reference_samples_prices_valid CHECK (
        best_bid_ticks > 0 AND best_ask_ticks > best_bid_ticks
        AND mid_price_ticks > 0 AND spread_ticks > 0
    )
);

DO $$
BEGIN
    ALTER TABLE market_maker_strategy_leases
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_strategy_leases
        DROP CONSTRAINT IF EXISTS market_maker_strategy_leases_pkey;
    ALTER TABLE market_maker_strategy_leases
        ADD CONSTRAINT market_maker_strategy_leases_pkey PRIMARY KEY (product_line, strategy_id, symbol);
    ALTER TABLE market_maker_strategy_leases
        DROP CONSTRAINT IF EXISTS market_maker_leases_product_line_check;
    ALTER TABLE market_maker_strategy_leases
        ADD CONSTRAINT market_maker_leases_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );

    ALTER TABLE market_maker_strategy_overrides
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_strategy_overrides
        DROP CONSTRAINT IF EXISTS market_maker_strategy_overrides_pkey;
    ALTER TABLE market_maker_strategy_overrides
        ADD CONSTRAINT market_maker_strategy_overrides_pkey PRIMARY KEY (product_line, strategy_id);
    ALTER TABLE market_maker_strategy_overrides
        DROP CONSTRAINT IF EXISTS market_maker_overrides_product_line_check;
    ALTER TABLE market_maker_strategy_overrides
        ADD CONSTRAINT market_maker_overrides_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );

    ALTER TABLE market_maker_strategy_run_events
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_strategy_run_events
        DROP CONSTRAINT IF EXISTS market_maker_run_events_product_line_check;
    ALTER TABLE market_maker_strategy_run_events
        ADD CONSTRAINT market_maker_run_events_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );

    ALTER TABLE market_maker_reference_samples
        ADD COLUMN IF NOT EXISTS product_line TEXT NOT NULL DEFAULT 'LINEAR_PERPETUAL';
    ALTER TABLE market_maker_reference_samples
        DROP CONSTRAINT IF EXISTS market_maker_reference_samples_product_line_check;
    ALTER TABLE market_maker_reference_samples
        ADD CONSTRAINT market_maker_reference_samples_product_line_check CHECK (
            product_line IN ('SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL',
                             'LINEAR_DELIVERY', 'INVERSE_DELIVERY', 'OPTION')
        );
END $$;

DROP INDEX IF EXISTS market_maker_reference_samples_strategy_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_reference_samples_strategy_time_idx
    ON market_maker_reference_samples (product_line, strategy_id, sampled_at DESC);

DROP INDEX IF EXISTS market_maker_reference_samples_symbol_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_reference_samples_symbol_time_idx
    ON market_maker_reference_samples (product_line, symbol, sampled_at DESC);

DROP INDEX IF EXISTS market_maker_reference_samples_transport_time_idx;
CREATE INDEX IF NOT EXISTS market_maker_reference_samples_transport_time_idx
    ON market_maker_reference_samples (product_line, transport, sampled_at DESC);

CREATE TABLE IF NOT EXISTS gateway_users (
    user_id             BIGSERIAL PRIMARY KEY,
    username            TEXT NOT NULL,
    email               TEXT,
    password_hash       TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'NORMAL',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_users_username_format CHECK (username ~ '^[a-z0-9_]{3,32}$'),
    CONSTRAINT gateway_users_email_length CHECK (email IS NULL OR length(email) <= 254),
    CONSTRAINT gateway_users_status_check CHECK (
        status IN ('NORMAL', 'FROZEN', 'TRADE_DISABLED', 'WITHDRAW_DISABLED')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_users_username_uidx
    ON gateway_users (lower(username));

CREATE UNIQUE INDEX IF NOT EXISTS gateway_users_email_uidx
    ON gateway_users (lower(email))
    WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_roles (
    role_id             BIGSERIAL PRIMARY KEY,
    role_code           TEXT NOT NULL,
    role_name           TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_roles_code_format CHECK (role_code ~ '^[A-Z0-9_]{2,64}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_roles_code_uidx
    ON gateway_roles (role_code);

INSERT INTO gateway_roles (role_code, role_name)
VALUES
    ('USER', 'Standard user'),
    ('SUPPORT', 'Customer support read-only operator'),
    ('ADMIN', 'Admin operator'),
    ('SUPER_ADMIN', 'Super administrator')
ON CONFLICT (role_code) DO NOTHING;

CREATE TABLE IF NOT EXISTS gateway_user_roles (
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    role_id             BIGINT NOT NULL REFERENCES gateway_roles(role_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX IF NOT EXISTS gateway_user_roles_role_idx
    ON gateway_user_roles (role_id);

CREATE TABLE IF NOT EXISTS gateway_permissions (
    permission_id       BIGSERIAL PRIMARY KEY,
    permission_code     TEXT NOT NULL,
    permission_name     TEXT NOT NULL,
    description         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_permissions_code_format CHECK (permission_code ~ '^[a-z0-9*][a-z0-9.*_-]{1,127}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_permissions_code_uidx
    ON gateway_permissions (permission_code);

INSERT INTO gateway_permissions (permission_code, permission_name, description)
VALUES
    ('admin.*', 'All admin permissions', 'Full access to every local and proxied admin operation.'),
    ('admin.users.read', 'Read users', 'View users, sessions, login logs and user profile aggregates.'),
    ('admin.users.write', 'Write users', 'Change user status, roles and sessions.'),
    ('admin.audit.read', 'Read audit logs', 'View admin operation logs and login audit logs.'),
    ('admin.approvals.read', 'Read approvals', 'View admin approval requests.'),
    ('admin.approvals.write', 'Write approvals', 'Create, approve, reject and consume admin approvals.'),
    ('admin.system.read', 'Read system state', 'View configured routes and backend health.'),
    ('admin.traces.read', 'Read TraceId timelines', 'View cross-module TraceId timelines for incident investigation.'),
    ('admin.market.read', 'Read market health', 'View index source, mark price and candlestick freshness metrics.'),
    ('admin.alerts.read', 'Read alerts', 'View admin alert rules and alert events.'),
    ('admin.alerts.write', 'Write alerts', 'Create, update, evaluate and acknowledge admin alerts.'),
    ('admin.trading.read', 'Read trading operations', 'View trading operation metrics and aggregated order flow.'),
    ('admin.reports.read', 'Read admin reports', 'View account asset valuation and back-office reports.'),
    ('admin.reports.write', 'Write admin reports', 'Generate account asset report snapshots.'),
    ('admin.security.mfa', 'Manage own MFA', 'Enroll, confirm and disable own admin TOTP MFA.'),
    ('admin.support.read', 'Read support console', 'View read-only customer support user overviews.'),
    ('admin.support.write', 'Write support tickets', 'Create and update customer support tickets and internal notes.'),
    ('admin.compliance.read', 'Read compliance', 'View KYC, AML cases and risk tags.'),
    ('admin.compliance.write', 'Write compliance', 'Update KYC, AML cases and risk tags.'),
    ('admin.exports.read', 'Read exports', 'View and download admin export jobs.'),
    ('admin.exports.write', 'Write exports', 'Create admin export jobs.'),
    ('admin.queries.read', 'Read query tasks', 'View long-running admin query tasks and results.'),
    ('admin.queries.write', 'Write query tasks', 'Create controlled long-running admin query tasks.'),
    ('admin.permissions.read', 'Read permissions', 'View roles, permission catalog and role assignments.'),
    ('admin.permissions.write', 'Write permissions', 'Replace role permission assignments.'),
    ('admin.gateway.*.read', 'Read admin gateway services', 'Read through any configured admin gateway service.'),
    ('admin.gateway.*.write', 'Write admin gateway services', 'Write through any configured admin gateway service.')
ON CONFLICT (permission_code) DO UPDATE
   SET permission_name = EXCLUDED.permission_name,
       description = EXCLUDED.description;

CREATE TABLE IF NOT EXISTS gateway_role_permissions (
    role_id             BIGINT NOT NULL REFERENCES gateway_roles(role_id),
    permission_id       BIGINT NOT NULL REFERENCES gateway_permissions(permission_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX IF NOT EXISTS gateway_role_permissions_permission_idx
    ON gateway_role_permissions (permission_id);

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code = 'admin.*'
 WHERE r.role_code = 'SUPER_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code IN (
      'admin.support.read',
      'admin.users.read',
      'admin.users.write',
      'admin.audit.read',
      'admin.approvals.read',
      'admin.approvals.write',
      'admin.system.read',
      'admin.traces.read',
      'admin.market.read',
      'admin.alerts.read',
      'admin.alerts.write',
      'admin.trading.read',
      'admin.reports.read',
      'admin.reports.write',
      'admin.security.mfa',
      'admin.compliance.read',
      'admin.compliance.write',
      'admin.exports.read',
      'admin.exports.write',
      'admin.queries.read',
      'admin.queries.write',
      'admin.permissions.read',
      'admin.support.write',
      'admin.gateway.*.read',
      'admin.gateway.*.write'
  )
 WHERE r.role_code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO gateway_role_permissions (role_id, permission_id)
SELECT r.role_id, p.permission_id
  FROM gateway_roles r
  JOIN gateway_permissions p ON p.permission_code IN (
      'admin.support.read',
      'admin.support.write',
      'admin.security.mfa'
  )
 WHERE r.role_code = 'SUPPORT'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS gateway_user_mfa (
    user_id                 BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    totp_secret_ciphertext  TEXT NOT NULL,
    enabled                 BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_mfa_secret_present CHECK (length(totp_secret_ciphertext) > 0),
    CONSTRAINT gateway_user_mfa_verified_enabled CHECK (enabled = FALSE OR verified_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS gateway_user_mfa_enabled_idx
    ON gateway_user_mfa (enabled);

CREATE TABLE IF NOT EXISTS gateway_user_kyc_profiles (
    user_id                 BIGINT PRIMARY KEY REFERENCES gateway_users(user_id),
    kyc_level               TEXT NOT NULL DEFAULT 'NONE',
    status                  TEXT NOT NULL DEFAULT 'UNVERIFIED',
    country                 TEXT,
    document_type           TEXT,
    provider                TEXT,
    provider_reference      TEXT,
    reviewed_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    reviewed_at             TIMESTAMPTZ,
    rejection_reason        TEXT,
    expires_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_kyc_level_check CHECK (kyc_level IN ('NONE', 'BASIC', 'STANDARD', 'ENHANCED', 'INSTITUTIONAL')),
    CONSTRAINT gateway_user_kyc_status_check CHECK (status IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT gateway_user_kyc_country_check CHECK (country IS NULL OR country ~ '^[A-Z]{2}$')
);

CREATE INDEX IF NOT EXISTS gateway_user_kyc_status_idx
    ON gateway_user_kyc_profiles (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_user_risk_tags (
    tag_id                  BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES gateway_users(user_id),
    tag_code                TEXT NOT NULL,
    severity                TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'ACTIVE',
    source                  TEXT,
    reason                  TEXT NOT NULL,
    created_by_user_id      BIGINT REFERENCES gateway_users(user_id),
    resolved_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_risk_tags_code_check CHECK (tag_code ~ '^[A-Z0-9_.:-]{2,64}$'),
    CONSTRAINT gateway_user_risk_tags_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT gateway_user_risk_tags_status_check CHECK (status IN ('ACTIVE', 'RESOLVED')),
    CONSTRAINT gateway_user_risk_tags_reason_present CHECK (length(reason) > 0)
);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_user_time_idx
    ON gateway_user_risk_tags (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_status_idx
    ON gateway_user_risk_tags (status, severity, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_created_page_idx
    ON gateway_user_risk_tags (created_at DESC, tag_id DESC);

CREATE INDEX IF NOT EXISTS gateway_user_risk_tags_updated_page_idx
    ON gateway_user_risk_tags (updated_at DESC, tag_id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_user_risk_tags_active_uidx
    ON gateway_user_risk_tags (user_id, tag_code)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS gateway_user_aml_cases (
    case_id                 BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES gateway_users(user_id),
    status                  TEXT NOT NULL DEFAULT 'OPEN',
    risk_score              INTEGER NOT NULL DEFAULT 0,
    source                  TEXT,
    summary                 TEXT NOT NULL,
    assigned_admin_user_id  BIGINT REFERENCES gateway_users(user_id),
    created_by_user_id      BIGINT REFERENCES gateway_users(user_id),
    reviewed_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    reviewed_at             TIMESTAMPTZ,
    closed_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_user_aml_cases_status_check CHECK (
        status IN ('OPEN', 'REVIEWING', 'CLEARED', 'ESCALATED', 'RESTRICTED', 'CLOSED')
    ),
    CONSTRAINT gateway_user_aml_cases_risk_score_check CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT gateway_user_aml_cases_summary_present CHECK (length(summary) > 0)
);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_user_time_idx
    ON gateway_user_aml_cases (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_status_idx
    ON gateway_user_aml_cases (status, risk_score DESC, updated_at DESC);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_updated_page_idx
    ON gateway_user_aml_cases (updated_at DESC, case_id DESC);

CREATE INDEX IF NOT EXISTS gateway_user_aml_cases_created_page_idx
    ON gateway_user_aml_cases (created_at DESC, case_id DESC);

CREATE TABLE IF NOT EXISTS gateway_support_tickets (
    ticket_id               BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES gateway_users(user_id),
    status                  TEXT NOT NULL DEFAULT 'OPEN',
    priority                TEXT NOT NULL DEFAULT 'MEDIUM',
    category                TEXT NOT NULL DEFAULT 'GENERAL',
    title                   TEXT NOT NULL,
    assigned_admin_user_id  BIGINT REFERENCES gateway_users(user_id),
    created_by_user_id      BIGINT NOT NULL REFERENCES gateway_users(user_id),
    resolved_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at               TIMESTAMPTZ,
    CONSTRAINT gateway_support_tickets_status_check CHECK (
        status IN ('OPEN', 'PENDING_USER', 'PENDING_INTERNAL', 'RESOLVED', 'CLOSED')
    ),
    CONSTRAINT gateway_support_tickets_priority_check CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')
    ),
    CONSTRAINT gateway_support_tickets_category_check CHECK (category ~ '^[A-Z0-9_.:-]{2,64}$'),
    CONSTRAINT gateway_support_tickets_title_present CHECK (length(title) BETWEEN 1 AND 160),
    CONSTRAINT gateway_support_tickets_closed_state_check CHECK (
        (closed_at IS NULL AND status <> 'CLOSED') OR (closed_at IS NOT NULL AND status = 'CLOSED')
    )
);

CREATE INDEX IF NOT EXISTS gateway_support_tickets_user_time_idx
    ON gateway_support_tickets (user_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS gateway_support_tickets_status_idx
    ON gateway_support_tickets (status, priority, updated_at DESC);

CREATE TABLE IF NOT EXISTS gateway_support_ticket_notes (
    note_id             BIGSERIAL PRIMARY KEY,
    ticket_id           BIGINT NOT NULL REFERENCES gateway_support_tickets(ticket_id) ON DELETE CASCADE,
    admin_user_id       BIGINT NOT NULL REFERENCES gateway_users(user_id),
    note_type           TEXT NOT NULL DEFAULT 'NOTE',
    visibility          TEXT NOT NULL DEFAULT 'INTERNAL',
    body                TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_support_ticket_notes_type_check CHECK (
        note_type IN ('NOTE', 'STATUS_CHANGE', 'ESCALATION', 'FOLLOW_UP')
    ),
    CONSTRAINT gateway_support_ticket_notes_visibility_check CHECK (
        visibility IN ('INTERNAL', 'CUSTOMER')
    ),
    CONSTRAINT gateway_support_ticket_notes_body_present CHECK (length(body) BETWEEN 1 AND 2000)
);

CREATE INDEX IF NOT EXISTS gateway_support_ticket_notes_ticket_time_idx
    ON gateway_support_ticket_notes (ticket_id, created_at ASC);

CREATE INDEX IF NOT EXISTS gateway_support_ticket_notes_page_idx
    ON gateway_support_ticket_notes (ticket_id, created_at ASC, note_id ASC);

CREATE TABLE IF NOT EXISTS gateway_refresh_sessions (
    session_id          BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES gateway_users(user_id),
    token_hash          TEXT NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    user_agent          TEXT,
    ip_address          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_refresh_sessions_hash_present CHECK (length(token_hash) > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_refresh_sessions_hash_uidx
    ON gateway_refresh_sessions (token_hash);

CREATE INDEX IF NOT EXISTS gateway_refresh_sessions_user_time_idx
    ON gateway_refresh_sessions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_refresh_sessions_expiry_idx
    ON gateway_refresh_sessions (expires_at ASC)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS gateway_login_logs (
    login_id            BIGSERIAL PRIMARY KEY,
    user_id             BIGINT REFERENCES gateway_users(user_id),
    result              TEXT NOT NULL,
    reason              TEXT,
    user_agent          TEXT,
    ip_address          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_login_logs_result_check CHECK (result IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS gateway_login_logs_user_time_idx
    ON gateway_login_logs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_login_logs_time_idx
    ON gateway_login_logs (created_at DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_operation_logs (
    operation_id          BIGSERIAL PRIMARY KEY,
    admin_user_id        BIGINT REFERENCES gateway_users(user_id),
    admin_username       TEXT,
    admin_roles          TEXT,
    service              TEXT NOT NULL,
    http_method          TEXT NOT NULL,
    request_path         TEXT NOT NULL,
    query_string         TEXT,
    target_uri           TEXT,
    request_body_sha256  TEXT,
    response_status      INTEGER,
    duration_ms          BIGINT,
    success              BOOLEAN NOT NULL,
    error_message        TEXT,
    trace_id             TEXT,
    user_agent           TEXT,
    ip_address           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_admin_operation_service_check CHECK (service ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT gateway_admin_operation_method_check CHECK (http_method ~ '^[A-Z]{3,16}$'),
    CONSTRAINT gateway_admin_operation_duration_non_negative CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_admin_time_idx
    ON gateway_admin_operation_logs (admin_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_service_time_idx
    ON gateway_admin_operation_logs (service, created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_time_idx
    ON gateway_admin_operation_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_operation_logs_trace_idx
    ON gateway_admin_operation_logs (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_admin_approval_requests (
    approval_id          BIGSERIAL PRIMARY KEY,
    requester_user_id    BIGINT NOT NULL REFERENCES gateway_users(user_id),
    requester_username   TEXT NOT NULL,
    approver_user_id     BIGINT REFERENCES gateway_users(user_id),
    approver_username    TEXT,
    service              TEXT NOT NULL,
    http_method          TEXT NOT NULL,
    request_path         TEXT NOT NULL,
    query_string         TEXT,
    request_body_sha256  TEXT,
    reason               TEXT NOT NULL,
    decision_reason      TEXT,
    status               TEXT NOT NULL DEFAULT 'PENDING',
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    decided_at           TIMESTAMPTZ,
    consumed_at          TIMESTAMPTZ,
    consumed_trace_id    TEXT,
    CONSTRAINT gateway_admin_approval_status_check CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'CONSUMED')
    ),
    CONSTRAINT gateway_admin_approval_service_check CHECK (service ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT gateway_admin_approval_method_check CHECK (http_method ~ '^[A-Z]{3,16}$')
);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_requester_time_idx
    ON gateway_admin_approval_requests (requester_user_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_status_time_idx
    ON gateway_admin_approval_requests (status, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_service_time_idx
    ON gateway_admin_approval_requests (service, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_approval_requests_consumed_trace_idx
    ON gateway_admin_approval_requests (consumed_trace_id)
    WHERE consumed_trace_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS gateway_admin_export_jobs (
    export_id              BIGSERIAL PRIMARY KEY,
    requested_by_user_id   BIGINT NOT NULL REFERENCES gateway_users(user_id),
    requested_by_username  TEXT NOT NULL,
    export_type            TEXT NOT NULL,
    status                 TEXT NOT NULL DEFAULT 'PENDING',
    format                 TEXT NOT NULL DEFAULT 'CSV',
    query_params           TEXT,
    file_name              TEXT,
    content_type           TEXT,
    row_count              INTEGER NOT NULL DEFAULT 0,
    byte_size              BIGINT NOT NULL DEFAULT 0,
    result_content         TEXT,
    error_message          TEXT,
    requested_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ NOT NULL DEFAULT now() + interval '7 days',
    CONSTRAINT gateway_admin_export_jobs_type_check CHECK (
        export_type IN (
            'USERS', 'LOGIN_LOGS', 'ADMIN_OPERATIONS', 'COMPLIANCE_USERS',
            'ORDERS', 'TRIGGER_ORDERS', 'MATCH_TRADES',
            'ACCOUNT_BALANCES', 'PRODUCT_BALANCES', 'POSITIONS',
            'ACCOUNT_LEDGER', 'PRODUCT_LEDGER', 'PRODUCT_TRANSFERS', 'ACCOUNT_ADJUSTMENTS'
        )
    ),
    CONSTRAINT gateway_admin_export_jobs_status_check CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT gateway_admin_export_jobs_format_check CHECK (format = 'CSV'),
    CONSTRAINT gateway_admin_export_jobs_row_count_non_negative CHECK (row_count >= 0),
    CONSTRAINT gateway_admin_export_jobs_byte_size_non_negative CHECK (byte_size >= 0)
);

CREATE INDEX IF NOT EXISTS gateway_admin_export_jobs_requester_time_idx
    ON gateway_admin_export_jobs (requested_by_user_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_export_jobs_status_time_idx
    ON gateway_admin_export_jobs (status, requested_at DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_query_tasks (
    query_task_id         BIGSERIAL PRIMARY KEY,
    requested_by_user_id  BIGINT NOT NULL REFERENCES gateway_users(user_id),
    requested_by_username TEXT NOT NULL,
    query_type            TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'PENDING',
    query_params          TEXT,
    result_json           TEXT,
    row_count             INTEGER NOT NULL DEFAULT 0,
    byte_size             BIGINT NOT NULL DEFAULT 0,
    error_message         TEXT,
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ NOT NULL DEFAULT now() + interval '3 days',
    archived_at           TIMESTAMPTZ,
    archive_reason        TEXT,
    CONSTRAINT gateway_admin_query_tasks_type_check CHECK (
        query_type IN (
            'SYSTEM_OPERATION_LATENCY', 'OUTBOX_BACKLOG',
            'APPROVAL_BACKLOG', 'ALERT_DELIVERY_FAILURES',
            'ORDER_AUDIT_SEARCH', 'TRIGGER_ORDER_AUDIT_SEARCH',
            'MATCH_TRADE_AUDIT_SEARCH'
        )
    ),
    CONSTRAINT gateway_admin_query_tasks_status_check CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'ARCHIVED')
    ),
    CONSTRAINT gateway_admin_query_tasks_row_count_non_negative CHECK (row_count >= 0),
    CONSTRAINT gateway_admin_query_tasks_byte_size_non_negative CHECK (byte_size >= 0)
);

CREATE INDEX IF NOT EXISTS gateway_admin_query_tasks_requester_time_idx
    ON gateway_admin_query_tasks (requested_by_user_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_query_tasks_status_time_idx
    ON gateway_admin_query_tasks (status, requested_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_query_tasks_type_time_idx
    ON gateway_admin_query_tasks (query_type, requested_at DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_account_asset_snapshots (
    snapshot_id             BIGSERIAL PRIMARY KEY,
    snapshot_date           DATE NOT NULL,
    valuation_asset         TEXT NOT NULL,
    account_type            TEXT NOT NULL,
    asset                   TEXT NOT NULL,
    total_available_units   NUMERIC(38, 0) NOT NULL,
    total_locked_units      NUMERIC(38, 0) NOT NULL,
    total_equity_units      NUMERIC(38, 0) NOT NULL,
    valuation_rate          NUMERIC(38, 18),
    total_value             NUMERIC(38, 18),
    valuation_source        TEXT NOT NULL,
    rate_updated_at         TIMESTAMPTZ,
    source_updated_at       TIMESTAMPTZ,
    user_count              BIGINT NOT NULL,
    balance_count           BIGINT NOT NULL,
    created_by_user_id      BIGINT REFERENCES gateway_users(user_id),
    created_by_username     TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_account_asset_snapshots_valuation_asset_check CHECK (valuation_asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT gateway_account_asset_snapshots_asset_check CHECK (asset ~ '^[A-Z0-9]{2,20}$'),
    CONSTRAINT gateway_account_asset_snapshots_account_type_check CHECK (
        account_type IN ('BASIC', 'FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                         'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
    ),
    CONSTRAINT gateway_account_asset_snapshots_counts_check CHECK (user_count >= 0 AND balance_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_account_asset_snapshots_uidx
    ON gateway_admin_account_asset_snapshots (snapshot_date, valuation_asset, account_type, asset);

DO $$
BEGIN
    IF to_regclass('public.gateway_admin_account_asset_snapshots') IS NOT NULL THEN
        ALTER TABLE gateway_admin_account_asset_snapshots
            DROP CONSTRAINT IF EXISTS gateway_account_asset_snapshots_account_type_check;
        ALTER TABLE gateway_admin_account_asset_snapshots
            ADD CONSTRAINT gateway_account_asset_snapshots_account_type_check CHECK (
                account_type IN ('BASIC', 'FUNDING', 'SPOT', 'USDT_PERPETUAL', 'COIN_PERPETUAL',
                                 'USDT_DELIVERY', 'COIN_DELIVERY', 'OPTION')
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS gateway_account_asset_snapshots_date_idx
    ON gateway_admin_account_asset_snapshots (snapshot_date DESC, valuation_asset, account_type, asset);

CREATE INDEX IF NOT EXISTS gateway_account_asset_snapshots_page_idx
    ON gateway_admin_account_asset_snapshots (snapshot_date DESC, total_value DESC NULLS LAST, snapshot_id DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_alert_rules (
    alert_rule_id          BIGSERIAL PRIMARY KEY,
    rule_code              TEXT NOT NULL,
    rule_name              TEXT NOT NULL,
    domain                 TEXT NOT NULL,
    metric_key             TEXT NOT NULL,
    target                 TEXT,
    condition_operator     TEXT NOT NULL,
    threshold_value        NUMERIC(38, 8) NOT NULL,
    severity               TEXT NOT NULL,
    enabled                BOOLEAN NOT NULL DEFAULT TRUE,
    window_seconds         BIGINT NOT NULL DEFAULT 300,
    cooldown_seconds       BIGINT NOT NULL DEFAULT 300,
    description            TEXT,
    created_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    updated_by_user_id     BIGINT REFERENCES gateway_users(user_id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_admin_alert_rules_code_check CHECK (rule_code ~ '^[A-Z0-9_.:-]{2,96}$'),
    CONSTRAINT gateway_admin_alert_rules_domain_check CHECK (
        domain IN ('SYSTEM', 'MARKET', 'TRADING', 'RISK', 'WALLET')
    ),
    CONSTRAINT gateway_admin_alert_rules_metric_check CHECK (metric_key ~ '^[A-Z0-9_.:-]{2,96}$'),
    CONSTRAINT gateway_admin_alert_rules_operator_check CHECK (
        condition_operator IN ('GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE')
    ),
    CONSTRAINT gateway_admin_alert_rules_severity_check CHECK (
        severity IN ('INFO', 'WARN', 'CRITICAL')
    ),
    CONSTRAINT gateway_admin_alert_rules_window_positive CHECK (
        window_seconds > 0 AND cooldown_seconds >= 0
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_admin_alert_rules_code_uidx
    ON gateway_admin_alert_rules (rule_code);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_rules_domain_idx
    ON gateway_admin_alert_rules (domain, enabled, updated_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_rules_updated_page_idx
    ON gateway_admin_alert_rules (updated_at DESC, alert_rule_id DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_rules_created_page_idx
    ON gateway_admin_alert_rules (created_at DESC, alert_rule_id DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_alert_events (
    alert_event_id         BIGSERIAL PRIMARY KEY,
    alert_rule_id          BIGINT REFERENCES gateway_admin_alert_rules(alert_rule_id),
    rule_code              TEXT NOT NULL,
    domain                 TEXT NOT NULL,
    metric_key             TEXT NOT NULL,
    target                 TEXT,
    severity               TEXT NOT NULL,
    status                 TEXT NOT NULL DEFAULT 'OPEN',
    condition_operator     TEXT NOT NULL,
    threshold_value        NUMERIC(38, 8) NOT NULL,
    current_value          NUMERIC(38, 8) NOT NULL,
    fingerprint            TEXT NOT NULL,
    message                TEXT NOT NULL,
    occurrences            BIGINT NOT NULL DEFAULT 1,
    first_seen_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_by_user_id BIGINT REFERENCES gateway_users(user_id),
    acknowledged_at        TIMESTAMPTZ,
    resolved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_admin_alert_events_domain_check CHECK (
        domain IN ('SYSTEM', 'MARKET', 'TRADING', 'RISK', 'WALLET')
    ),
    CONSTRAINT gateway_admin_alert_events_operator_check CHECK (
        condition_operator IN ('GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE')
    ),
    CONSTRAINT gateway_admin_alert_events_severity_check CHECK (
        severity IN ('INFO', 'WARN', 'CRITICAL')
    ),
    CONSTRAINT gateway_admin_alert_events_status_check CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')
    ),
    CONSTRAINT gateway_admin_alert_events_occurrences_positive CHECK (occurrences > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_admin_alert_events_active_uidx
    ON gateway_admin_alert_events (fingerprint)
    WHERE status IN ('OPEN', 'ACKNOWLEDGED');

CREATE INDEX IF NOT EXISTS gateway_admin_alert_events_status_time_idx
    ON gateway_admin_alert_events (status, severity, last_seen_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_events_rule_time_idx
    ON gateway_admin_alert_events (alert_rule_id, last_seen_at DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_alert_channels (
    alert_channel_id      BIGSERIAL PRIMARY KEY,
    channel_code          TEXT NOT NULL,
    channel_name          TEXT NOT NULL,
    channel_type          TEXT NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    domain                TEXT,
    min_severity          TEXT NOT NULL DEFAULT 'WARN',
    endpoint              TEXT,
    description           TEXT,
    created_by_user_id    BIGINT REFERENCES gateway_users(user_id),
    updated_by_user_id    BIGINT REFERENCES gateway_users(user_id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_admin_alert_channels_code_check CHECK (channel_code ~ '^[A-Z0-9_.:-]{2,96}$'),
    CONSTRAINT gateway_admin_alert_channels_type_check CHECK (
        channel_type IN ('WEBHOOK', 'EMAIL', 'SLACK', 'PAGERDUTY', 'OPS')
    ),
    CONSTRAINT gateway_admin_alert_channels_domain_check CHECK (
        domain IS NULL OR domain IN ('SYSTEM', 'MARKET', 'TRADING', 'RISK', 'WALLET')
    ),
    CONSTRAINT gateway_admin_alert_channels_severity_check CHECK (
        min_severity IN ('INFO', 'WARN', 'CRITICAL')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_admin_alert_channels_code_uidx
    ON gateway_admin_alert_channels (channel_code);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_channels_enabled_idx
    ON gateway_admin_alert_channels (enabled, domain, min_severity, updated_at DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_channels_updated_page_idx
    ON gateway_admin_alert_channels (updated_at DESC, alert_channel_id DESC);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_channels_created_page_idx
    ON gateway_admin_alert_channels (created_at DESC, alert_channel_id DESC);

CREATE TABLE IF NOT EXISTS gateway_admin_alert_deliveries (
    alert_delivery_id     BIGSERIAL PRIMARY KEY,
    alert_event_id        BIGINT NOT NULL REFERENCES gateway_admin_alert_events(alert_event_id),
    alert_channel_id      BIGINT NOT NULL REFERENCES gateway_admin_alert_channels(alert_channel_id),
    channel_code          TEXT NOT NULL,
    channel_type          TEXT NOT NULL,
    delivery_status       TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    last_attempt_at       TIMESTAMPTZ,
    delivered_at          TIMESTAMPTZ,
    error_message         TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gateway_admin_alert_deliveries_status_check CHECK (
        delivery_status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT gateway_admin_alert_deliveries_attempt_check CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS gateway_admin_alert_deliveries_event_channel_uidx
    ON gateway_admin_alert_deliveries (alert_event_id, alert_channel_id);

CREATE INDEX IF NOT EXISTS gateway_admin_alert_deliveries_status_idx
    ON gateway_admin_alert_deliveries (delivery_status, next_attempt_at, created_at DESC);
