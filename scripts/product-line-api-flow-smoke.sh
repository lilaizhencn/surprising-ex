#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="${DB_NAME:-surprising_product_line_smoke}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/${DB_NAME}}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
PRODUCT_LINES="${PRODUCT_LINES:-LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT}"
BUILD_SERVICES="${BUILD_SERVICES:-true}"
KEEP_TMP="${KEEP_TMP:-true}"
RESET_KAFKA="${RESET_KAFKA:-false}"
CREATE_KAFKA_TOPICS="${CREATE_KAFKA_TOPICS:-false}"
RECONCILE_FUNDS="${RECONCILE_FUNDS:-true}"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
RUN_SEQUENCE_BASE=$((500000000 + (RUN_SEQ % 500000) * 3000))
TMP_DIR="$(mktemp -d /tmp/surprising-product-line-api.XXXXXX)"

BASE_USER=$((6000000000 + RUN_SEQ * 1000))
MM_USER_A=$((BASE_USER + 1))
MM_USER_B=$((BASE_USER + 2))
TAKER_USER=$((BASE_USER + 10))
MANUAL_MAKER_USER=$((BASE_USER + 20))
NO_FUNDS_USER=$((BASE_USER + 30))
CANCEL_USER=$((BASE_USER + 40))
AMEND_USER=$((BASE_USER + 50))
SELF_TRADE_USER=$((BASE_USER + 60))
POST_ONLY_USER=$((BASE_USER + 70))
LIQ_USER=$((BASE_USER + 80))
LIQ_MAKER_USER=$((BASE_USER + 90))
LIFECYCLE_USER=$((BASE_USER + 100))
LIFECYCLE_MAKER_USER=$((BASE_USER + 110))
FUNDING_USER=$((BASE_USER + 120))
FUNDING_MAKER_USER=$((BASE_USER + 130))

PROVIDER_NAMES=()
PROVIDER_PIDS=()

cleanup() {
  local i
  for ((i = 0; i < ${#PROVIDER_PIDS[@]}; i++)); do
    local pid="${PROVIDER_PIDS[$i]}"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      kill "${pid}" >/dev/null 2>&1 || true
    fi
  done
  for ((i = 0; i < ${#PROVIDER_PIDS[@]}; i++)); do
    local pid="${PROVIDER_PIDS[$i]}"
    [[ -n "${pid}" ]] && wait "${pid}" >/dev/null 2>&1 || true
  done
  if [[ "${KEEP_TMP}" == "true" ]]; then
    echo "Keeping product-line smoke logs in ${TMP_DIR}" >&2
  else
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT

stop_provider_by_name() {
  local target="$1"
  local i
  for ((i = 0; i < ${#PROVIDER_PIDS[@]}; i++)); do
    if [[ "${PROVIDER_NAMES[$i]}" != "${target}" ]]; then
      continue
    fi
    local pid="${PROVIDER_PIDS[$i]}"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      kill "${pid}" >/dev/null 2>&1 || true
      wait "${pid}" >/dev/null 2>&1 || true
    fi
    PROVIDER_PIDS[$i]=""
  done
}

stop_all_providers() {
  local i
  for ((i = 0; i < ${#PROVIDER_PIDS[@]}; i++)); do
    local pid="${PROVIDER_PIDS[$i]}"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      kill "${pid}" >/dev/null 2>&1 || true
    fi
  done
  for ((i = 0; i < ${#PROVIDER_PIDS[@]}; i++)); do
    local pid="${PROVIDER_PIDS[$i]}"
    [[ -n "${pid}" ]] && wait "${pid}" >/dev/null 2>&1 || true
  done
  PROVIDER_NAMES=()
  PROVIDER_PIDS=()
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

kafka_topics_cmd() {
  if command -v kafka-topics >/dev/null 2>&1; then
    echo kafka-topics
  else
    echo kafka-topics.sh
  fi
}

kafka_producer_cmd() {
  if command -v kafka-console-producer >/dev/null 2>&1; then
    echo kafka-console-producer
  else
    echo kafka-console-producer.sh
  fi
}

psql_exec() {
  PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${POSTGRES_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
    -v ON_ERROR_STOP=1 "$@"
}

postgres_exec() {
  PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${POSTGRES_PORT}" -U "${DB_USER}" -d postgres \
    -v ON_ERROR_STOP=1 "$@"
}

query_value() {
  local sql="$1"
  psql_exec -At -c "${sql}"
}

wait_until() {
  local description="$1"
  local timeout_seconds="$2"
  shift 2
  local deadline=$((SECONDS + timeout_seconds))
  until "$@"; do
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${description}" >&2
      exit 1
    fi
    sleep 1
  done
}

wait_sql_equals() {
  local description="$1"
  local sql="$2"
  local expected="$3"
  local timeout="${4:-180}"
  local deadline=$((SECONDS + timeout))
  local actual
  while true; do
    actual="$(query_value "${sql}" || true)"
    if [[ "${actual}" == "${expected}" ]]; then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${description}: expected '${expected}', got '${actual}'" >&2
      exit 1
    fi
    sleep 1
  done
}

wait_sql_nonzero() {
  local description="$1"
  local sql="$2"
  local timeout="${3:-180}"
  local deadline=$((SECONDS + timeout))
  local actual
  while true; do
    actual="$(query_value "${sql}" || true)"
    if [[ "${actual}" =~ ^[0-9]+$ ]] && ((actual > 0)); then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${description}: got '${actual}'" >&2
      exit 1
    fi
    sleep 1
  done
}

wait_http() {
  local name="$1"
  local port="$2"
  local log_file="${TMP_DIR}/${name}.log"
  local deadline=$((SECONDS + 180))
  until curl -fsS "http://localhost:${port}/actuator/health" | grep -q 'UP'; do
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${name} health on port ${port}" >&2
      tail -n 160 "${log_file}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

product_slug() {
  case "$1" in
    SPOT) echo "spot" ;;
    LINEAR_PERPETUAL) echo "linear-perp" ;;
    INVERSE_PERPETUAL) echo "inverse-perp" ;;
    LINEAR_DELIVERY) echo "linear-delivery" ;;
    INVERSE_DELIVERY) echo "inverse-delivery" ;;
    OPTION) echo "option" ;;
    *) echo "Unsupported product line: $1" >&2; exit 1 ;;
  esac
}

topic_prefix() {
  echo "surprising.$(product_slug "$1")"
}

topic_name() {
  local product_line="$1"
  local event_name="$2"
  echo "$(topic_prefix "${product_line}").${event_name}.v1"
}

consumer_group() {
  local product_line="$1"
  local service="$2"
  echo "surprising-$(product_slug "${product_line}")-${service}-v1"
}

account_type() {
  case "$1" in
    SPOT) echo "SPOT" ;;
    LINEAR_PERPETUAL) echo "USDT_PERPETUAL" ;;
    INVERSE_PERPETUAL) echo "COIN_PERPETUAL" ;;
    LINEAR_DELIVERY) echo "USDT_DELIVERY" ;;
    INVERSE_DELIVERY) echo "COIN_DELIVERY" ;;
    OPTION) echo "OPTION" ;;
    *) echo "Unsupported product line: $1" >&2; exit 1 ;;
  esac
}

contract_type() {
  case "$1" in
    SPOT) echo "SPOT" ;;
    LINEAR_PERPETUAL) echo "LINEAR_PERPETUAL" ;;
    INVERSE_PERPETUAL) echo "INVERSE_PERPETUAL" ;;
    LINEAR_DELIVERY) echo "LINEAR_DELIVERY" ;;
    INVERSE_DELIVERY) echo "INVERSE_DELIVERY" ;;
    OPTION) echo "VANILLA_OPTION" ;;
    *) echo "Unsupported product line: $1" >&2; exit 1 ;;
  esac
}

symbol_for() {
  case "$1" in
    SPOT) echo "BTC-USDT-SPOT" ;;
    LINEAR_PERPETUAL) echo "BTC-USDT" ;;
    LINEAR_DELIVERY) echo "BTC-USDT-260925" ;;
    OPTION) echo "BTC-USDT-260925-59000-C" ;;
    *) echo "Unsupported product line: $1" >&2; exit 1 ;;
  esac
}

instrument_type_for() {
  case "$1" in
    SPOT) echo "SPOT" ;;
    LINEAR_PERPETUAL) echo "PERPETUAL" ;;
    LINEAR_DELIVERY) echo "DELIVERY" ;;
    OPTION) echo "OPTION" ;;
    *) echo "Unsupported product line: $1" >&2; exit 1 ;;
  esac
}

is_spot() {
  [[ "$1" == "SPOT" ]]
}

is_margin_product() {
  [[ "$1" != "SPOT" ]]
}

