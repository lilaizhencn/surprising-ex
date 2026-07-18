#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="${DB_NAME:-surprising_exchange}"
GATEWAY_BASE="${GATEWAY_BASE:-http://localhost:9094/api/v1/gateway}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 100000000))

LINEAR_SYMBOL="${LINEAR_SYMBOL:-ETH-USDT}"
COIN_SYMBOL="${COIN_SYMBOL:-BTC-USD}"
SPOT_SYMBOL="${SPOT_SYMBOL:-BTC-USDT-SPOT}"
MM_SYMBOL="${MM_SYMBOL:-BTC-USDT}"
MM_STRATEGY_ID="${MM_STRATEGY_ID:-btc-usdt-mm-a}"

LINEAR_ENTRY_TICKS="${LINEAR_ENTRY_TICKS:-300000}"
LINEAR_EXIT_TICKS="${LINEAR_EXIT_TICKS:-302000}"
LINEAR_QTY="${LINEAR_QTY:-5}"
COIN_ENTRY_TICKS="${COIN_ENTRY_TICKS:-600000}"
COIN_EXIT_TICKS="${COIN_EXIT_TICKS:-610000}"
COIN_QTY="${COIN_QTY:-10}"
SPOT_PRICE_TICKS="${SPOT_PRICE_TICKS:-600000}"
SPOT_QTY="${SPOT_QTY:-2}"
ACCOUNT_INITIAL_USDT="${ACCOUNT_INITIAL_USDT:-100000000000}"
COIN_INITIAL_BTC="${COIN_INITIAL_BTC:-100000000}"
SPOT_INITIAL_BTC="${SPOT_INITIAL_BTC:-1000000}"
SPOT_INITIAL_USDT="${SPOT_INITIAL_USDT:-100000000000}"

LINEAR_SHORT_USER=$((8300000000 + RUN_SEQ * 20 + 1))
LINEAR_LONG_USER=$((8300000000 + RUN_SEQ * 20 + 2))
COIN_SHORT_USER=$((8300000000 + RUN_SEQ * 20 + 3))
COIN_LONG_USER=$((8300000000 + RUN_SEQ * 20 + 4))
SPOT_SELLER_USER=$((8300000000 + RUN_SEQ * 20 + 5))
SPOT_BUYER_USER=$((8300000000 + RUN_SEQ * 20 + 6))

export PGPASSWORD="${DB_PASSWORD}"

psql_exec() {
  psql -h "${DB_HOST}" -U "${DB_USER}" -d "${DB_NAME}" "$@"
}

query_value() {
  local sql="$1"
  psql_exec -At -c "${sql}"
}

kafka_producer_cmd() {
  if command -v kafka-console-producer >/dev/null 2>&1; then
    echo kafka-console-producer
  else
    echo kafka-console-producer.sh
  fi
}

mark_price_topic() {
  case "${PRODUCT_LINE}" in
    LINEAR_PERPETUAL) echo "${MARK_PRICE_TOPIC:-surprising.perp.mark.price.v1}" ;;
    INVERSE_PERPETUAL) echo "${MARK_PRICE_TOPIC:-surprising.inverse-perp.mark.price.v1}" ;;
    SPOT) echo "${MARK_PRICE_TOPIC:-surprising.spot.mark.price.v1}" ;;
    *) echo "Unsupported PRODUCT_LINE=${PRODUCT_LINE}" >&2; exit 1 ;;
  esac
}

wait_sql_equals() {
  local description="$1"
  local sql="$2"
  local expected="$3"
  local actual
  local deadline=$((SECONDS + 180))
  while true; do
    actual="$(query_value "${sql}" || true)"
    if [[ "${actual}" == "${expected}" ]]; then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${description}: expected '${expected}', got '${actual}'" >&2
      exit 1
    fi
    sleep 1
  done
}

wait_sql_nonzero() {
  local description="$1"
  local sql="$2"
  local actual
  local deadline=$((SECONDS + 180))
  while true; do
    actual="$(query_value "${sql}" || true)"
    if [[ "${actual}" =~ ^[0-9]+$ ]] && (( actual > 0 )); then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${description}: got '${actual}'" >&2
      exit 1
    fi
    sleep 1
  done
}

