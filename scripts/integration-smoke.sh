#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PGPORT="${PGPORT:-55432}"

resolve_pg_tool() {
  local tool="$1"
  if [[ -n "${PG_BIN:-}" && -x "${PG_BIN}/${tool}" ]]; then
    echo "${PG_BIN}/${tool}"
    return
  fi
  if command -v "${tool}" >/dev/null 2>&1; then
    command -v "${tool}"
    return
  fi
  for candidate in \
    "/usr/local/opt/postgresql@18/bin/${tool}" \
    "/opt/homebrew/opt/postgresql@18/bin/${tool}" \
    "/usr/local/opt/postgresql@16/bin/${tool}" \
    "/opt/homebrew/opt/postgresql@16/bin/${tool}"; do
    if [[ -x "${candidate}" ]]; then
      echo "${candidate}"
      return
    fi
  done
  echo "Missing PostgreSQL tool: ${tool}" >&2
  exit 1
}

INITDB="$(resolve_pg_tool initdb)"
PG_CTL="$(resolve_pg_tool pg_ctl)"
CREATEDB="$(resolve_pg_tool createdb)"
PSQL="$(resolve_pg_tool psql)"

TMP_DIR="$(mktemp -d /tmp/surprising-smoke.XXXXXX)"
DB_DIR="${TMP_DIR}/data"
LOG_FILE="${TMP_DIR}/postgres.log"
DB_NAME="surprising_ex_smoke"