is_funding_product() {
  [[ "$1" == "LINEAR_PERPETUAL" || "$1" == "INVERSE_PERPETUAL" ]]
}

is_delivery_product() {
  [[ "$1" == "LINEAR_DELIVERY" || "$1" == "INVERSE_DELIVERY" ]]
}

is_option_product() {
  [[ "$1" == "OPTION" ]]
}

base_asset_for() {
  case "$1" in
    SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) echo "BTC" ;;
    INVERSE_PERPETUAL|INVERSE_DELIVERY) echo "BTC" ;;
  esac
}

quote_asset_for() {
  case "$1" in
    INVERSE_PERPETUAL|INVERSE_DELIVERY) echo "USD" ;;
    *) echo "USDT" ;;
  esac
}

settle_asset_for() {
  case "$1" in
    INVERSE_PERPETUAL|INVERSE_DELIVERY) echo "BTC" ;;
    *) echo "USDT" ;;
  esac
}

price_ticks_for() {
  case "$1" in
    OPTION) echo 1000 ;;
    *) echo 600000 ;;
  esac
}

price_with_offset() {
  local product_line="$1"
  local base_price="$2"
  local offset="$3"
  local normalized_offset
  if is_option_product "${product_line}"; then
    normalized_offset=$((offset / 100))
    if ((normalized_offset == 0 && offset != 0)); then
      if ((offset > 0)); then
        normalized_offset=1
      else
        normalized_offset=-1
      fi
    fi
  else
    normalized_offset="${offset}"
  fi
  echo $((base_price + normalized_offset))
}

price_tick_units_for() {
  echo 10000000
}

quantity_step_units_for() {
  case "$1" in
    SPOT) echo 100000 ;;
    *) echo 100000 ;;
  esac
}

settle_funding_amount() {
  case "$1" in
    USDT) echo 200000000000000 ;;
    BTC) echo 100000000000 ;;
    *) echo 200000000000000 ;;
  esac
}

liquidation_test_margin_amount() {
  case "$1" in
    USDT|USD) echo 150000000 ;;
    BTC) echo 100000000 ;;
    *) echo 150000000 ;;
  esac
}

ensure_database() {
  if [[ ! "${DB_NAME}" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "DB_NAME must contain only letters, numbers, and underscore: ${DB_NAME}" >&2
    exit 1
  fi
  local exists
  exists="$(postgres_exec -At -c "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'" || true)"
  if [[ "${exists}" != "1" ]]; then
    postgres_exec -c "CREATE DATABASE ${DB_NAME}" >/dev/null
  fi
}

reset_database() {
  psql_exec <<'SQL' >/dev/null
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
SQL
  psql_exec -f "${ROOT_DIR}/init.sql" >/dev/null
  reset_runtime_sequences
  seed_product_instruments
}

reset_runtime_sequences() {
  psql_exec <<SQL >/dev/null
ALTER SEQUENCE IF EXISTS trading_order_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_command_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_event_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_outbox_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_margin_reservation_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_spot_reservation_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_match_trade_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_orderbook_depth_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_fee_schedule_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_matching_asset_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_matching_symbol_seq RESTART WITH ${RUN_SEQUENCE_BASE};
SQL
}

seed_product_instruments() {
  psql_exec <<SQL >/dev/null
INSERT INTO instruments (
    symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,
    contract_multiplier_ppm, contract_value_asset, price_tick_units, quantity_step_units,
    min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,
    notional_multiplier_units, price_precision, quantity_precision,
    supported_order_types, supported_time_in_force, post_only_enabled, reduce_only_enabled, market_order_enabled,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm,
    maker_fee_rate_ppm, taker_fee_rate_ppm, max_position_notional_units,
    user_open_interest_limit_rate_ppm, user_open_interest_limit_floor_units,
    funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,
    impact_notional_units, min_valid_index_sources,
    expiry_time, delivery_time, underlying_symbol, strike_price_units, option_type, option_exercise_style,
    settlement_method, status, effective_time, created_at, updated_at
) VALUES
('BTC-USDT-260925', 1, 'DELIVERY', 'LINEAR_DELIVERY', 'BTC', 'USDT', 'USDT',
 1000000, 'USDT', 10000000, 100000, 1, 100000, 1, 1000000000000000,
 10000, 1, 3, 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 1000000000000000000, 1000000, 1000000000000000000,
 0, 0, 0, 0, 1000000000000, 1,
 now() + interval '30 days', now() + interval '30 days', NULL, NULL, NULL, NULL,
 'CASH', 'TRADING', now(), now(), now()),
('BTC-USDT-260925-59000-C', 1, 'OPTION', 'VANILLA_OPTION', 'BTC', 'USDT', 'USDT',
 1000000, 'USDT', 10000000, 100000, 1, 100000, 1, 1000000000000000,
 10000, 1, 3, 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 1000000000000000000, 1000000, 1000000000000000000,
 0, 0, 0, 0, 1000000000000, 1,
 now() + interval '30 days', now() + interval '30 days', 'BTC-USDT', 5900000000000, 'CALL', 'EUROPEAN',
 'CASH', 'TRADING', now(), now(), now()),
('BTC-USDT-SPOT', 1, 'SPOT', 'SPOT', 'BTC', 'USDT', 'USDT',
 1000000, 'USDT', 10000000, 100000, 1, 100000, 1, 1000000000000000,
 10000, 1, 3, 'LIMIT', 'GTC,IOC,FOK,GTX', TRUE, FALSE, FALSE,
 1000000, 1000000, 1000000, 200, 500, 1000000000000000, 0, 1,
 0, 0, 0, 0, 1000000000000, 1,
 NULL, NULL, NULL, NULL, NULL, NULL, NULL,
 'TRADING', now(), now(), now())
ON CONFLICT (symbol, version) DO UPDATE SET
    instrument_type = EXCLUDED.instrument_type,
    contract_type = EXCLUDED.contract_type,
    base_asset = EXCLUDED.base_asset,
    quote_asset = EXCLUDED.quote_asset,
    settle_asset = EXCLUDED.settle_asset,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO instrument_current_versions (symbol, version, updated_at)
VALUES
('BTC-USDT-260925', 1, now()),
('BTC-USDT-260925-59000-C', 1, now()),
('BTC-USDT-SPOT', 1, now())
ON CONFLICT (symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_product_current_versions (product_line, symbol, version, updated_at)
VALUES
('LINEAR_PERPETUAL', 'BTC-USDT', 1, now()),
('LINEAR_PERPETUAL', 'ETH-USDT', 1, now()),
('LINEAR_DELIVERY', 'BTC-USDT-260925', 1, now()),
('OPTION', 'BTC-USDT-260925-59000-C', 1, now()),
('SPOT', 'BTC-USDT-SPOT', 1, now())
ON CONFLICT (product_line, symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_symbol_sequences (symbol, version, updated_at)
VALUES
('BTC-USDT-260925', 1, now()),
('BTC-USDT-260925-59000-C', 1, now()),
('BTC-USDT-SPOT', 1, now())
ON CONFLICT (symbol) DO UPDATE SET version = GREATEST(instrument_symbol_sequences.version, EXCLUDED.version),
    updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_risk_brackets (
    symbol, version, bracket_no, notional_floor_units, notional_cap_units,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
) VALUES
('BTC-USDT-260925', 1, 1, 0, 1000000000000000000, 100000000, 10000, 5000),
('BTC-USDT-260925-59000-C', 1, 1, 0, 1000000000000000000, 100000000, 10000, 5000),
('BTC-USDT-SPOT', 1, 1, 0, 1000000000000000000, 1000000, 1000000, 1000000)
ON CONFLICT (symbol, version, bracket_no) DO NOTHING;
SQL
}

delete_surprising_topics() {
  local topics_cmd
  topics_cmd="$(kafka_topics_cmd)"
  local topics
  topics="$("${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null | grep '^surprising\.' || true)"
  if [[ -z "${topics}" ]]; then
    return
  fi
  while IFS= read -r topic; do
    [[ -n "${topic}" ]] || continue
    "${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --delete --if-exists --topic "${topic}" >/dev/null 2>&1 || true
  done <<<"${topics}"
}

create_topics() {
  local product_line="$1"
  local product_topic_line
  product_topic_line="$(product_slug "${product_line}")"
  BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    REPLICATION_FACTOR=1 \
    INCLUDE_PRODUCT_TOPICS=true \
    PRODUCT_TOPIC_LINES="${product_topic_line}" \
    "${ROOT_DIR}/scripts/create-topics.sh" >/dev/null
}

reset_kafka_topics() {
  local product_line="$1"
  if [[ "${RESET_KAFKA}" == "true" ]]; then
    delete_surprising_topics
    sleep 2
  fi
  if [[ "${CREATE_KAFKA_TOPICS}" == "true" ]]; then
    create_topics "${product_line}"
  fi
}

boot_jar() {
  local module_path="$1"
  local artifact="$2"
  local jar
  jar="$(find "${ROOT_DIR}/${module_path}/target" -name "${artifact}-*-exec.jar" -type f | sort | tail -n 1)"
  if [[ -z "${jar}" ]]; then
    return 1
  fi
  echo "${jar}"
}

provider_module() {
  case "$1" in
    instrument) echo "surprising-instrument/surprising-instrument-provider" ;;
    mark-price) echo "surprising-price/surprising-mark-price-provider" ;;
    order) echo "surprising-trading/surprising-order-provider" ;;
    matching) echo "surprising-trading/surprising-matching-provider" ;;
    account) echo "surprising-account/surprising-account-provider" ;;
    risk) echo "surprising-risk/surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation/surprising-liquidation-provider" ;;
    insurance) echo "surprising-insurance/surprising-insurance-provider" ;;
    funding) echo "surprising-funding/surprising-funding-provider" ;;
    gateway) echo "surprising-gateway/surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker/surprising-market-maker-provider" ;;
    *) echo "unknown provider: $1" >&2; exit 1 ;;
  esac
}