json_field() {
  local field="$1"
  python3 -c "import json,sys; data=json.load(sys.stdin); data=data.get('data', data); print(data['${field}'])"
}

decimal_price() {
  local ticks="$1"
  local tick_units="$2"
  python3 - "${ticks}" "${tick_units}" <<'PY'
import decimal
import sys

ticks = decimal.Decimal(sys.argv[1])
tick_units = decimal.Decimal(sys.argv[2])
price = ticks * tick_units / decimal.Decimal(100000000)
print(price.quantize(decimal.Decimal("0.00000001")))
PY
}

ceil_div() {
  python3 - "$1" "$2" <<'PY'
import sys

numerator = int(sys.argv[1])
denominator = int(sys.argv[2])
print((numerator + denominator - 1) // denominator)
PY
}

round_div_signed() {
  python3 - "$1" "$2" <<'PY'
import sys

numerator = int(sys.argv[1])
denominator = int(sys.argv[2])
sign = -1 if numerator < 0 else 1
absolute = abs(numerator)
quotient, remainder = divmod(absolute, denominator)
if remainder * 2 >= denominator:
    quotient += 1
print(sign * quotient)
PY
}

instrument_field() {
  local symbol="$1"
  local field="$2"
  query_value "SELECT ${field} FROM instruments WHERE symbol = '${symbol}' AND status = 'TRADING' ORDER BY version DESC LIMIT 1"
}

asset_scale() {
  local asset="$1"
  query_value "SELECT scale_units FROM account_asset_scales WHERE asset = '${asset}'"
}

require_health() {
  local name="$1"
  local port="$2"
  curl -fsS "http://localhost:${port}/actuator/health" >/dev/null
  echo "health ${name}=UP"
}

publish_mark_price() {
  local symbol="$1"
  local tick_units="$2"
  local sequence="$3"
  local price_ticks="$4"
  local price
  local bid
  local ask
  local units
  local instrument_version
  local event_time
  local payload
  price="$(decimal_price "${price_ticks}" "${tick_units}")"
  bid="$(decimal_price "$((price_ticks - 1))" "${tick_units}")"
  ask="$(decimal_price "$((price_ticks + 1))" "${tick_units}")"
  units=$((price_ticks * tick_units))
  instrument_version="$(instrument_field "${symbol}" "version")"
  event_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  payload="{\"result\":{\"productLine\":\"${PRODUCT_LINE}\",\"symbol\":\"${symbol}\",\"instrumentVersion\":${instrument_version},\"markPriceUnits\":${units},\"markPriceTicks\":${price_ticks},\"markPrice\":${price},\"indexPrice\":${price},\"price1\":${price},\"price2\":${price},\"lastTradePrice\":${price},\"bestBidPrice\":${bid},\"bestAskPrice\":${ask},\"fundingRate\":0,\"nextFundingTime\":\"${event_time}\",\"timeUntilFundingSeconds\":0,\"basisAverage\":0,\"basisWindowSeconds\":60,\"clampLow\":${price},\"clampHigh\":${price},\"sequence\":${sequence},\"status\":\"HEALTHY\",\"eventTime\":\"${event_time}\",\"publishedAt\":\"${event_time}\"},\"indexInput\":null,\"bookInput\":null,\"tradeInput\":null,\"fundingInput\":null,\"basisAverage\":0,\"basisWindowSeconds\":60,\"calculatedAt\":\"${event_time}\"}"
  printf '%s:%s\n' "${symbol}" "${payload}" | "$(kafka_producer_cmd)" \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
    --topic "$(mark_price_topic)" \
    --property parse.key=true \
    --property key.separator=: >/dev/null
}

refresh_mark_price() {
  local symbol="$1"
  local price_ticks="$2"
  local tick_units
  tick_units="$(instrument_field "${symbol}" "price_tick_units")"
  publish_mark_price "${symbol}" "${tick_units}" "$((RUN_SEQ + SECONDS + price_ticks))" "${price_ticks}"
  local deadline=$((SECONDS + 30))
  until [[ "$(latest_mark_ticks "${symbol}" 2>/dev/null || true)" == "${price_ticks}" ]]; do
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for Kafka mark price ${symbol}=${price_ticks}" >&2
      exit 1
    fi
    sleep 1
  done
}

latest_mark_ticks() {
  local symbol="$1"
  local tick_units
  tick_units="$(instrument_field "${symbol}" "price_tick_units")"
  local units
  units="$(curl -fsS "${GATEWAY_BASE}/price-mark/mark/latest?symbol=${symbol}" | json_field markPriceUnits)"
  echo $(((units + tick_units / 2) / tick_units))
}

gateway_post() {
  local service_path="$1"
  local user_id="$2"
  local trace_id="$3"
  local payload="$4"
  curl -fsS -X POST "${GATEWAY_BASE}/${service_path}" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: ${trace_id}" \
    -d "${payload}"
}

adjust_balance() {
  local user_id="$1"
  local amount_units="$2"
  local reference_id="$3"
  gateway_post "account/admin/balance-adjustments" 1 "live-${RUN_ID}-${reference_id}" "{
    \"userId\": ${user_id},
    \"asset\": \"USDT\",
    \"amountUnits\": ${amount_units},
    \"referenceId\": \"${reference_id}\",
    \"reason\": \"LIVE_RUNTIME_RECONCILIATION\"
  }" >/dev/null
}