cleanup() {
  "${PG_CTL}" -D "${DB_DIR}" -m fast stop >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

echo "Starting temporary PostgreSQL on port ${PGPORT}"
"${INITDB}" -D "${DB_DIR}" >/dev/null
"${PG_CTL}" -D "${DB_DIR}" -o "-p ${PGPORT} -k ${TMP_DIR}" -l "${LOG_FILE}" start >/dev/null
"${CREATEDB}" -h "${TMP_DIR}" -p "${PGPORT}" "${DB_NAME}"

echo "Applying init.sql"
"${PSQL}" -h "${TMP_DIR}" -p "${PGPORT}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -f "${ROOT_DIR}/init.sql" >/dev/null

echo "Running trading/account/risk/liquidation smoke checks"
"${PSQL}" -h "${TMP_DIR}" -p "${PGPORT}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
VALUES
    (1001, 'USDT', 100000000000, 0, now()),
    (2002, 'USDT', 100000000000, 0, now()),
    (3003, 'BTC', 100000000, 0, now());

-- Version 2 intentionally changes the multiplier. Existing v1 positions must not be reinterpreted.
INSERT INTO instruments (
    symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
    contract_multiplier_ppm, contract_value_asset, price_tick_units, quantity_step_units,
    min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,
    notional_multiplier_units,
    price_precision, quantity_precision, supported_order_types, supported_time_in_force,
    post_only_enabled, reduce_only_enabled, market_order_enabled,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm, max_position_notional_units,
    funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
    impact_notional_units, min_valid_index_sources, status, effective_time, created_at, updated_at
)
SELECT symbol, 2, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
       contract_multiplier_ppm, contract_value_asset, price_tick_units * 2, quantity_step_units,
       min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,
       notional_multiplier_units * 2,
       price_precision, quantity_precision, supported_order_types, supported_time_in_force,
       post_only_enabled, reduce_only_enabled, market_order_enabled,
       max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm, max_position_notional_units,
       funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
       impact_notional_units, min_valid_index_sources, status, now(), now(), now()
  FROM instruments
 WHERE symbol = 'BTC-USDT' AND version = 1;

INSERT INTO instrument_risk_brackets (
    symbol, version, bracket_no, notional_floor_units, notional_cap_units,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
)
SELECT symbol, 2, bracket_no, notional_floor_units, notional_cap_units,
       max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
  FROM instrument_risk_brackets
 WHERE symbol = 'BTC-USDT' AND version = 1;

UPDATE instrument_current_versions
   SET version = 2, updated_at = now()
 WHERE symbol = 'BTC-USDT';

INSERT INTO trading_orders (
    order_id, product_line, user_id, client_order_id, symbol, instrument_version, side, order_type, time_in_force,
    price_ticks, quantity_steps, executed_quantity_steps, remaining_quantity_steps,
    reduce_only, post_only, status, reject_reason, created_at, updated_at
) VALUES
    (9001, 'LINEAR_PERPETUAL', 1001, 'maker-1', 'BTC-USDT', 1, 'SELL', 'LIMIT', 'GTC', 600000, 10, 3, 7, FALSE, FALSE, 'PARTIALLY_FILLED', NULL, now(), now()),
    (9002, 'LINEAR_PERPETUAL', 2002, 'taker-1', 'BTC-USDT', 1, 'BUY', 'LIMIT', 'IOC', 600000, 3, 3, 0, FALSE, FALSE, 'FILLED', NULL, now(), now());

INSERT INTO trading_match_results (
    command_id, product_line, order_id, user_id, symbol, instrument_version, command_type, result_code,
    filled_quantity_steps, order_status, event_time
) VALUES (9101, 'LINEAR_PERPETUAL', 9002, 2002, 'BTC-USDT', 1, 'PLACE', 'SUCCESS', 3, 'FILLED', now());

INSERT INTO trading_match_trades (
    trade_id, command_id, product_line, symbol, taker_order_id, taker_instrument_version, taker_user_id, taker_side,
    maker_order_id, maker_instrument_version, maker_user_id, price_ticks, quantity_steps,
    taker_order_completed, maker_order_completed, event_time
) VALUES (9201, 9101, 'LINEAR_PERPETUAL', 'BTC-USDT', 9002, 1, 2002, 'BUY', 9001, 1, 1001, 600000, 3, TRUE, FALSE, now());

INSERT INTO account_positions (
    product_line, user_id, symbol, instrument_version, signed_quantity_steps,
    entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
)
VALUES
    ('LINEAR_PERPETUAL', 1001, 'BTC-USDT', 1, -3, 600000, 1800000, 0, now()),
    ('LINEAR_PERPETUAL', 2002, 'BTC-USDT', 1, 3, 600000, 1800000, 0, now());

INSERT INTO price_mark_ticks (
    product_line, symbol, instrument_version, sequence, mark_price, mark_price_units, mark_price_ticks,
    index_price, price1, price2, last_trade_price,
    best_bid_price, best_ask_price, funding_rate, next_funding_time,
    time_until_funding_seconds, basis_average, basis_window_seconds, clamp_low,
    clamp_high, status, event_time, published_at, calculation_inputs
) VALUES (
    'LINEAR_PERPETUAL', 'BTC-USDT', 1, 9901, 59000, 5900000000000, 590000,
    59000, 59000, 59000, 59000,
    58999, 59001, 0, now() + interval '1 hour',
    3600, 0, 300, 1, 100000, 'HEALTHY', now(), now(), '{}'::jsonb
);

DO $$
DECLARE
    actual_notional BIGINT;
    actual_pnl BIGINT;
    active_candidates INTEGER;
BEGIN
    SELECT CAST(round(abs(p.signed_quantity_steps)::NUMERIC * pm.mark_price_ticks * i.notional_multiplier_units) AS BIGINT),
           CAST(round(p.signed_quantity_steps::NUMERIC * (pm.mark_price_ticks - p.entry_price_ticks) * i.notional_multiplier_units) AS BIGINT)
      INTO actual_notional, actual_pnl
      FROM account_positions p
      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
      JOIN LATERAL (
          SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_price_ticks
            FROM price_mark_ticks m
           WHERE m.symbol = p.symbol
           ORDER BY event_time DESC
           LIMIT 1
      ) pm ON TRUE
     WHERE p.user_id = 2002 AND p.symbol = 'BTC-USDT';

    IF actual_notional <> 17700000000 OR actual_pnl <> -300000000 THEN
        RAISE EXCEPTION 'version-pinned linear risk mismatch: notional %, pnl %', actual_notional, actual_pnl;
    END IF;

    INSERT INTO risk_account_snapshots (
        snapshot_id, product_line, user_id, account_type, settle_asset, wallet_balance_units, unrealized_pnl_units,
        equity_units, maintenance_margin_units, margin_ratio_ppm, status, event_time
    ) VALUES (9301, 'LINEAR_PERPETUAL', 2002, 'USDT_PERPETUAL', 'USDT', 100000000, -300000000, -200000000, 88500000, 1000000000000, 'LIQUIDATION', now());

    INSERT INTO risk_position_snapshots (
        product_line, snapshot_id, user_id, symbol, instrument_version, settle_asset, signed_quantity_steps,
        entry_price_ticks, mark_price_ticks, notional_units, unrealized_pnl_units,
        maintenance_margin_units, margin_ratio_ppm, status, event_time
    ) VALUES ('LINEAR_PERPETUAL', 9301, 2002, 'BTC-USDT', 1, 'USDT', 3, 600000, 590000,
              17700000000, -300000000, 88500000, 1000000000000, 'LIQUIDATION', now());

    INSERT INTO risk_liquidation_candidates (
        candidate_id, product_line, snapshot_id, user_id, symbol, instrument_version, account_type, settle_asset,
        signed_quantity_steps, mark_price_ticks, equity_units, maintenance_margin_units,
        margin_ratio_ppm, status, event_time
    ) VALUES (9401, 'LINEAR_PERPETUAL', 9301, 2002, 'BTC-USDT', 1, 'USDT_PERPETUAL', 'USDT', 3, 590000, -200000000, 88500000, 1000000000000, 'NEW', now())
    ON CONFLICT DO NOTHING;

    INSERT INTO risk_liquidation_candidates (
        candidate_id, product_line, snapshot_id, user_id, symbol, instrument_version, account_type, settle_asset,
        signed_quantity_steps, mark_price_ticks, equity_units, maintenance_margin_units,
        margin_ratio_ppm, status, event_time
    ) VALUES (9402, 'LINEAR_PERPETUAL', 9301, 2002, 'BTC-USDT', 1, 'USDT_PERPETUAL', 'USDT', 3, 590000, -200000000, 88500000, 1000000000000, 'NEW', now())
    ON CONFLICT DO NOTHING;

    SELECT count(*) INTO active_candidates
      FROM risk_liquidation_candidates
     WHERE user_id = 2002 AND symbol = 'BTC-USDT' AND status IN ('NEW', 'PROCESSING');

    IF active_candidates <> 1 THEN
        RAISE EXCEPTION 'active liquidation candidate uniqueness failed: %', active_candidates;
    END IF;
END $$;

INSERT INTO funding_rate_ticks (
    symbol, sequence, funding_time, funding_interval_hours,
    premium_rate_ppm, interest_rate_ppm, funding_rate_ppm,
    status, event_time, created_at
) VALUES (
    'BTC-USDT', 9901, now() - interval '1 minute', 8,
    0, 0, 1000, 'PREDICTED', now(), now()
);

DO $$
DECLARE
    funding_notional BIGINT;
    funding_payment BIGINT;
BEGIN
    WITH rate_row AS (
        SELECT funding_rate_ppm
          FROM funding_rate_ticks
         WHERE symbol = 'BTC-USDT'
         ORDER BY sequence DESC
         LIMIT 1
    )
    SELECT CAST(round(
               CASE
                   WHEN i.contract_type = 'INVERSE_PERPETUAL' THEN
                       abs(p.signed_quantity_steps)::NUMERIC
                       * i.notional_multiplier_units
                       * ss.scale_units
                       / NULLIF(mark_row.mark_price_ticks::NUMERIC * i.price_tick_units, 0)
                   ELSE
                       abs(p.signed_quantity_steps)::NUMERIC
                       * mark_row.mark_price_ticks
                       * i.notional_multiplier_units
               END
           ) AS BIGINT) AS notional_units,
           CASE
               WHEN p.signed_quantity_steps > 0
                   THEN -CAST(round(CAST(round(
                       abs(p.signed_quantity_steps)::NUMERIC
                       * mark_row.mark_price_ticks
                       * i.notional_multiplier_units
                   ) AS BIGINT)::NUMERIC * rate_row.funding_rate_ppm / 1000000) AS BIGINT)
               ELSE CAST(round(CAST(round(
                       abs(p.signed_quantity_steps)::NUMERIC
                       * mark_row.mark_price_ticks
                       * i.notional_multiplier_units
                   ) AS BIGINT)::NUMERIC * rate_row.funding_rate_ppm / 1000000) AS BIGINT)
           END AS amount_units
      INTO funding_notional, funding_payment
      FROM account_positions p
      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
      JOIN account_asset_scales ss ON ss.asset = i.settle_asset
      JOIN LATERAL (
          SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_price_ticks
            FROM price_mark_ticks m
           WHERE m.symbol = p.symbol
           ORDER BY m.event_time DESC
           LIMIT 1
      ) mark_row ON TRUE
      CROSS JOIN rate_row
     WHERE p.user_id = 2002 AND p.symbol = 'BTC-USDT';

    IF funding_notional <> 17700000000 OR funding_payment <> -17700000 THEN
        RAISE EXCEPTION 'version-pinned funding mismatch: notional %, payment %',
            funding_notional, funding_payment;
    END IF;
END $$;

INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
VALUES
    (4004, 'USDT', 0, 0, now()),
    (5005, 'USDT', 0, 1000, now());

INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
VALUES
    (4004, 'USDT', 1000, now()),
    (5005, 'USDT', 0, now());

INSERT INTO insurance_fund_balances (asset, balance_units, updated_at)
VALUES ('USDT', 600, now());

INSERT INTO insurance_deficit_coverages (
    coverage_id, user_id, asset, requested_units, covered_units,
    remaining_deficit_units, status, reason, created_at, updated_at
) VALUES (9501, 4004, 'USDT', 1000, 600, 400, 'PARTIALLY_COVERED', 'DEFICIT_COVERAGE', now(), now());

UPDATE insurance_fund_balances
   SET balance_units = 0, updated_at = now()
 WHERE asset = 'USDT';

UPDATE account_deficits
   SET deficit_units = 400, updated_at = now()
 WHERE user_id = 4004 AND asset = 'USDT';

INSERT INTO insurance_fund_ledger (
    entry_id, asset, amount_units, balance_after_units,
    reference_type, reference_id, reason, created_at
) VALUES (9601, 'USDT', -600, 0, 'DEFICIT_COVERAGE', '9501', 'COVER_ACCOUNT_DEFICIT', now());

INSERT INTO account_ledger_entries (
    entry_id, user_id, asset, amount_units, balance_after_units,
    reference_type, reference_id, reason, created_at
) VALUES (9701, 4004, 'USDT', 600, -400, 'INSURANCE_COVERAGE', '9501', 'COVER_ACCOUNT_DEFICIT', now());

DO $$
DECLARE
    fund_balance BIGINT;
    deficit_after BIGINT;
    available_after BIGINT;
BEGIN
    SELECT balance_units INTO fund_balance
      FROM insurance_fund_balances
     WHERE asset = 'USDT';
    SELECT deficit_units INTO deficit_after
      FROM account_deficits
     WHERE user_id = 4004 AND asset = 'USDT';
    SELECT available_units INTO available_after
      FROM account_balances
     WHERE user_id = 4004 AND asset = 'USDT';

    IF fund_balance <> 0 OR deficit_after <> 400 OR available_after <> 0 THEN
        RAISE EXCEPTION 'insurance coverage mismatch: fund %, deficit %, available %',
            fund_balance, deficit_after, available_after;
    END IF;
END $$;

INSERT INTO account_positions (
    product_line, user_id, symbol, instrument_version, signed_quantity_steps,
    entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
) VALUES ('LINEAR_PERPETUAL', 5005, 'BTC-USDT', 1, 10, 580000, 5800000, 0, now());

INSERT INTO account_position_margins (product_line, user_id, symbol, asset, margin_units, updated_at)
VALUES ('LINEAR_PERPETUAL', 5005, 'BTC-USDT', 'USDT', 1000, now());

DO $$
DECLARE
    close_steps BIGINT := 1;
    covered_units BIGINT := 400;
    mark_ticks BIGINT;
    notional_multiplier BIGINT;
    released_margin BIGINT;
    realized_profit BIGINT;
    target_available BIGINT;
    target_locked BIGINT;
    remaining_deficit BIGINT;
    target_position BIGINT;
    target_margin BIGINT;
BEGIN
    SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units),
           i.notional_multiplier_units
      INTO mark_ticks, notional_multiplier
      FROM price_mark_ticks m
      JOIN instruments i ON i.symbol = m.symbol AND i.version = 1
     WHERE m.symbol = 'BTC-USDT'
     ORDER BY m.event_time DESC
     LIMIT 1;

    realized_profit := (mark_ticks - 580000) * close_steps * notional_multiplier;
    released_margin := 1000 * close_steps / 10;

    UPDATE account_positions
       SET signed_quantity_steps = 9,
           realized_pnl_units = realized_pnl_units + realized_profit,
           updated_at = now()
     WHERE user_id = 5005 AND symbol = 'BTC-USDT';

    UPDATE account_position_margins
       SET margin_units = margin_units - released_margin,
           updated_at = now()
     WHERE user_id = 5005 AND symbol = 'BTC-USDT' AND asset = 'USDT';

    UPDATE account_balances
       SET locked_units = locked_units - released_margin,
           available_units = available_units + released_margin + realized_profit - covered_units,
           updated_at = now()
     WHERE user_id = 5005 AND asset = 'USDT';

    UPDATE account_deficits
       SET deficit_units = 0,
           updated_at = now()
     WHERE user_id = 4004 AND asset = 'USDT';

    INSERT INTO account_ledger_entries (
        entry_id, user_id, asset, amount_units, balance_after_units,
        reference_type, reference_id, reason, created_at
    ) VALUES
        (9702, 5005, 'USDT', realized_profit, released_margin + realized_profit + (1000 - released_margin),
         'ADL_REALIZED_PNL', '9801', 'ADL_POSITION_DELEVERAGED', now()),
        (9703, 5005, 'USDT', -covered_units, released_margin + realized_profit - covered_units + (1000 - released_margin),
         'ADL_TRANSFER', '9801', 'ADL_DEFICIT_TRANSFER', now()),
        (9704, 4004, 'USDT', covered_units, 0,
         'ADL_COVERAGE', '9801', 'ADL_DEFICIT_COVERAGE', now());

    INSERT INTO adl_events (
        event_id, deficit_user_id, target_user_id, asset, symbol, target_side,
        closed_quantity_steps, entry_price_ticks, mark_price_ticks, requested_deficit_units,
        realized_profit_units, covered_units, remaining_deficit_units,
        priority_score_ppm, reason, created_at
    ) VALUES (
        9801, 4004, 5005, 'USDT', 'BTC-USDT', 'LONG',
        close_steps, 580000, mark_ticks, 400,
        realized_profit, covered_units, 0,
        1, 'ADL_DEFICIT_COVERAGE', now()
    );

    SELECT available_units, locked_units INTO target_available, target_locked
      FROM account_balances
     WHERE user_id = 5005 AND asset = 'USDT';
    SELECT deficit_units INTO remaining_deficit
      FROM account_deficits
     WHERE user_id = 4004 AND asset = 'USDT';
    SELECT signed_quantity_steps INTO target_position
      FROM account_positions
     WHERE user_id = 5005 AND symbol = 'BTC-USDT';
    SELECT margin_units INTO target_margin
      FROM account_position_margins
     WHERE user_id = 5005 AND symbol = 'BTC-USDT' AND asset = 'USDT';

    IF realized_profit <> 100000000
       OR target_available <> 99999700
       OR target_locked <> 900
       OR remaining_deficit <> 0
       OR target_position <> 9
       OR target_margin <> 900 THEN
        RAISE EXCEPTION 'ADL settlement mismatch: profit %, available %, locked %, deficit %, position %, margin %',
            realized_profit, target_available, target_locked, remaining_deficit, target_position, target_margin;
    END IF;