provider_artifact() {
  case "$1" in
    instrument) echo "surprising-instrument-provider" ;;
    mark-price) echo "surprising-mark-price-provider" ;;
    order) echo "surprising-order-provider" ;;
    matching) echo "surprising-matching-provider" ;;
    account) echo "surprising-account-provider" ;;
    risk) echo "surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation-provider" ;;
    insurance) echo "surprising-insurance-provider" ;;
    funding) echo "surprising-funding-provider" ;;
    gateway) echo "surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker-provider" ;;
    *) echo "unknown provider: $1" >&2; exit 1 ;;
  esac
}

provider_port() {
  case "$1" in
    instrument) echo 9080 ;;
    mark-price) echo 9083 ;;
    order) echo 9084 ;;
    matching) echo 9085 ;;
    account) echo 9086 ;;
    risk) echo 9087 ;;
    liquidation) echo 9088 ;;
    insurance) echo 9090 ;;
    funding) echo 9089 ;;
    gateway) echo 9094 ;;
    market-maker) echo 9096 ;;
    *) echo "unknown provider: $1" >&2; exit 1 ;;
  esac
}

package_services() {
  if [[ "${BUILD_SERVICES}" == "false" ]]; then
    echo "Skipping provider package build (BUILD_SERVICES=false)"
    return
  fi
  local missing=()
  local name
  for name in instrument mark-price order matching account risk liquidation insurance funding gateway market-maker; do
    if ! boot_jar "$(provider_module "${name}")" "$(provider_artifact "${name}")" >/dev/null 2>&1; then
      missing+=(":$(provider_artifact "${name}")")
    fi
  done
  if [[ "${BUILD_SERVICES}" == "auto" && ${#missing[@]} -eq 0 ]]; then
    echo "Provider jars found; skipping Maven package (BUILD_SERVICES=auto)"
    return
  fi
  local selectors
  if [[ "${BUILD_SERVICES}" == "true" ]]; then
    selectors=":surprising-instrument-provider,:surprising-mark-price-provider,:surprising-order-provider,:surprising-matching-provider,:surprising-account-provider,:surprising-risk-provider,:surprising-liquidation-provider,:surprising-insurance-provider,:surprising-funding-provider,:surprising-gateway-provider,:surprising-market-maker-provider"
  else
    local IFS=,
    selectors="${missing[*]}"
  fi
  mvn -q -pl "${selectors}" -am -DskipTests package
}

register_provider_pid() {
  PROVIDER_NAMES+=("$1")
  PROVIDER_PIDS+=("$2")
}

matching_java_args() {
  printf '%s\n' \
    "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED" \
    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED" \
    "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED" \
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED" \
    "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED" \
    "--add-opens=java.base/java.lang=ALL-UNNAMED" \
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" \
    "--add-opens=java.base/java.io=ALL-UNNAMED" \
    "--add-opens=java.base/java.util=ALL-UNNAMED" \
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED" \
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
}

product_provider_args() {
  local name="$1"
  local product_line="$2"
  local slug
  slug="$(product_slug "${product_line}")"
  case "${name}" in
    order)
      printf '%s\n' \
        "--surprising.trading.order.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.trading.order.kafka.product-line=${product_line}" \
        "--surprising.trading.order.kafka.product-topics-enabled=true" \
        "--surprising.trading.order.risk.market-max-mark-age-ms=30000"
      ;;
    matching)
      printf '%s\n' \
        "--surprising.trading.matching.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.trading.matching.kafka.product-line=${product_line}" \
        "--surprising.trading.matching.kafka.product-topics-enabled=true" \
        "--surprising.trading.matching.kafka.group-id=product-smoke-${RUN_ID}-${slug}-matching" \
        "--surprising.trading.matching.kafka.client-id=product-smoke-${RUN_ID}-${slug}-matching" \
        "--surprising.trading.matching.engine.exchange-id=product-smoke-${slug}"
      ;;
    account)
      printf '%s\n' \
        "--surprising.account.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.account.kafka.product-line=${product_line}" \
        "--surprising.account.kafka.product-topics-enabled=true" \
        "--surprising.account.kafka.group-id=product-smoke-${RUN_ID}-${slug}-account" \
        "--surprising.account.kafka.client-id=product-smoke-${RUN_ID}-${slug}-account" \
        "--surprising.account.kafka.concurrency=1" \
        "--surprising.account.expiring-settlement.settlement-price-window=PT0S"
      ;;
    risk)
      printf '%s\n' \
        "--surprising.risk.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.risk.kafka.product-line=${product_line}" \
        "--surprising.risk.kafka.product-topics-enabled=true" \
        "--surprising.risk.kafka.group-id=product-smoke-${RUN_ID}-${slug}-risk" \
        "--surprising.risk.kafka.concurrency=1" \
        "--surprising.risk.coordination.node-id=product-smoke-${RUN_ID}-${slug}-risk" \
        "--surprising.risk.calculation.scan-delay-ms=500"
      ;;
    liquidation)
      printf '%s\n' \
        "--surprising.liquidation.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.liquidation.kafka.product-line=${product_line}" \
        "--surprising.liquidation.kafka.product-topics-enabled=true" \
        "--surprising.liquidation.kafka.group-id=product-smoke-${RUN_ID}-${slug}-liquidation" \
        "--surprising.liquidation.kafka.concurrency=1"
      ;;
    insurance)
      printf '%s\n' \
        "--surprising.insurance.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.insurance.kafka.product-line=${product_line}" \
        "--surprising.insurance.kafka.product-topics-enabled=false" \
        "--surprising.insurance.kafka.liquidation-fee-events-topic=$(topic_name "${product_line}" "account.liquidation-fee.events")" \
        "--surprising.insurance.kafka.group-id=product-smoke-${RUN_ID}-${slug}-insurance" \
        "--surprising.insurance.kafka.concurrency=1" \
        "--surprising.insurance.coverage.scan-delay-ms=500"
      ;;
    funding)
      printf '%s\n' \
        "--surprising.funding.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.funding.kafka.product-line=${product_line}" \
        "--surprising.funding.kafka.product-topics-enabled=true" \
        "--surprising.funding.coordination.node-id=product-smoke-${RUN_ID}-${slug}-funding" \
        "--surprising.funding.calculation.enabled=false" \
        "--surprising.funding.settlement.enabled=true" \
        "--surprising.funding.settlement.scan-delay-ms=500"
      ;;
    mark-price)
      printf '%s\n' \
        "--surprising.price.mark.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.price.mark.kafka.product-line=${product_line}" \
        "--surprising.price.mark.kafka.product-topics-enabled=true" \
        "--surprising.price.mark.kafka.group-id=product-smoke-${RUN_ID}-${slug}-mark-price" \
        "--spring.kafka.listener.auto-startup=false"
      ;;
    market-maker)
      local symbol
      symbol="$(symbol_for "${product_line}")"
      printf '%s\n' \
        "--surprising.clients.account.base-url=http://localhost:9086" \
        "--surprising.clients.instrument.base-url=http://localhost:9080" \
        "--surprising.clients.mark-price.base-url=http://localhost:9083" \
        "--surprising.clients.matching.base-url=http://localhost:9085" \
        "--surprising.clients.order.base-url=http://localhost:9084" \
        "--surprising.market-maker.engine.enabled=true" \
        "--surprising.market-maker.engine.cycle-delay-ms=250" \
        "--surprising.market-maker.engine.node-id=product-smoke-${RUN_ID}-${slug}-mm" \
        "--surprising.market-maker.coordination.enabled=true" \
        "--surprising.market-maker.quoting.order-levels=2" \
        "--surprising.market-maker.quoting.min-spread-ticks=20" \
        "--surprising.market-maker.quoting.level-spacing-ticks=10" \
        "--surprising.market-maker.quoting.max-open-orders-per-account-symbol=12" \
        "--surprising.market-maker.trade.enabled=false" \
        "--surprising.market-maker.strategies[0].strategy-id=product-smoke-mm" \
        "--surprising.market-maker.strategies[0].product-line=${product_line}" \
        "--surprising.market-maker.strategies[0].enabled=true" \
        "--surprising.market-maker.strategies[0].account-ids[0]=${MM_USER_A}" \
        "--surprising.market-maker.strategies[0].account-ids[1]=${MM_USER_B}" \
        "--surprising.market-maker.strategies[0].symbols[0]=${symbol}" \
        "--surprising.market-maker.strategies[0].base-quantity-steps=2" \
        "--surprising.market-maker.strategies[0].margin-mode=CROSS" \
        "--surprising.market-maker.strategies[0].order-levels=2"
      ;;
  esac
}