adjust_product_balance() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  local amount_units="$4"
  local reference_id="$5"
  gateway_post "account/admin/product-balance-adjustments" 1 "live-${RUN_ID}-${reference_id}" "{
    \"userId\": ${user_id},
    \"accountType\": \"${account_type}\",
    \"asset\": \"${asset}\",
    \"amountUnits\": ${amount_units},
    \"referenceId\": \"${reference_id}\",
    \"reason\": \"LIVE_RUNTIME_RECONCILIATION\"
  }" >/dev/null
}

place_order() {
  local symbol="$1"
  local user_id="$2"
  local client_order_id="$3"
  local side="$4"
  local order_type="$5"
  local tif="$6"
  local price_ticks="$7"
  local quantity_steps="$8"
  local reduce_only="$9"
  local post_only="${10}"
  local margin_mode="${11:-CROSS}"
  local response
  response="$(gateway_post "trading" "${user_id}" "live-${RUN_ID}-${client_order_id}" "{
    \"userId\": ${user_id},
    \"clientOrderId\": \"${client_order_id}\",
    \"symbol\": \"${symbol}\",
    \"side\": \"${side}\",
    \"orderType\": \"${order_type}\",
    \"timeInForce\": \"${tif}\",
    \"priceTicks\": ${price_ticks},
    \"quantitySteps\": ${quantity_steps},
    \"marginMode\": \"${margin_mode}\",
    \"positionSide\": \"NET\",
    \"reduceOnly\": ${reduce_only},
    \"postOnly\": ${post_only}
  }")"
  printf '%s\n' "${response}" | json_field orderId
}

wait_order_state() {
  local order_id="$1"
  local status="$2"
  local executed="$3"
  local remaining="$4"
  wait_sql_equals "order ${order_id} ${status}" \
    "SELECT status || ':' || executed_quantity_steps || ':' || remaining_quantity_steps FROM trading_orders WHERE order_id = ${order_id}" \
    "${status}:${executed}:${remaining}"
}

wait_position_flat() {
  local symbol="$1"
  local user_id="$2"
  wait_sql_equals "flat position ${symbol} user=${user_id}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE symbol = '${symbol}' AND user_id = ${user_id}), '0:0')" \
    "0:0"
}

wait_account_balance() {
  local user_id="$1"
  local asset="$2"
  local available="$3"
  local locked="$4"
  wait_sql_equals "account balance user=${user_id} asset=${asset}" \
    "SELECT COALESCE((SELECT available_units || ':' || locked_units FROM account_balances WHERE user_id = ${user_id} AND asset = '${asset}'), '0:0')" \
    "${available}:${locked}"
}

wait_product_balance() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  local available="$4"
  local locked="$5"
  wait_sql_equals "product balance user=${user_id} type=${account_type} asset=${asset}" \
    "SELECT COALESCE((SELECT available_units || ':' || locked_units FROM account_product_balances WHERE user_id = ${user_id} AND account_type = '${account_type}' AND asset = '${asset}'), '0:0')" \
    "${available}:${locked}"
}