END $$;

-- Inverse contract check: 1 contract, 100 USD face value, entry 50k, mark 60k -> 33333 sat profit.
INSERT INTO instruments (
    symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
    contract_multiplier_ppm, contract_value_asset, price_tick_units, quantity_step_units,
    min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,
    notional_multiplier_units,
    price_precision, quantity_precision, supported_order_types, supported_time_in_force,
    post_only_enabled, reduce_only_enabled, market_order_enabled,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm, max_position_notional_units,
    funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
    impact_notional_units, min_valid_index_sources, status, effective_time, created_at, updated_at
) VALUES (
    'BTC-USD', 1, 'PERPETUAL', 'INVERSE_PERPETUAL', 'BTC', 'USD', 'BTC',
    1000000, 'USD', 100000000, 1,
    1, 100000, 10000000000, 1000000000000000,
    10000000000,
    0, 0, 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX',
    TRUE, TRUE, TRUE,
    100000000, 10000, 5000, 100000000000,
    8, 100, 3000, -3000,
    10000000000, 1, 'TRADING', now(), now(), now()
);

INSERT INTO instrument_current_versions (symbol, version, updated_at)
VALUES ('BTC-USD', 1, now());

INSERT INTO account_positions (
    product_line, user_id, symbol, instrument_version, signed_quantity_steps,
    entry_price_ticks, entry_value_ticks, realized_pnl_units, updated_at
)
VALUES ('INVERSE_PERPETUAL', 3003, 'BTC-USD', 1, 1, 50000, 50000, 0, now());