start_provider() {
  local name="$1"
  local product_line="$2"
  local port module artifact jar log_file
  port="$(provider_port "${name}")"
  module="$(provider_module "${name}")"
  artifact="$(provider_artifact "${name}")"
  jar="$(boot_jar "${module}" "${artifact}")"
  log_file="${TMP_DIR}/${product_line}-${name}.log"
  local java_args=()
  local app_args=("--server.port=${port}")
  local arg
  if [[ "${name}" == "matching" ]]; then
    while IFS= read -r arg; do
      java_args+=("${arg}")
    done < <(matching_java_args)
  fi
  while IFS= read -r arg; do
    [[ -n "${arg}" ]] && app_args+=("${arg}")
  done < <(product_provider_args "${name}" "${product_line}")
  echo "Starting ${product_line} ${name} on port ${port}"
  (
    cd "${ROOT_DIR}"
    exec env \
      "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}" \
      "SPRING_DATASOURCE_USERNAME=${DB_USER}" \
      "SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}" \
      java ${java_args[@]+"${java_args[@]}"} -jar "${jar}" "${app_args[@]}"
  ) >"${log_file}" 2>&1 &
  register_provider_pid "${name}" "$!"
  wait_http "${product_line}-${name}" "${port}"
}

start_providers_for_line() {
  local product_line="$1"
  start_provider instrument "${product_line}"
  start_provider mark-price "${product_line}"
  start_provider matching "${product_line}"
  start_provider account "${product_line}"
  if is_margin_product "${product_line}"; then
    start_provider risk "${product_line}"
    start_provider liquidation "${product_line}"
    start_provider insurance "${product_line}"
    if is_funding_product "${product_line}"; then
      start_provider funding "${product_line}"
    fi
  fi
  start_provider order "${product_line}"
  start_provider gateway "${product_line}"
  start_provider market-maker "${product_line}"
}

start_price_refresher() {
  local product_line="$1"
  local log_file="${TMP_DIR}/${product_line}-price-refresher.log"
  echo "Starting ${product_line} synthetic mark-price refresher"
  (
    while true; do
      seed_prices_for_line "${product_line}" >/dev/null 2>&1 || true
      sleep 5
    done
  ) >"${log_file}" 2>&1 &
  register_provider_pid "price-refresher" "$!"
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

insert_mark_price() {
  local symbol="$1"
  local tick_units="$2"
  local sequence="$3"
  local price_ticks="$4"
  local price bid ask units
  price="$(decimal_price "${price_ticks}" "${tick_units}")"
  bid="$(decimal_price "$((price_ticks - 1))" "${tick_units}")"
  ask="$(decimal_price "$((price_ticks + 1))" "${tick_units}")"
  units=$((price_ticks * tick_units))
  psql_exec <<SQL >/dev/null
INSERT INTO price_index_ticks (
    symbol, sequence, index_price, status, component_count, valid_component_count,
    total_configured_weight, event_time
) VALUES (
    '${symbol}', ${sequence}, ${price}, 'HEALTHY', 5, 5, 5.000000000000000000, now()
) ON CONFLICT (symbol, sequence) DO UPDATE SET
    index_price = EXCLUDED.index_price,
    status = EXCLUDED.status,
    component_count = EXCLUDED.component_count,
    valid_component_count = EXCLUDED.valid_component_count,
    total_configured_weight = EXCLUDED.total_configured_weight,
    event_time = EXCLUDED.event_time;

INSERT INTO price_mark_ticks (
    symbol, sequence, mark_price, mark_price_units, index_price, price1, price2,
    last_trade_price, best_bid_price, best_ask_price, funding_rate, next_funding_time,
    time_until_funding_seconds, basis_average, basis_window_seconds, clamp_low, clamp_high,
    status, event_time
) VALUES (
    '${symbol}', ${sequence}, ${price}, ${units}, ${price}, ${price}, ${price},
    ${price}, ${bid}, ${ask}, 0.000000000000000000, now() + interval '8 hours',
    28800, 0.000000000000000000, 60,
    (${price} * 0.970000000000000000),
    (${price} * 1.030000000000000000),
    'HEALTHY', now()
) ON CONFLICT (symbol, sequence) DO UPDATE SET
    mark_price = EXCLUDED.mark_price,
    mark_price_units = EXCLUDED.mark_price_units,
    index_price = EXCLUDED.index_price,
    price1 = EXCLUDED.price1,
    price2 = EXCLUDED.price2,
    last_trade_price = EXCLUDED.last_trade_price,
    best_bid_price = EXCLUDED.best_bid_price,
    best_ask_price = EXCLUDED.best_ask_price,
    funding_rate = EXCLUDED.funding_rate,
    next_funding_time = EXCLUDED.next_funding_time,
    time_until_funding_seconds = EXCLUDED.time_until_funding_seconds,
    basis_average = EXCLUDED.basis_average,
    basis_window_seconds = EXCLUDED.basis_window_seconds,
    clamp_low = EXCLUDED.clamp_low,
    clamp_high = EXCLUDED.clamp_high,
    status = EXCLUDED.status,
    event_time = EXCLUDED.event_time;
SQL
}

seed_prices_for_line() {
  local product_line="$1"
  local symbol price_ticks tick_units
  symbol="$(symbol_for "${product_line}")"
  price_ticks="$(price_ticks_for "${product_line}")"
  tick_units="$(price_tick_units_for "${product_line}")"
  insert_mark_price "${symbol}" "${tick_units}" "$((1000 + RUN_SEQ % 100000))" "${price_ticks}"
  insert_mark_price "BTC-USDT" 10000000 "$((2000 + RUN_SEQ % 100000))" 600000
}

seed_lifecycle_settlement_price() {
  local product_line="$1"
  local symbol price_ticks tick_units sequence
  symbol="$(symbol_for "${product_line}")"
  price_ticks="$(price_ticks_for "${product_line}")"
  tick_units="$(price_tick_units_for "${product_line}")"
  sequence="$((3000 + RUN_SEQ % 100000))"
  if is_delivery_product "${product_line}"; then
    insert_mark_price "${symbol}" "${tick_units}" "${sequence}" "$((price_ticks + 100))"
  elif is_option_product "${product_line}"; then
    insert_mark_price "BTC-USDT" 10000000 "${sequence}" 600100
  fi
}

json_field() {
  local field="$1"
  python3 -c "import json,sys; print(json.load(sys.stdin)['${field}'])"
}

api_post() {
  local product_line="$1"
  local path="$2"
  local user_id="$3"
  local trace_id="$4"
  local payload="$5"
  curl -fsS -X POST "http://localhost:9094/api/v1/gateway/${path}" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Product-Line: ${product_line}" \
    -H "X-Trace-Id: ${trace_id}" \
    -d "${payload}"
}

api_get() {
  local product_line="$1"
  local path="$2"
  local user_id="$3"
  local trace_id="$4"
  curl -fsS "http://localhost:9094/api/v1/gateway/${path}" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Product-Line: ${product_line}" \
    -H "X-Trace-Id: ${trace_id}"
}

adjust_product_balance() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  local amount_units="$4"
  local reference_id="$5"
  curl -fsS -X POST "http://localhost:9086/api/v1/accounts/admin/product-balance-adjustments" \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": ${user_id},
      \"accountType\": \"${account_type}\",
      \"asset\": \"${asset}\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"PRODUCT_LINE_API_SMOKE\"
    }" >/dev/null
}