assert_account_ledger_matches_balance() {
  local user_id="$1"
  local asset="$2"
  wait_sql_equals "account ledger reconciles user=${user_id} asset=${asset}" \
    "SELECT CASE WHEN COALESCE((SELECT available_units + locked_units FROM account_balances WHERE user_id = ${user_id} AND asset = '${asset}'), 0) = COALESCE((SELECT sum(amount_units) FROM account_ledger_entries WHERE user_id = ${user_id} AND asset = '${asset}'), 0) THEN 1 ELSE 0 END" \
    "1"
}

assert_product_ledger_matches_balance() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  wait_sql_equals "product ledger reconciles user=${user_id} type=${account_type} asset=${asset}" \
    "SELECT CASE WHEN COALESCE((SELECT available_units + locked_units FROM account_product_balances WHERE user_id = ${user_id} AND account_type = '${account_type}' AND asset = '${asset}'), 0) = COALESCE((SELECT sum(amount_units) FROM account_product_ledger_entries WHERE user_id = ${user_id} AND account_type = '${account_type}' AND asset = '${asset}'), 0) THEN 1 ELSE 0 END" \
    "1"
}

assert_global_accounting_invariants() {
  wait_sql_equals "no negative account balances" \
    "SELECT count(*) FROM account_balances WHERE available_units < 0 OR locked_units < 0" \
    "0"
  wait_sql_equals "no negative product balances" \
    "SELECT count(*) FROM account_product_balances WHERE available_units < 0 OR locked_units < 0" \
    "0"
  wait_sql_equals "no over-released margin reservations" \
    "SELECT count(*) FROM account_margin_reservations WHERE released_units + position_margin_units > reserved_units" \
    "0"
  wait_sql_equals "no over-released spot reservations" \
    "SELECT count(*) FROM account_spot_order_reservations WHERE settled_units + released_units > reserved_units" \
    "0"
}

linear_fee() {
  local price_ticks="$1"
  local quantity_steps="$2"
  local notional_multiplier="$3"
  local fee_rate_ppm="$4"
  ceil_div "$((price_ticks * quantity_steps * notional_multiplier * fee_rate_ppm))" 1000000
}