INSERT INTO price_mark_ticks (
    product_line, symbol, instrument_version, sequence, mark_price, mark_price_units, mark_price_ticks,
    index_price, price1, price2, last_trade_price,
    best_bid_price, best_ask_price, funding_rate, next_funding_time,
    time_until_funding_seconds, basis_average, basis_window_seconds, clamp_low,
    clamp_high, status, event_time, published_at, calculation_inputs
) VALUES (
    'INVERSE_PERPETUAL', 'BTC-USD', 1, 9902, 60000, 6000000000000, 600000,
    60000, 60000, 60000, 60000,
    59999, 60001, 0, now() + interval '1 hour',
    3600, 0, 300, 1, 100000, 'HEALTHY', now(), now(), '{}'::jsonb
);

DO $$
DECLARE
    inverse_notional BIGINT;
    inverse_pnl BIGINT;
BEGIN
    SELECT CAST(round(abs(p.signed_quantity_steps)::NUMERIC * i.notional_multiplier_units * ss.scale_units
                     / NULLIF(pm.mark_price_ticks::NUMERIC * i.price_tick_units, 0)) AS BIGINT),
           CAST(round(p.signed_quantity_steps::NUMERIC * i.notional_multiplier_units * ss.scale_units
                     * (pm.mark_price_ticks - p.entry_price_ticks)
                     / NULLIF(p.entry_price_ticks::NUMERIC * pm.mark_price_ticks * i.price_tick_units, 0)) AS BIGINT)
      INTO inverse_notional, inverse_pnl
      FROM account_positions p
      JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
      JOIN account_asset_scales ss ON ss.asset = i.settle_asset
      JOIN LATERAL (
          SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_price_ticks
            FROM price_mark_ticks m
           WHERE m.symbol = p.symbol
           ORDER BY event_time DESC
           LIMIT 1
      ) pm ON TRUE
     WHERE p.user_id = 3003 AND p.symbol = 'BTC-USD';

    IF inverse_notional <> 166667 OR inverse_pnl <> 33333 THEN
        RAISE EXCEPTION 'inverse risk mismatch: notional %, pnl %', inverse_notional, inverse_pnl;
    END IF;
END $$;
SQL

if [[ "${RUN_MAVEN:-false}" == "true" ]]; then
  echo "Running Maven tests"
  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
  if [[ -n "${JAVA_HOME}" ]]; then
    PATH="${JAVA_HOME}/bin:${PATH}" mvn -q test
  else
    mvn -q test
  fi
fi

echo "Integration smoke passed"