fund_user_for_line() {
  local product_line="$1"
  local user_id="$2"
  local asset amount type
  type="$(account_type "${product_line}")"
  if is_spot "${product_line}"; then
    adjust_product_balance "${user_id}" "${type}" "USDT" 200000000000000 "smoke-${RUN_ID}-${product_line}-${user_id}-usdt"
    adjust_product_balance "${user_id}" "${type}" "BTC" 100000000000 "smoke-${RUN_ID}-${product_line}-${user_id}-btc"
  else
    asset="$(settle_asset_for "${product_line}")"
    amount="$(settle_funding_amount "${asset}")"
    adjust_product_balance "${user_id}" "${type}" "${asset}" "${amount}" "smoke-${RUN_ID}-${product_line}-${user_id}-${asset}"
  fi
}

fund_liquidation_user_for_line() {
  local product_line="$1"
  local user_id="$2"
  local asset amount type
  type="$(account_type "${product_line}")"
  asset="$(settle_asset_for "${product_line}")"
  amount="$(liquidation_test_margin_amount "${asset}")"
  adjust_product_balance "${user_id}" "${type}" "${asset}" "${amount}" "smoke-${RUN_ID}-${product_line}-${user_id}-${asset}-liq-margin"
}

place_order() {
  local product_line="$1"
  local user_id="$2"
  local client_order_id="$3"
  local side="$4"
  local order_type="$5"
  local tif="$6"
  local price_ticks="$7"
  local quantity_steps="$8"
  local reduce_only="$9"
  local post_only="${10}"
  local symbol
  symbol="$(symbol_for "${product_line}")"
  seed_prices_for_line "${product_line}"
  local response
  response="$(api_post "${product_line}" "trading" "${user_id}" "product-smoke-${RUN_ID}-${client_order_id}" "{
      \"userId\": ${user_id},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${symbol}\",
      \"side\": \"${side}\",
      \"orderType\": \"${order_type}\",
      \"timeInForce\": \"${tif}\",
      \"priceTicks\": ${price_ticks},
      \"quantitySteps\": ${quantity_steps},
      \"marginMode\": \"CROSS\",
      \"positionSide\": \"NET\",
      \"reduceOnly\": ${reduce_only},
      \"postOnly\": ${post_only}
    }")"
  printf '%s\n' "${response}" | json_field orderId
}

place_order_expect_rejected() {
  local product_line="$1"
  local user_id="$2"
  local client_order_id="$3"
  local side="$4"
  local order_type="$5"
  local tif="$6"
  local price_ticks="$7"
  local quantity_steps="$8"
  local reduce_only="$9"
  local post_only="${10}"
  local symbol body_file http_code order_id
  symbol="$(symbol_for "${product_line}")"
  seed_prices_for_line "${product_line}"
  body_file="${TMP_DIR}/${product_line}-${client_order_id}.response.json"
  http_code="$(curl -sS -o "${body_file}" -w "%{http_code}" -X POST "http://localhost:9094/api/v1/gateway/trading" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Product-Line: ${product_line}" \
    -H "X-Trace-Id: product-smoke-${RUN_ID}-${client_order_id}" \
    -d "{
      \"userId\": ${user_id},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${symbol}\",
      \"side\": \"${side}\",
      \"orderType\": \"${order_type}\",
      \"timeInForce\": \"${tif}\",
      \"priceTicks\": ${price_ticks},
      \"quantitySteps\": ${quantity_steps},
      \"marginMode\": \"CROSS\",
      \"positionSide\": \"NET\",
      \"reduceOnly\": ${reduce_only},
      \"postOnly\": ${post_only}
    }")"
  if [[ "${http_code}" =~ ^2 ]]; then
    order_id="$(json_field orderId <"${body_file}")"
    wait_order_status "${product_line}" "${order_id}" "REJECTED"
    printf '%s\n' "${order_id}"
    return
  fi
  case "${http_code}" in
    400|409|422)
      wait_sql_equals "rejected order ${client_order_id} did not remain active" \
        "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND user_id = ${user_id} AND client_order_id = '${client_order_id}' AND status NOT IN ('REJECTED', 'CANCELED')" \
        "0" \
        20
      printf '\n'
      ;;
    *)
      echo "Expected rejected order ${client_order_id}, got HTTP ${http_code}" >&2
      cat "${body_file}" >&2 || true
      exit 1
      ;;
  esac
}

cancel_order() {
  local product_line="$1"
  local user_id="$2"
  local order_id="$3"
  api_post "${product_line}" "trading/cancel" "${user_id}" "product-smoke-${RUN_ID}-cancel-${order_id}" \
    "{\"userId\": ${user_id}, \"orderId\": ${order_id}}" >/dev/null
}

amend_order() {
  local product_line="$1"
  local user_id="$2"
  local order_id="$3"
  local price_ticks="$4"
  local quantity_steps="$5"
  local new_client_order_id response
  new_client_order_id="am-${order_id}-${RUN_SEQ}"
  response="$(api_post "${product_line}" "trading/amend" "${user_id}" "product-smoke-${RUN_ID}-amend-${order_id}" \
    "{\"userId\": ${user_id}, \"orderId\": ${order_id}, \"newClientOrderId\": \"${new_client_order_id}\", \"priceTicks\": ${price_ticks}, \"quantitySteps\": ${quantity_steps}}")"
  printf '%s\n' "${response}" | python3 -c "import json,sys; print(json.load(sys.stdin)['replacementOrder']['orderId'])"
}

wait_order_status() {
  local product_line="$1"
  local order_id="$2"
  local expected="$3"
  wait_sql_equals "order ${order_id} status ${expected}" \
    "SELECT COALESCE((SELECT status FROM trading_orders WHERE product_line = '${product_line}' AND order_id = ${order_id}), '')" \
    "${expected}"
}

wait_order_filled() {
  local product_line="$1"
  local order_id="$2"
  local qty="$3"
  wait_sql_equals "order ${order_id} filled ${qty}" \
    "SELECT COALESCE((SELECT status || ':' || executed_quantity_steps || ':' || remaining_quantity_steps FROM trading_orders WHERE product_line = '${product_line}' AND order_id = ${order_id}), '')" \
    "FILLED:${qty}:0"
}

wait_account_processed_order() {
  local product_line="$1"
  local order_id="$2"
  wait_sql_nonzero "account processed order ${order_id}" \
    "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.product_line = p.product_line AND t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.product_line = '${product_line}' AND (t.taker_order_id = ${order_id} OR t.maker_order_id = ${order_id})"
}

wait_position() {
  local product_line="$1"
  local user_id="$2"
  local symbol="$3"
  local expected_qty="$4"
  local expected_entry="$5"
  wait_sql_equals "position ${product_line} ${user_id} ${symbol} ${expected_qty}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE product_line = '${product_line}' AND user_id = ${user_id} AND symbol = '${symbol}' AND margin_mode = 'CROSS' AND position_side = 'NET'), '0:0')" \
    "${expected_qty}:${expected_entry}"
}

assert_no_negative_balances() {
  local product_line="$1"
  wait_sql_equals "no negative product balances ${product_line}" \
    "SELECT count(*) FROM account_product_balances WHERE account_type = '$(account_type "${product_line}")' AND (available_units < 0 OR locked_units < 0)" \
    "0"
  wait_sql_equals "no negative position margins ${product_line}" \
    "SELECT count(*) FROM account_position_margins WHERE product_line = '${product_line}' AND margin_units < 0" \
    "0"
}

assert_liquidation_fees_insured() {
  local product_line="$1"
  local type
  type="$(account_type "${product_line}")"
  if ! is_margin_product "${product_line}"; then
    return
  fi
  wait_sql_equals "liquidation fees insured ${product_line}" \
    "WITH user_liq AS (
       SELECT '${type}' AS account_type, asset, reference_id, amount_units
         FROM account_ledger_entries
        WHERE '${type}' = 'USDT_PERPETUAL'
          AND reference_type = 'LIQUIDATION_FEE'
       UNION ALL
       SELECT account_type, asset, reference_id, amount_units
         FROM account_product_ledger_entries
        WHERE account_type = '${type}'
          AND reference_type = 'LIQUIDATION_FEE'
     )
     SELECT count(*)
       FROM user_liq l
       LEFT JOIN insurance_fund_ledger i
         ON i.reference_type = 'LIQUIDATION_FEE'
        AND i.reference_id = l.reference_id
        AND i.account_type = l.account_type
        AND i.asset = l.asset
      WHERE i.entry_id IS NULL
         OR i.amount_units <> -l.amount_units" \
    "0" \
    "180"
}