inverse_fee() {
  local price_ticks="$1"
  local quantity_steps="$2"
  local notional_multiplier="$3"
  local price_tick_units="$4"
  local settle_scale_units="$5"
  local fee_rate_ppm="$6"
  python3 - "${price_ticks}" "${quantity_steps}" "${notional_multiplier}" "${price_tick_units}" "${settle_scale_units}" "${fee_rate_ppm}" <<'PY'
import sys

price_ticks, qty, multiplier, tick_units, scale, fee = map(int, sys.argv[1:])
numerator = qty * multiplier * scale * abs(fee)
denominator = price_ticks * tick_units * 1_000_000
print((numerator + denominator - 1) // denominator)
PY
}

inverse_pnl() {
  local signed_qty="$1"
  local entry_ticks="$2"
  local exit_ticks="$3"
  local close_qty="$4"
  local notional_multiplier="$5"
  local price_tick_units="$6"
  local settle_scale_units="$7"
  python3 - "${signed_qty}" "${entry_ticks}" "${exit_ticks}" "${close_qty}" "${notional_multiplier}" "${price_tick_units}" "${settle_scale_units}" <<'PY'
import sys

signed_qty, entry, exit_, close_qty, multiplier, tick_units, scale = map(int, sys.argv[1:])
sign = 1 if signed_qty > 0 else -1
numerator = sign * close_qty * multiplier * scale * (exit_ - entry)
denominator = entry * exit_ * tick_units
absolute = abs(numerator)
quotient, remainder = divmod(absolute, denominator)
if remainder * 2 >= denominator:
    quotient += 1
print((1 if numerator >= 0 else -1) * quotient)
PY
}

run_linear_flow() {
  echo "Scenario: linear perpetual API open/close and exact USDT reconciliation"
  local nm maker_fee taker_fee maker_open maker_close taker_open taker_close entry_ticks exit_ticks
  nm="$(instrument_field "${LINEAR_SYMBOL}" "notional_multiplier_units")"
  maker_fee="$(instrument_field "${LINEAR_SYMBOL}" "maker_fee_rate_ppm")"
  taker_fee="$(instrument_field "${LINEAR_SYMBOL}" "taker_fee_rate_ppm")"
  entry_ticks="$(latest_mark_ticks "${LINEAR_SYMBOL}")"
  if (( entry_ticks <= 0 )); then
    refresh_mark_price "${LINEAR_SYMBOL}" "${LINEAR_ENTRY_TICKS}"
    entry_ticks="$(latest_mark_ticks "${LINEAR_SYMBOL}")"
  fi
  adjust_balance "${LINEAR_SHORT_USER}" "${ACCOUNT_INITIAL_USDT}" "linear-short-${RUN_ID}"
  adjust_balance "${LINEAR_LONG_USER}" "${ACCOUNT_INITIAL_USDT}" "linear-long-${RUN_ID}"

  maker_open="$(place_order "${LINEAR_SYMBOL}" "${LINEAR_SHORT_USER}" "linear-short-open-${RUN_ID}" "SELL" "LIMIT" "GTC" "${entry_ticks}" "${LINEAR_QTY}" false false)"
  taker_open="$(place_order "${LINEAR_SYMBOL}" "${LINEAR_LONG_USER}" "linear-long-open-${RUN_ID}" "BUY" "LIMIT" "IOC" "${entry_ticks}" "${LINEAR_QTY}" false false)"
  wait_order_state "${maker_open}" "FILLED" "${LINEAR_QTY}" "0"
  wait_order_state "${taker_open}" "FILLED" "${LINEAR_QTY}" "0"

  exit_ticks="$(latest_mark_ticks "${LINEAR_SYMBOL}")"
  maker_close="$(place_order "${LINEAR_SYMBOL}" "${LINEAR_SHORT_USER}" "linear-short-close-${RUN_ID}" "BUY" "LIMIT" "GTC" "${exit_ticks}" "${LINEAR_QTY}" true false)"
  taker_close="$(place_order "${LINEAR_SYMBOL}" "${LINEAR_LONG_USER}" "linear-long-close-${RUN_ID}" "SELL" "LIMIT" "IOC" "${exit_ticks}" "${LINEAR_QTY}" true false)"
  wait_order_state "${maker_close}" "FILLED" "${LINEAR_QTY}" "0"
  wait_order_state "${taker_close}" "FILLED" "${LINEAR_QTY}" "0"
  wait_position_flat "${LINEAR_SYMBOL}" "${LINEAR_SHORT_USER}"
  wait_position_flat "${LINEAR_SYMBOL}" "${LINEAR_LONG_USER}"

  local price_diff short_pnl long_pnl short_fees long_fees short_expected long_expected
  price_diff=$((exit_ticks - entry_ticks))
  long_pnl=$((price_diff * LINEAR_QTY * nm))
  short_pnl=$((-long_pnl))
  short_fees=$(( $(linear_fee "${entry_ticks}" "${LINEAR_QTY}" "${nm}" "${maker_fee}") + $(linear_fee "${exit_ticks}" "${LINEAR_QTY}" "${nm}" "${maker_fee}") ))
  long_fees=$(( $(linear_fee "${entry_ticks}" "${LINEAR_QTY}" "${nm}" "${taker_fee}") + $(linear_fee "${exit_ticks}" "${LINEAR_QTY}" "${nm}" "${taker_fee}") ))
  short_expected=$((ACCOUNT_INITIAL_USDT + short_pnl - short_fees))
  long_expected=$((ACCOUNT_INITIAL_USDT + long_pnl - long_fees))

  wait_account_balance "${LINEAR_SHORT_USER}" "USDT" "${short_expected}" "0"
  wait_account_balance "${LINEAR_LONG_USER}" "USDT" "${long_expected}" "0"
  assert_account_ledger_matches_balance "${LINEAR_SHORT_USER}" "USDT"
  assert_account_ledger_matches_balance "${LINEAR_LONG_USER}" "USDT"
  wait_sql_equals "linear close settlement processed" \
    "SELECT count(*) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.symbol = '${LINEAR_SYMBOL}' AND t.maker_user_id = ${LINEAR_SHORT_USER} AND t.taker_user_id = ${LINEAR_LONG_USER}" \
    "2"
  echo "linear entryTicks=${entry_ticks} exitTicks=${exit_ticks} short=${short_expected} long=${long_expected}"
}

run_coin_flow() {
  echo "Scenario: coin-margined perpetual API open/close and exact BTC reconciliation"
  local nm tick_units settle_scale maker_fee taker_fee maker_open taker_open maker_close taker_close
  nm="$(instrument_field "${COIN_SYMBOL}" "notional_multiplier_units")"
  tick_units="$(instrument_field "${COIN_SYMBOL}" "price_tick_units")"
  maker_fee="$(instrument_field "${COIN_SYMBOL}" "maker_fee_rate_ppm")"
  taker_fee="$(instrument_field "${COIN_SYMBOL}" "taker_fee_rate_ppm")"
  settle_scale="$(asset_scale "BTC")"
  refresh_mark_price "${COIN_SYMBOL}" "${COIN_ENTRY_TICKS}"
  adjust_product_balance "${COIN_SHORT_USER}" "COIN_PERPETUAL" "BTC" "${COIN_INITIAL_BTC}" "coin-short-${RUN_ID}"
  adjust_product_balance "${COIN_LONG_USER}" "COIN_PERPETUAL" "BTC" "${COIN_INITIAL_BTC}" "coin-long-${RUN_ID}"

  maker_open="$(place_order "${COIN_SYMBOL}" "${COIN_SHORT_USER}" "coin-short-open-${RUN_ID}" "SELL" "LIMIT" "GTC" "${COIN_ENTRY_TICKS}" "${COIN_QTY}" false false)"
  taker_open="$(place_order "${COIN_SYMBOL}" "${COIN_LONG_USER}" "coin-long-open-${RUN_ID}" "BUY" "LIMIT" "IOC" "${COIN_ENTRY_TICKS}" "${COIN_QTY}" false false)"
  wait_order_state "${maker_open}" "FILLED" "${COIN_QTY}" "0"
  wait_order_state "${taker_open}" "FILLED" "${COIN_QTY}" "0"

  refresh_mark_price "${COIN_SYMBOL}" "${COIN_EXIT_TICKS}"
  maker_close="$(place_order "${COIN_SYMBOL}" "${COIN_SHORT_USER}" "coin-short-close-${RUN_ID}" "BUY" "LIMIT" "GTC" "${COIN_EXIT_TICKS}" "${COIN_QTY}" true false)"
  taker_close="$(place_order "${COIN_SYMBOL}" "${COIN_LONG_USER}" "coin-long-close-${RUN_ID}" "SELL" "LIMIT" "IOC" "${COIN_EXIT_TICKS}" "${COIN_QTY}" true false)"
  wait_order_state "${maker_close}" "FILLED" "${COIN_QTY}" "0"
  wait_order_state "${taker_close}" "FILLED" "${COIN_QTY}" "0"
  wait_position_flat "${COIN_SYMBOL}" "${COIN_SHORT_USER}"
  wait_position_flat "${COIN_SYMBOL}" "${COIN_LONG_USER}"

  local short_pnl long_pnl short_fees long_fees short_expected long_expected
  short_pnl="$(inverse_pnl "-${COIN_QTY}" "${COIN_ENTRY_TICKS}" "${COIN_EXIT_TICKS}" "${COIN_QTY}" "${nm}" "${tick_units}" "${settle_scale}")"
  long_pnl="$(inverse_pnl "${COIN_QTY}" "${COIN_ENTRY_TICKS}" "${COIN_EXIT_TICKS}" "${COIN_QTY}" "${nm}" "${tick_units}" "${settle_scale}")"
  short_fees=$(( $(inverse_fee "${COIN_ENTRY_TICKS}" "${COIN_QTY}" "${nm}" "${tick_units}" "${settle_scale}" "${maker_fee}") + $(inverse_fee "${COIN_EXIT_TICKS}" "${COIN_QTY}" "${nm}" "${tick_units}" "${settle_scale}" "${maker_fee}") ))
  long_fees=$(( $(inverse_fee "${COIN_ENTRY_TICKS}" "${COIN_QTY}" "${nm}" "${tick_units}" "${settle_scale}" "${taker_fee}") + $(inverse_fee "${COIN_EXIT_TICKS}" "${COIN_QTY}" "${nm}" "${tick_units}" "${settle_scale}" "${taker_fee}") ))
  short_expected=$((COIN_INITIAL_BTC + short_pnl - short_fees))
  long_expected=$((COIN_INITIAL_BTC + long_pnl - long_fees))

  wait_product_balance "${COIN_SHORT_USER}" "COIN_PERPETUAL" "BTC" "${short_expected}" "0"
  wait_product_balance "${COIN_LONG_USER}" "COIN_PERPETUAL" "BTC" "${long_expected}" "0"
  assert_product_ledger_matches_balance "${COIN_SHORT_USER}" "COIN_PERPETUAL" "BTC"
  assert_product_ledger_matches_balance "${COIN_LONG_USER}" "COIN_PERPETUAL" "BTC"
  wait_sql_equals "coin trades processed by account settlement" \
    "SELECT count(*) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.symbol = '${COIN_SYMBOL}' AND t.maker_user_id = ${COIN_SHORT_USER} AND t.taker_user_id = ${COIN_LONG_USER}" \
    "2"
  echo "coin short=${short_expected} long=${long_expected}"
}

run_spot_flow() {
  echo "Scenario: spot API trade and exact product balance reconciliation"
  local nm quantity_step maker_fee taker_fee sell_order buy_order
  nm="$(instrument_field "${SPOT_SYMBOL}" "notional_multiplier_units")"
  quantity_step="$(instrument_field "${SPOT_SYMBOL}" "quantity_step_units")"
  maker_fee="$(instrument_field "${SPOT_SYMBOL}" "maker_fee_rate_ppm")"
  taker_fee="$(instrument_field "${SPOT_SYMBOL}" "taker_fee_rate_ppm")"
  refresh_mark_price "${SPOT_SYMBOL}" "${SPOT_PRICE_TICKS}"
  adjust_product_balance "${SPOT_SELLER_USER}" "SPOT" "BTC" "${SPOT_INITIAL_BTC}" "spot-seller-btc-${RUN_ID}"
  adjust_product_balance "${SPOT_BUYER_USER}" "SPOT" "USDT" "${SPOT_INITIAL_USDT}" "spot-buyer-usdt-${RUN_ID}"

  sell_order="$(place_order "${SPOT_SYMBOL}" "${SPOT_SELLER_USER}" "spot-seller-${RUN_ID}" "SELL" "LIMIT" "GTC" "${SPOT_PRICE_TICKS}" "${SPOT_QTY}" false false)"
  buy_order="$(place_order "${SPOT_SYMBOL}" "${SPOT_BUYER_USER}" "spot-buyer-${RUN_ID}" "BUY" "LIMIT" "IOC" "${SPOT_PRICE_TICKS}" "${SPOT_QTY}" false false)"
  wait_order_state "${sell_order}" "FILLED" "${SPOT_QTY}" "0"
  wait_order_state "${buy_order}" "FILLED" "${SPOT_QTY}" "0"

  local base_units quote_units seller_fee buyer_fee seller_btc seller_usdt buyer_btc buyer_usdt
  base_units=$((SPOT_QTY * quantity_step))
  quote_units=$((SPOT_PRICE_TICKS * SPOT_QTY * nm))
  seller_fee="$(ceil_div "$((quote_units * maker_fee))" 1000000)"
  buyer_fee="$(ceil_div "$((quote_units * taker_fee))" 1000000)"
  seller_btc=$((SPOT_INITIAL_BTC - base_units))
  seller_usdt=$((quote_units - seller_fee))
  buyer_btc="${base_units}"
  buyer_usdt=$((SPOT_INITIAL_USDT - quote_units - buyer_fee))

  wait_product_balance "${SPOT_SELLER_USER}" "SPOT" "BTC" "${seller_btc}" "0"
  wait_product_balance "${SPOT_SELLER_USER}" "SPOT" "USDT" "${seller_usdt}" "0"
  wait_product_balance "${SPOT_BUYER_USER}" "SPOT" "BTC" "${buyer_btc}" "0"
  wait_product_balance "${SPOT_BUYER_USER}" "SPOT" "USDT" "${buyer_usdt}" "0"
  assert_product_ledger_matches_balance "${SPOT_SELLER_USER}" "SPOT" "BTC"
  assert_product_ledger_matches_balance "${SPOT_SELLER_USER}" "SPOT" "USDT"
  assert_product_ledger_matches_balance "${SPOT_BUYER_USER}" "SPOT" "BTC"
  assert_product_ledger_matches_balance "${SPOT_BUYER_USER}" "SPOT" "USDT"
  wait_sql_equals "spot reservations settled exactly" \
    "SELECT count(*) FROM account_spot_order_reservations WHERE order_id IN (${sell_order}, ${buy_order}) AND status = 'SETTLED' AND settled_units + released_units = reserved_units" \
    "2"
  wait_sql_equals "spot did not create perpetual positions" \
    "SELECT count(*) FROM account_positions WHERE symbol = '${SPOT_SYMBOL}' AND user_id IN (${SPOT_SELLER_USER}, ${SPOT_BUYER_USER})" \
    "0"
  wait_sql_equals "spot trade processed by account settlement" \
    "SELECT count(*) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.symbol = '${SPOT_SYMBOL}' AND t.maker_user_id = ${SPOT_SELLER_USER} AND t.taker_user_id = ${SPOT_BUYER_USER}" \
    "1"
  echo "spot seller_btc=${seller_btc} seller_usdt=${seller_usdt} buyer_btc=${buyer_btc} buyer_usdt=${buyer_usdt}"
}

run_market_maker_flow() {
  echo "Scenario: market-maker run-once places real post-only quotes"
  local trace_id="live-${RUN_ID}-market-maker"
  refresh_mark_price "${MM_SYMBOL}" "$(instrument_field "${MM_SYMBOL}" "CASE WHEN price_tick_units = 10000000 THEN 600000 ELSE 600000 END")"
  gateway_post "market-maker/run-once" 1 "${trace_id}" "{
    \"strategyId\": \"${MM_STRATEGY_ID}\",
    \"symbol\": \"${MM_SYMBOL}\"
  }" >/dev/null
  wait_sql_nonzero "market-maker run event trace=${trace_id}" \
    "SELECT count(*) FROM market_maker_strategy_run_events WHERE trace_id = '${trace_id}' AND strategy_id = '${MM_STRATEGY_ID}' AND symbol = '${MM_SYMBOL}' AND event_type = 'QUOTE_RECONCILED' AND submitted_orders > 0 AND rejected_orders = 0"
  wait_sql_nonzero "market-maker accepted post-only orders trace=${trace_id}" \
    "SELECT count(DISTINCT o.order_id) FROM trading_orders o JOIN trading_order_events e ON e.order_id = o.order_id WHERE e.trace_id = '${trace_id}' AND o.symbol = '${MM_SYMBOL}' AND o.client_order_id LIKE 'mm-%' AND o.post_only = true AND o.time_in_force = 'GTX'"
  echo "market-maker trace=${trace_id}"
}

main() {
  if ! command -v "$(kafka_producer_cmd)" >/dev/null 2>&1; then
    echo "Missing Kafka console producer" >&2
    exit 1
  fi
  require_health instrument 9080
  require_health trading-entry 9084
  require_health matching 9085
  require_health account 9086
  require_health margin-ops 9088
  require_health gateway 9094
  require_health market-maker 9096

  echo "runId=${RUN_ID} productLine=${PRODUCT_LINE}"
  case "${PRODUCT_LINE}" in
    LINEAR_PERPETUAL)
      run_linear_flow
      run_market_maker_flow
      ;;
    INVERSE_PERPETUAL)
      run_coin_flow
      ;;
    SPOT)
      run_spot_flow
      ;;
    *)
      echo "Unsupported PRODUCT_LINE=${PRODUCT_LINE}" >&2
      exit 1
      ;;
  esac
  assert_global_accounting_invariants
  echo "Live runtime trading reconciliation passed"
}

main "$@"