assert_outbox_drained() {
  local product_line="$1"
  wait_sql_equals "trading outbox drained ${product_line}" \
    "SELECT count(*) FROM trading_outbox_events WHERE published_at IS NULL" \
    "0"
  wait_sql_equals "account outbox drained ${product_line}" \
    "SELECT count(*) FROM account_outbox_events WHERE published_at IS NULL" \
    "0"
  if is_margin_product "${product_line}"; then
    wait_sql_equals "risk outbox drained ${product_line}" \
      "SELECT count(*) FROM risk_outbox_events WHERE published_at IS NULL" \
      "0"
    assert_liquidation_fees_insured "${product_line}"
  fi
}

reconcile_funds() {
  local product_line="$1"
  if [[ "${RECONCILE_FUNDS}" != "true" ]]; then
    return
  fi
  PRODUCT_LINES="${product_line}" \
    DB_HOST=localhost \
    DB_USER="${DB_USER}" \
    DB_PASSWORD="${DB_PASSWORD}" \
    DB_NAME="${DB_NAME}" \
    POSTGRES_PORT="${POSTGRES_PORT}" \
    "${ROOT_DIR}/scripts/product-line-funds-reconcile.sh"
}

wait_market_maker_running() {
  local product_line="$1"
  local symbol
  symbol="$(symbol_for "${product_line}")"
  wait_sql_nonzero "market-maker cycle success ${product_line}" \
    "SELECT count(*) FROM market_maker_strategy_run_events WHERE product_line = '${product_line}' AND strategy_id = 'product-smoke-mm' AND event_type = 'CYCLE_SUCCESS'"
  wait_sql_nonzero "market-maker open quotes ${product_line}" \
    "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND symbol = '${symbol}' AND user_id IN (${MM_USER_A}, ${MM_USER_B}) AND client_order_id LIKE 'mm-%' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')"
}

run_market_maker_taker_flow() {
  local product_line="$1"
  local symbol price qty buy_order sell_order
  symbol="$(symbol_for "${product_line}")"
  price="$(price_ticks_for "${product_line}")"
  qty=1
  echo "Scenario ${product_line}: user taker trades against continuously running market-maker"
  wait_market_maker_running "${product_line}"
  buy_order="$(place_order "${product_line}" "${TAKER_USER}" "mm-taker-buy-${product_line}-${RUN_ID}" "BUY" "LIMIT" "IOC" "$(price_with_offset "${product_line}" "${price}" 1000)" "${qty}" false false)"
  wait_order_filled "${product_line}" "${buy_order}" "${qty}"
  wait_account_processed_order "${product_line}" "${buy_order}"
  if is_margin_product "${product_line}"; then
    wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "${qty}" "$(query_value "SELECT price_ticks FROM trading_match_trades WHERE product_line = '${product_line}' AND taker_order_id = ${buy_order} ORDER BY event_time DESC LIMIT 1")"
    sell_order="$(place_order "${product_line}" "${TAKER_USER}" "mm-taker-close-${product_line}-${RUN_ID}" "SELL" "LIMIT" "IOC" "$(price_with_offset "${product_line}" "${price}" -1000)" "${qty}" true false)"
    wait_order_filled "${product_line}" "${sell_order}" "${qty}"
    wait_account_processed_order "${product_line}" "${sell_order}"
    wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "0" "0"
  else
    sell_order="$(place_order "${product_line}" "${TAKER_USER}" "mm-taker-spot-sell-${product_line}-${RUN_ID}" "SELL" "LIMIT" "IOC" "$(price_with_offset "${product_line}" "${price}" -1000)" "${qty}" false false)"
    wait_order_filled "${product_line}" "${sell_order}" "${qty}"
    wait_account_processed_order "${product_line}" "${sell_order}"
  fi
}

run_order_api_controls() {
  local product_line="$1"
  local price qty cancel_order_id amend_order_id self_maker self_taker self_taker_client post_maker post_taker post_taker_client replacement_price self_price post_price
  price="$(price_ticks_for "${product_line}")"
  qty=2
  echo "Scenario ${product_line}: API order controls"
  place_order_expect_rejected "${product_line}" "${NO_FUNDS_USER}" "no-funds-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "${price}" "${qty}" false false >/dev/null

  fund_user_for_line "${product_line}" "${CANCEL_USER}"
  cancel_order_id="$(place_order "${product_line}" "${CANCEL_USER}" "cancel-open-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "$(price_with_offset "${product_line}" "${price}" -5000)" "${qty}" false false)"
  wait_order_status "${product_line}" "${cancel_order_id}" "ACCEPTED"
  cancel_order "${product_line}" "${CANCEL_USER}" "${cancel_order_id}"
  wait_order_status "${product_line}" "${cancel_order_id}" "CANCELED"

  fund_user_for_line "${product_line}" "${AMEND_USER}"
  amend_order_id="$(place_order "${product_line}" "${AMEND_USER}" "amend-open-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "$(price_with_offset "${product_line}" "${price}" -6000)" "${qty}" false false)"
  wait_order_status "${product_line}" "${amend_order_id}" "ACCEPTED"
  local replacement_order_id
  replacement_price="$(price_with_offset "${product_line}" "${price}" -7000)"
  replacement_order_id="$(amend_order "${product_line}" "${AMEND_USER}" "${amend_order_id}" "${replacement_price}" 1)"
  wait_order_status "${product_line}" "${amend_order_id}" "CANCELED"
  wait_order_status "${product_line}" "${replacement_order_id}" "ACCEPTED"
  wait_sql_equals "amended replacement persisted ${product_line}" \
    "SELECT price_ticks || ':' || quantity_steps FROM trading_orders WHERE product_line = '${product_line}' AND order_id = ${replacement_order_id}" \
    "${replacement_price}:1"
  cancel_order "${product_line}" "${AMEND_USER}" "${replacement_order_id}"
  wait_order_status "${product_line}" "${replacement_order_id}" "CANCELED"

  fund_user_for_line "${product_line}" "${SELF_TRADE_USER}"
  self_price="$(price_with_offset "${product_line}" "${price}" 3000)"
  self_maker="$(place_order "${product_line}" "${SELF_TRADE_USER}" "self-maker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "GTC" "${self_price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${self_maker}" "ACCEPTED"
  self_taker_client="self-taker-${product_line}-${RUN_ID}"
  self_taker="$(place_order_expect_rejected "${product_line}" "${SELF_TRADE_USER}" "${self_taker_client}" "BUY" "LIMIT" "IOC" "${self_price}" "${qty}" false false)"
  wait_sql_equals "self trade prevented ${product_line}" \
    "SELECT count(*) FROM trading_match_trades t JOIN trading_orders o ON o.product_line = t.product_line AND o.order_id = t.taker_order_id WHERE t.product_line = '${product_line}' AND o.user_id = ${SELF_TRADE_USER} AND o.client_order_id = '${self_taker_client}'" \
    "0"
  cancel_order "${product_line}" "${SELF_TRADE_USER}" "${self_maker}"
  wait_order_status "${product_line}" "${self_maker}" "CANCELED"

  fund_user_for_line "${product_line}" "${POST_ONLY_USER}"
  fund_user_for_line "${product_line}" "${MANUAL_MAKER_USER}"
  post_price="$(price_with_offset "${product_line}" "${price}" 4000)"
  post_maker="$(place_order "${product_line}" "${MANUAL_MAKER_USER}" "post-maker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "GTC" "${post_price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${post_maker}" "ACCEPTED"
  post_taker_client="post-only-cross-${product_line}-${RUN_ID}"
  post_taker="$(place_order_expect_rejected "${product_line}" "${POST_ONLY_USER}" "${post_taker_client}" "BUY" "LIMIT" "GTX" "${post_price}" "${qty}" false true)"
  cancel_order "${product_line}" "${MANUAL_MAKER_USER}" "${post_maker}"
  wait_order_status "${product_line}" "${post_maker}" "CANCELED"
}

run_manual_open_close_flow() {
  local product_line="$1"
  local symbol price qty maker_order taker_order close_maker close_taker entry_price
  if ! is_margin_product "${product_line}"; then
    return
  fi
  symbol="$(symbol_for "${product_line}")"
  price="$(price_ticks_for "${product_line}")"
  qty=3
  echo "Scenario ${product_line}: user opens position and closes by reduce-only API order"
  fund_user_for_line "${product_line}" "${MANUAL_MAKER_USER}"
  fund_user_for_line "${product_line}" "${TAKER_USER}"
  maker_order="$(place_order "${product_line}" "${MANUAL_MAKER_USER}" "manual-open-maker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "GTC" "${price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${maker_order}" "ACCEPTED"
  taker_order="$(place_order "${product_line}" "${TAKER_USER}" "manual-open-taker-${product_line}-${RUN_ID}" "BUY" "LIMIT" "IOC" "${price}" "${qty}" false false)"
  wait_order_filled "${product_line}" "${taker_order}" "${qty}"
  wait_account_processed_order "${product_line}" "${taker_order}"
  entry_price="$(query_value "SELECT price_ticks FROM trading_match_trades WHERE product_line = '${product_line}' AND taker_order_id = ${taker_order} ORDER BY event_time DESC LIMIT 1")"
  wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "${qty}" "${entry_price}"
  wait_position "${product_line}" "${MANUAL_MAKER_USER}" "${symbol}" "-${qty}" "${entry_price}"

  if is_funding_product "${product_line}"; then
    run_funding_settlement_flow "${product_line}" "${symbol}"
  fi

  close_maker="$(place_order "${product_line}" "${MANUAL_MAKER_USER}" "manual-close-maker-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "${price}" "${qty}" true false)"
  wait_order_status "${product_line}" "${close_maker}" "ACCEPTED"
  close_taker="$(place_order "${product_line}" "${TAKER_USER}" "manual-close-taker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "IOC" "${price}" "${qty}" true false)"
  wait_order_filled "${product_line}" "${close_taker}" "${qty}"
  wait_account_processed_order "${product_line}" "${close_taker}"
  wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "0" "0"
  wait_position "${product_line}" "${MANUAL_MAKER_USER}" "${symbol}" "0" "0"
}

run_funding_settlement_flow() {
  local product_line="$1"
  local symbol="$2"
  echo "Scenario ${product_line}: funding settlement while positions are open"
  psql_exec <<SQL >/dev/null
INSERT INTO funding_rate_ticks (
    symbol, sequence, funding_time, funding_interval_hours,
    premium_rate_ppm, interest_rate_ppm, funding_rate_ppm, status, event_time, created_at
) VALUES (
    '${symbol}', ${RUN_SEQ}, now() - interval '1 second', 8,
    1000, 0, 1000, 'PREDICTED', now(), now()
) ON CONFLICT (symbol, sequence) DO NOTHING;
SQL
  wait_sql_nonzero "funding settlement completed ${product_line}" \
    "SELECT count(*) FROM funding_settlements WHERE symbol = '${symbol}' AND funding_rate_ppm = 1000 AND status = 'COMPLETED' AND position_count > 0" \
    180
  wait_sql_nonzero "funding payments ${product_line}" \
    "SELECT count(*) FROM funding_payments p JOIN funding_settlements s ON s.settlement_id = p.settlement_id WHERE s.symbol = '${symbol}' AND s.funding_rate_ppm = 1000" \
    180
}

update_risk_runtime_config() {
  local warning="$1"
  local liquidation="$2"
  curl -fsS -X POST "http://localhost:9087/api/v1/risk/admin/runtime-config" \
    -H "Content-Type: application/json" \
    -H "X-Admin-User-Id: product-line-smoke" \
    -d "{\"warningMarginRatioPpm\": ${warning}, \"liquidationMarginRatioPpm\": ${liquidation}}" >/dev/null
}

update_liquidation_runtime_config() {
  local full_close="$1"
  curl -fsS -X POST "http://localhost:9088/api/v1/liquidations/admin/runtime-config" \
    -H "Content-Type: application/json" \
    -H "X-Admin-User-Id: product-line-smoke" \
    -d "{\"fullCloseMarginRatioPpm\": ${full_close}}" >/dev/null
}

run_liquidation_flow() {
  local product_line="$1"
  local symbol price qty open_maker open_taker close_maker pre_ratio liq_threshold warning
  local open_maker_side open_taker_side close_maker_side expected_liq_qty
  if ! is_margin_product "${product_line}"; then
    return
  fi
  symbol="$(symbol_for "${product_line}")"
  price="$(price_ticks_for "${product_line}")"
  qty=2
  open_maker_side="SELL"
  open_taker_side="BUY"
  close_maker_side="BUY"
  expected_liq_qty="${qty}"
  if is_option_product "${product_line}"; then
    open_maker_side="BUY"
    open_taker_side="SELL"
    close_maker_side="SELL"
    expected_liq_qty="-${qty}"
  fi
  echo "Scenario ${product_line}: risk creates liquidation candidate and liquidation closes position"
  fund_liquidation_user_for_line "${product_line}" "${LIQ_USER}"
  fund_user_for_line "${product_line}" "${LIQ_MAKER_USER}"
  open_maker="$(place_order "${product_line}" "${LIQ_MAKER_USER}" "liq-open-maker-${product_line}-${RUN_ID}" "${open_maker_side}" "LIMIT" "GTC" "${price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${open_maker}" "ACCEPTED"
  open_taker="$(place_order "${product_line}" "${LIQ_USER}" "liq-open-taker-${product_line}-${RUN_ID}" "${open_taker_side}" "LIMIT" "IOC" "${price}" "${qty}" false false)"
  wait_order_filled "${product_line}" "${open_taker}" "${qty}"
  wait_account_processed_order "${product_line}" "${open_taker}"
  wait_position "${product_line}" "${LIQ_USER}" "${symbol}" "${expected_liq_qty}" "${price}"
  wait_sql_nonzero "risk snapshot after open ${product_line}" \
    "SELECT count(*) FROM risk_account_snapshots WHERE product_line = '${product_line}' AND user_id = ${LIQ_USER} AND maintenance_margin_units > 0 AND margin_ratio_ppm > 1"
  pre_ratio="$(query_value "SELECT margin_ratio_ppm FROM risk_account_snapshots WHERE product_line = '${product_line}' AND user_id = ${LIQ_USER} ORDER BY event_time DESC, snapshot_id DESC LIMIT 1")"
  liq_threshold=$((pre_ratio * 80 / 100))
  if ((liq_threshold <= 1)); then
    echo "Unable to derive liquidation threshold from margin ratio ${pre_ratio}" >&2
    exit 1
  fi
  warning=$((liq_threshold - 1))
  close_maker="$(place_order "${product_line}" "${LIQ_MAKER_USER}" "liq-close-maker-${product_line}-${RUN_ID}" "${close_maker_side}" "LIMIT" "GTC" "${price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${close_maker}" "ACCEPTED"
  update_liquidation_runtime_config "${liq_threshold}"
  update_risk_runtime_config "${warning}" "${liq_threshold}"
  wait_sql_nonzero "liquidation candidate ${product_line}" \
    "SELECT count(*) FROM risk_liquidation_candidates WHERE product_line = '${product_line}' AND user_id = ${LIQ_USER} AND symbol = '${symbol}'"
  wait_sql_nonzero "liquidation order filled ${product_line}" \
    "SELECT count(*) FROM liquidation_orders l JOIN risk_liquidation_candidates c ON c.candidate_id = l.candidate_id WHERE c.product_line = '${product_line}' AND l.user_id = ${LIQ_USER} AND l.symbol = '${symbol}' AND l.status = 'FILLED'"
  wait_position "${product_line}" "${LIQ_USER}" "${symbol}" "0" "0"
  update_risk_runtime_config 800000 1000000
  update_liquidation_runtime_config 3000000
}

instrument_json() {
  local symbol="$1"
  query_value "SELECT jsonb_build_object(
    'symbol', symbol,
    'version', version,
    'instrumentType', instrument_type,
    'contractType', contract_type,
    'baseAsset', base_asset,
    'quoteAsset', quote_asset,
    'settleAsset', settle_asset,
    'contractMultiplierPpm', contract_multiplier_ppm,
    'contractValueAsset', contract_value_asset,
    'priceTickUnits', price_tick_units,
    'quantityStepUnits', quantity_step_units,
    'minQuantitySteps', min_quantity_steps,
    'maxQuantitySteps', max_quantity_steps,
    'minNotionalUnits', min_notional_units,
    'maxNotionalUnits', max_notional_units,
    'notionalMultiplierUnits', notional_multiplier_units,
    'pricePrecision', price_precision,
    'quantityPrecision', quantity_precision,
    'supportedOrderTypes', string_to_array(supported_order_types, ','),
    'supportedTimeInForce', string_to_array(supported_time_in_force, ','),
    'postOnlyEnabled', post_only_enabled,
    'reduceOnlyEnabled', reduce_only_enabled,
    'marketOrderEnabled', market_order_enabled,
    'maxLeveragePpm', max_leverage_ppm,
    'initialMarginRatePpm', initial_margin_rate_ppm,
    'maintenanceMarginRatePpm', maintenance_margin_rate_ppm,
    'makerFeeRatePpm', maker_fee_rate_ppm,
    'takerFeeRatePpm', taker_fee_rate_ppm,
    'maxPositionNotionalUnits', max_position_notional_units,
    'userOpenInterestLimitRatePpm', user_open_interest_limit_rate_ppm,
    'userOpenInterestLimitFloorUnits', user_open_interest_limit_floor_units,
    'fundingIntervalHours', funding_interval_hours,
    'interestRatePpm', interest_rate_ppm,
    'fundingRateCapPpm', funding_rate_cap_ppm,
    'fundingRateFloorPpm', funding_rate_floor_ppm,
    'impactNotionalUnits', impact_notional_units,
    'minValidIndexSources', min_valid_index_sources,
    'expiryTime', expiry_time,
    'deliveryTime', delivery_time,
    'underlyingSymbol', underlying_symbol,
    'strikePriceUnits', strike_price_units,
    'optionType', option_type,
    'optionExerciseStyle', option_exercise_style,
    'settlementMethod', settlement_method,
    'status', status,
    'effectiveTime', effective_time,
    'createdAt', created_at,
    'updatedAt', updated_at,
    'riskLimitBrackets', '[]'::jsonb,
    'indexSources', '[]'::jsonb
  ) FROM instruments WHERE symbol = '${symbol}' AND version = 1"
}

produce_json() {
  local topic="$1"
  local key="$2"
  local payload="$3"
  local producer_cmd
  producer_cmd="$(kafka_producer_cmd)"
  printf '%s:%s\n' "${key}" "${payload}" | "${producer_cmd}" \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
    --topic "${topic}" \
    --property parse.key=true \
    --property key.separator=: >/dev/null
}

publish_lifecycle_event() {
  local product_line="$1"
  local symbol="$2"
  local event_time instrument payload topic strike
  event_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  psql_exec -c "UPDATE instruments SET status = 'CLOSED', updated_at = now() WHERE symbol = '${symbol}' AND version = 1" >/dev/null
  instrument="$(instrument_json "${symbol}")"
  if is_delivery_product "${product_line}"; then
    topic="$(topic_name "${product_line}" "delivery.settlements")"
    payload="{\"symbol\":\"${symbol}\",\"version\":1,\"contractType\":\"$(contract_type "${product_line}")\",\"expiryTime\":\"${event_time}\",\"deliveryTime\":\"${event_time}\",\"settlementMethod\":\"CASH\",\"status\":\"CLOSED\",\"eventTime\":\"${event_time}\",\"instrument\":${instrument}}"
  else
    topic="$(topic_name "${product_line}" "option.exercises")"
    strike="$(query_value "SELECT strike_price_units FROM instruments WHERE symbol = '${symbol}' AND version = 1")"
    payload="{\"symbol\":\"${symbol}\",\"version\":1,\"underlyingSymbol\":\"BTC-USDT\",\"strikePriceUnits\":${strike},\"optionType\":\"CALL\",\"optionExerciseStyle\":\"EUROPEAN\",\"expiryTime\":\"${event_time}\",\"deliveryTime\":\"${event_time}\",\"settlementMethod\":\"CASH\",\"status\":\"CLOSED\",\"eventTime\":\"${event_time}\",\"instrument\":${instrument}}"
  fi
  produce_json "${topic}" "${symbol}" "${payload}"
}

run_lifecycle_flow() {
  local product_line="$1"
  local symbol price qty maker taker
  if ! is_delivery_product "${product_line}" && ! is_option_product "${product_line}"; then
    return
  fi
  symbol="$(symbol_for "${product_line}")"
  price="$(price_ticks_for "${product_line}")"
  qty=2
  echo "Scenario ${product_line}: lifecycle settlement/exercise closes open positions"
  fund_user_for_line "${product_line}" "${LIFECYCLE_USER}"
  fund_user_for_line "${product_line}" "${LIFECYCLE_MAKER_USER}"
  maker="$(place_order "${product_line}" "${LIFECYCLE_MAKER_USER}" "life-open-maker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "GTC" "${price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${maker}" "ACCEPTED"
  taker="$(place_order "${product_line}" "${LIFECYCLE_USER}" "life-open-taker-${product_line}-${RUN_ID}" "BUY" "LIMIT" "IOC" "${price}" "${qty}" false false)"
  wait_order_filled "${product_line}" "${taker}" "${qty}"
  wait_account_processed_order "${product_line}" "${taker}"
  wait_position "${product_line}" "${LIFECYCLE_USER}" "${symbol}" "${qty}" "${price}"
  stop_provider_by_name price-refresher
  seed_lifecycle_settlement_price "${product_line}"
  sleep 1
  publish_lifecycle_event "${product_line}" "${symbol}"
  wait_position "${product_line}" "${LIFECYCLE_USER}" "${symbol}" "0" "0"
  wait_position "${product_line}" "${LIFECYCLE_MAKER_USER}" "${symbol}" "0" "0"
  wait_sql_nonzero "lifecycle ledger ${product_line}" \
    "SELECT count(*) FROM account_product_ledger_entries WHERE user_id IN (${LIFECYCLE_USER}, ${LIFECYCLE_MAKER_USER}) AND account_type = '$(account_type "${product_line}")' AND reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE') AND amount_units <> 0"
}

run_spot_asset_flow() {
  local product_line="$1"
  local price qty maker taker seller_btc buyer_btc
  if ! is_spot "${product_line}"; then
    return
  fi
  price="$(price_ticks_for "${product_line}")"
  qty=3
  echo "Scenario ${product_line}: spot asset exchange and reservation release"
  fund_user_for_line "${product_line}" "${MANUAL_MAKER_USER}"
  fund_user_for_line "${product_line}" "${TAKER_USER}"
  maker="$(place_order "${product_line}" "${MANUAL_MAKER_USER}" "spot-sell-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${price}" "${qty}" false false)"
  wait_order_status "${product_line}" "${maker}" "ACCEPTED"
  taker="$(place_order "${product_line}" "${TAKER_USER}" "spot-buy-taker-${RUN_ID}" "BUY" "LIMIT" "IOC" "${price}" "${qty}" false false)"
  wait_order_filled "${product_line}" "${taker}" "${qty}"
  wait_account_processed_order "${product_line}" "${taker}"
  wait_sql_equals "spot has no derivative positions" \
    "SELECT count(*) FROM account_positions WHERE product_line = 'SPOT' AND user_id IN (${MANUAL_MAKER_USER}, ${TAKER_USER})" \
    "0"
  seller_btc="$(query_value "SELECT COALESCE((SELECT available_units FROM account_product_balances WHERE user_id = ${MANUAL_MAKER_USER} AND account_type = 'SPOT' AND asset = 'BTC'), 0)")"
  buyer_btc="$(query_value "SELECT COALESCE((SELECT available_units FROM account_product_balances WHERE user_id = ${TAKER_USER} AND account_type = 'SPOT' AND asset = 'BTC'), 0)")"
  if ((buyer_btc <= seller_btc)); then
    echo "Expected spot buyer BTC to increase relative to seller after trade, seller=${seller_btc}, buyer=${buyer_btc}" >&2
    exit 1
  fi
  wait_sql_equals "spot reservations released or terminal" \
    "SELECT count(*) FROM account_spot_order_reservations WHERE order_id IN (${maker}, ${taker}) AND status NOT IN ('RELEASED', 'SETTLED')" \
    "0"
}

run_line() {
  local product_line="$1"
  echo "========== Product line ${product_line} =========="
  reset_database
  reset_kafka_topics "${product_line}"
  rm -rf "${ROOT_DIR}/data/kafka-streams/$(product_slug "${product_line}")"
  seed_prices_for_line "${product_line}"
  start_providers_for_line "${product_line}"
  seed_prices_for_line "${product_line}"
  start_price_refresher "${product_line}"
  fund_user_for_line "${product_line}" "${MM_USER_A}"
  fund_user_for_line "${product_line}" "${MM_USER_B}"
  fund_user_for_line "${product_line}" "${TAKER_USER}"
  run_market_maker_taker_flow "${product_line}"
  run_order_api_controls "${product_line}"
  run_manual_open_close_flow "${product_line}"
  run_liquidation_flow "${product_line}"
  run_lifecycle_flow "${product_line}"
  run_spot_asset_flow "${product_line}"
  stop_provider_by_name market-maker
  assert_no_negative_balances "${product_line}"
  assert_outbox_drained "${product_line}"
  stop_all_providers
  reconcile_funds "${product_line}"
  echo "Product line ${product_line} passed"
}

main() {
  require_command psql
  require_command java
  require_command mvn
  require_command python3
  require_command curl
  require_command "$(kafka_topics_cmd)"
  require_command "$(kafka_producer_cmd)"
  wait_until "PostgreSQL" 120 pg_isready -h localhost -p "${POSTGRES_PORT}" -U "${DB_USER}"
  wait_until "Kafka" 120 "$(kafka_topics_cmd)" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list
  ensure_database
  package_services
  local product_line
  for product_line in ${PRODUCT_LINES}; do
    run_line "${product_line}"
  done
}

main "$@"
