#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="${DB_NAME:-surprising_product_line_smoke}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/${DB_NAME}?reWriteBatchedInserts=true}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
# 测试机同时运行多个 provider 时，命令行 Kafka 工具不需要 512M 堆，降低其
# 启动内存可以避免标记价补发被 JVM 资源竞争拖到业务等待窗口之外。
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms32M -Xmx128M}"
ACCOUNT_COMMAND_PARTITIONS="${ACCOUNT_COMMAND_PARTITIONS:-32}"
MARKET_MAKER_CYCLE_DELAY_MS="${MARKET_MAKER_CYCLE_DELAY_MS:-1000}"
PRODUCT_LINES="${PRODUCT_LINES:-LINEAR_PERPETUAL LINEAR_DELIVERY OPTION SPOT}"
BUILD_SERVICES="${BUILD_SERVICES:-true}"
KEEP_TMP="${KEEP_TMP:-true}"
RESET_KAFKA="${RESET_KAFKA:-false}"
CREATE_KAFKA_TOPICS="${CREATE_KAFKA_TOPICS:-false}"
KAFKA_INCLUDE_SHARED_TOPICS="${KAFKA_INCLUDE_SHARED_TOPICS:-true}"
KAFKA_INCLUDE_LEGACY_PERP_TOPICS="${KAFKA_INCLUDE_LEGACY_PERP_TOPICS:-false}"
KAFKA_RESET_SHARED_TOPICS="${KAFKA_RESET_SHARED_TOPICS:-false}"
KAFKA_RESET_LEGACY_PERP_TOPICS="${KAFKA_RESET_LEGACY_PERP_TOPICS:-false}"
KAFKA_TRUNCATE_TOPICS="${KAFKA_TRUNCATE_TOPICS:-false}"
RECONCILE_FUNDS="${RECONCILE_FUNDS:-true}"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
RUN_SEQUENCE_BASE=$((500000000 + (RUN_SEQ % 500000) * 3000))
TMP_DIR="$(mktemp -d /tmp/surprising-product-line-api.XXXXXX)"
MULTI_SYMBOL_STRESS="${MULTI_SYMBOL_STRESS:-false}"
STRESS_SYMBOL_COUNT="${STRESS_SYMBOL_COUNT:-20}"
STRESS_USER_COUNT="${STRESS_USER_COUNT:-2000}"
STRESS_SCENARIO="${STRESS_SCENARIO:-trade}"
STRESS_LOAD_CONCURRENCY="${STRESS_LOAD_CONCURRENCY:-128}"
STRESS_MAKER_LOAD_CONCURRENCY="${STRESS_MAKER_LOAD_CONCURRENCY:-32}"
STRESS_MAKER_DEPTH_LEVELS="${STRESS_MAKER_DEPTH_LEVELS:-6}"
STRESS_MAKER_REFRESH_CYCLES="${STRESS_MAKER_REFRESH_CYCLES:-3}"
STRESS_MAKER_REFRESH_LEVELS="${STRESS_MAKER_REFRESH_LEVELS:-2}"
STRESS_MAKER_LEVEL_QUANTITY_STEPS="${STRESS_MAKER_LEVEL_QUANTITY_STEPS:-250}"
STRESS_MAKER_BATCH_SIZE="${STRESS_MAKER_BATCH_SIZE:-12}"
STRESS_TAKER_QUANTITY_STEPS="${STRESS_TAKER_QUANTITY_STEPS:-1}"
STRESS_WAIT_SECONDS="${STRESS_WAIT_SECONDS:-420}"
STRESS_PRICE_WARMUP_SECONDS="${STRESS_PRICE_WARMUP_SECONDS:-35}"
STRESS_PRICE_REFRESH_DELAY_SECONDS="${STRESS_PRICE_REFRESH_DELAY_SECONDS:-1}"
KAFKA_ASSIGNMENT_TIMEOUT_SECONDS="${KAFKA_ASSIGNMENT_TIMEOUT_SECONDS:-90}"
KAFKA_TOPIC_RESET_TIMEOUT_SECONDS="${KAFKA_TOPIC_RESET_TIMEOUT_SECONDS:-60}"
STRESS_REPORT_FILE="${STRESS_REPORT_FILE:-${TMP_DIR}/stress-report.md}"
STRESS_RUN_LABEL="${STRESS_RUN_LABEL:-}"
STRESS_MATCHING_KAFKA_CONCURRENCY="${STRESS_MATCHING_KAFKA_CONCURRENCY:-32}"
STRESS_MATCHING_MAX_POLL_RECORDS="${STRESS_MATCHING_MAX_POLL_RECORDS:-128}"
STRESS_MATCHING_ENGINE_SHARDS="${STRESS_MATCHING_ENGINE_SHARDS:-4}"
STRESS_MATCHING_RISK_SHARDS="${STRESS_MATCHING_RISK_SHARDS:-2}"
STRESS_MATCHING_OUTBOX_BATCH_SIZE="${STRESS_MATCHING_OUTBOX_BATCH_SIZE:-1000}"
STRESS_MATCHING_OUTBOX_PUBLISH_DELAY_MS="${STRESS_MATCHING_OUTBOX_PUBLISH_DELAY_MS:-20}"
STRESS_MATCHING_OUTBOX_MAX_IN_FLIGHT="${STRESS_MATCHING_OUTBOX_MAX_IN_FLIGHT:-64}"
STRESS_MATCHING_OUTBOX_MAX_ROWS_PER_KEY="${STRESS_MATCHING_OUTBOX_MAX_ROWS_PER_KEY:-32}"
STRESS_ORDER_OUTBOX_BATCH_SIZE="${STRESS_ORDER_OUTBOX_BATCH_SIZE:-1000}"
STRESS_ORDER_OUTBOX_PUBLISH_DELAY_MS="${STRESS_ORDER_OUTBOX_PUBLISH_DELAY_MS:-20}"
STRESS_ORDER_OUTBOX_MAX_IN_FLIGHT="${STRESS_ORDER_OUTBOX_MAX_IN_FLIGHT:-64}"
STRESS_ORDER_OUTBOX_MAX_ROWS_PER_KEY="${STRESS_ORDER_OUTBOX_MAX_ROWS_PER_KEY:-32}"
STRESS_ACCOUNT_KAFKA_CONCURRENCY="${STRESS_ACCOUNT_KAFKA_CONCURRENCY:-32}"
STRESS_ACCOUNT_OUTBOX_BATCH_SIZE="${STRESS_ACCOUNT_OUTBOX_BATCH_SIZE:-1000}"
STRESS_ACCOUNT_OUTBOX_PUBLISH_DELAY_MS="${STRESS_ACCOUNT_OUTBOX_PUBLISH_DELAY_MS:-20}"
STRESS_ACCOUNT_OUTBOX_MAX_IN_FLIGHT="${STRESS_ACCOUNT_OUTBOX_MAX_IN_FLIGHT:-32}"
STRESS_ACCOUNT_OUTBOX_MAX_ROWS_PER_KEY="${STRESS_ACCOUNT_OUTBOX_MAX_ROWS_PER_KEY:-32}"
STRESS_RISK_KAFKA_CONCURRENCY="${STRESS_RISK_KAFKA_CONCURRENCY:-4}"
STRESS_RISK_OUTBOX_BATCH_SIZE="${STRESS_RISK_OUTBOX_BATCH_SIZE:-1000}"
STRESS_RISK_OUTBOX_PUBLISH_DELAY_MS="${STRESS_RISK_OUTBOX_PUBLISH_DELAY_MS:-20}"
STRESS_RISK_OUTBOX_MAX_ROWS_PER_KEY="${STRESS_RISK_OUTBOX_MAX_ROWS_PER_KEY:-32}"
STRESS_LIQUIDATION_KAFKA_CONCURRENCY="${STRESS_LIQUIDATION_KAFKA_CONCURRENCY:-32}"
STRESS_LIQUIDATION_WAIT_SECONDS="${STRESS_LIQUIDATION_WAIT_SECONDS:-900}"
STRESS_LIQUIDATION_MARK_FACTOR_PPM="${STRESS_LIQUIDATION_MARK_FACTOR_PPM:-800000}"
STRESS_LIQUIDATION_WALLET_RATE_PPM="${STRESS_LIQUIDATION_WALLET_RATE_PPM:-120000}"
STRESS_MARK_PRICE_FACTOR_PPM="${STRESS_MARK_PRICE_FACTOR_PPM:-1000000}"
STRESS_TARGET_TPS="${STRESS_TARGET_TPS:-0}"
STRESS_HOT_SYMBOL_COUNT="${STRESS_HOT_SYMBOL_COUNT:-0}"
STRESS_HOT_TRAFFIC_PERCENT="${STRESS_HOT_TRAFFIC_PERCENT:-80}"
STRESS_INTERNAL_MARKET_MAKER_WHITELIST="${STRESS_INTERNAL_MARKET_MAKER_WHITELIST:-true}"
STRESS_RESET_PG_STAT_STATEMENTS="${STRESS_RESET_PG_STAT_STATEMENTS:-true}"
STRESS_KAFKA_LAG_SAMPLE_SECONDS="${STRESS_KAFKA_LAG_SAMPLE_SECONDS:-2}"
ACCOUNT_RESTART_RECOVERY="${ACCOUNT_RESTART_RECOVERY:-false}"
ACCOUNT_RESTART_MODE="${ACCOUNT_RESTART_MODE:-kill}"
RUN_WEBSOCKET_SMOKE="${RUN_WEBSOCKET_SMOKE:-false}"
WS_TIMEOUT_MS="${WS_TIMEOUT_MS:-300000}"
STRESS_PG_STAT_STATEMENTS_AVAILABLE=false
STRESS_KAFKA_LAG_FILE=""
STRESS_KAFKA_LAG_STOP_FILE=""
STRESS_KAFKA_LAG_MONITOR_PID=""
STRESS_OUTBOX_START_ID=0
STRESS_PG_STAT_STATEMENTS_FILE=""

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
STRESS_MM_USER_START=$((BASE_USER + 10000))
# 后台 market-maker 使用一组账户；压测脚本的显式批量盘口使用另一组账户，
# 避免两个生产者同时修改同一账户修订号，仍然保留后台做市进程运行。
STRESS_BOOK_USER_START=$((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT))
STRESS_TAKER_USER_START=$((BASE_USER + 20000))

PROVIDER_NAMES=()
PROVIDER_PIDS=()
WEBSOCKET_SMOKE_PID=""
WEBSOCKET_SMOKE_LOG=""
WEBSOCKET_SMOKE_EVIDENCE=""

cleanup() {
  stop_stress_kafka_lag_monitor || true
  if [[ -n "${WEBSOCKET_SMOKE_PID}" ]] && kill -0 "${WEBSOCKET_SMOKE_PID}" >/dev/null 2>&1; then
    kill "${WEBSOCKET_SMOKE_PID}" >/dev/null 2>&1 || true
    wait "${WEBSOCKET_SMOKE_PID}" >/dev/null 2>&1 || true
  fi
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

restart_account_for_recovery() {
  local product_line="$1"
  echo "Restarting ${product_line} account provider for recovery verification (mode=${ACCOUNT_RESTART_MODE})"
  if [[ "${ACCOUNT_RESTART_MODE}" == "kill" ]]; then
    local i pid
    for ((i = 0; i < ${#PROVIDER_PIDS[@]}; i++)); do
      if [[ "${PROVIDER_NAMES[$i]}" != "account" ]]; then
        continue
      fi
      pid="${PROVIDER_PIDS[$i]}"
      if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
        kill -KILL "${pid}" >/dev/null 2>&1 || true
        wait "${pid}" >/dev/null 2>&1 || true
      fi
      PROVIDER_PIDS[$i]=""
    done
  else
    stop_provider_by_name account
  fi
  start_provider account "${product_line}"
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

kafka_delete_records_cmd() {
  if command -v kafka-delete-records >/dev/null 2>&1; then
    echo kafka-delete-records
  else
    echo kafka-delete-records.sh
  fi
}

kafka_get_offsets_cmd() {
  if command -v kafka-get-offsets >/dev/null 2>&1; then
    echo kafka-get-offsets
  else
    echo kafka-get-offsets.sh
  fi
}

kafka_consumer_groups_cmd() {
  if command -v kafka-consumer-groups >/dev/null 2>&1; then
    echo kafka-consumer-groups
  else
    echo kafka-consumer-groups.sh
  fi
}

kafka_ready() {
  "$(kafka_topics_cmd)" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list >/dev/null
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
  # readiness 组只判断服务是否已完成启动；聚合 health 还可能包含可选的审计/投影指标。
  until curl -fsS "http://localhost:${port}/actuator/health/readiness" | grep -q 'UP'; do
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${name} health on port ${port}" >&2
      tail -n 160 "${log_file}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

wait_stress_mark_price_readiness() {
  local product_line="$1"
  local deadline=$((SECONDS + STRESS_WAIT_SECONDS))
  local port ready
  while true; do
    ready=true
    for port in 9085 9084; do
      if ! curl -fsS "http://localhost:${port}/actuator/health/readiness" | grep -q '"status":"UP"'; then
        ready=false
        break
      fi
    done
    if [[ "${ready}" == "true" ]]; then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${product_line} mark-price readiness" >&2
      tail -n 160 "${TMP_DIR}/${product_line}-matching.log" >&2 || true
      tail -n 160 "${TMP_DIR}/${product_line}-trading-entry.log" >&2 || true
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
  local product_line="${1:-}"
  psql_exec <<'SQL' >/dev/null
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
SQL
  psql_exec -f "${ROOT_DIR}/init.sql" >/dev/null
  reset_runtime_sequences
  seed_product_instruments
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" && -n "${product_line}" ]]; then
    seed_stress_symbols "${product_line}"
  fi
}

reset_runtime_sequences() {
  psql_exec <<SQL >/dev/null
ALTER SEQUENCE IF EXISTS trading_order_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_command_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_event_seq RESTART WITH ${RUN_SEQUENCE_BASE};
ALTER SEQUENCE IF EXISTS trading_outbox_seq RESTART WITH ${RUN_SEQUENCE_BASE};
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

INSERT INTO instrument_index_sources (
    symbol, version, source, enabled, base_url, path, source_symbol, parser,
    quote_currency, target_quote_currency, conversion_base_url, conversion_path,
    conversion_parser, conversion_mode, conversion_operation, fallback_weight_multiplier_ppm,
    websocket_enabled, websocket_url, websocket_subscribe_message, websocket_parser, weight_ppm
) VALUES
('BTC-USDT-260925', 1, 'SYNTHETIC', TRUE, 'http://localhost:9082', '/api/v1/index', 'BTC-USDT', 'SYNTHETIC',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 FALSE, NULL, NULL, NULL, 1000000),
('BTC-USDT-260925-59000-C', 1, 'SYNTHETIC', TRUE, 'http://localhost:9082', '/api/v1/index', 'BTC-USDT', 'SYNTHETIC',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 FALSE, NULL, NULL, NULL, 1000000),
('BTC-USDT-SPOT', 1, 'SYNTHETIC', TRUE, 'http://localhost:9082', '/api/v1/index', 'BTC-USDT', 'SYNTHETIC',
 'USDT', 'USDT', NULL, NULL, NULL, 'DISCOUNT', 'MULTIPLY', 500000,
 FALSE, NULL, NULL, NULL, 1000000)
ON CONFLICT (symbol, version, source) DO NOTHING;
SQL
}

stress_symbol_for_index() {
  local product_line="$1"
  local index="$2"
  python3 - "${product_line}" "${index}" <<'PY'
import sys

product_line = sys.argv[1]
i = int(sys.argv[2])
bases = [
    "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "TRX", "TON", "AVAX",
    "LINK", "BCH", "DOT", "LTC", "NEAR", "UNI", "AAVE", "ETC", "FIL", "OP",
]
base = bases[i % len(bases)]
if product_line == "SPOT":
    print(f"{base}-USDT-SPOT")
elif product_line == "LINEAR_PERPETUAL":
    print(f"{base}-USDT")
elif product_line == "LINEAR_DELIVERY":
    print(f"{base}-USDT-260925")
elif product_line == "OPTION":
    strike = 59000 + i * 500
    option_type = "C" if i % 2 == 0 else "P"
    print(f"{base}-USDT-260925-{strike}-{option_type}")
else:
    raise SystemExit(f"unsupported stress product line {product_line}")
PY

}

stress_price_ticks_for_index() {
  local product_line="$1"
  local index="$2"
  python3 - "${product_line}" "${index}" <<'PY'
import sys

product_line = sys.argv[1]
i = int(sys.argv[2])
underlying_prices = [
    600000, 30000, 1500, 1000, 6000, 1000, 1200, 1000, 3000, 3000,
    1500, 4500, 1000, 8500, 1000, 1000, 9000, 2000, 1000, 1000,
]
if product_line == "OPTION":
    print(1000 + i * 25)
else:
    print(underlying_prices[i % len(underlying_prices)])
PY
}

seed_stress_symbols() {
  local product_line="$1"
  python3 - "${product_line}" "${STRESS_SYMBOL_COUNT}" <<'PY' | psql_exec >/dev/null
import sys

product_line = sys.argv[1]
count = int(sys.argv[2])
bases = [
    "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "TRX", "TON", "AVAX",
    "LINK", "BCH", "DOT", "LTC", "NEAR", "UNI", "AAVE", "ETC", "FIL", "OP",
]
underlying_prices = [
    600000, 30000, 1500, 1000, 6000, 1000, 1200, 1000, 3000, 3000,
    1500, 4500, 1000, 8500, 1000, 1000, 9000, 2000, 1000, 1000,
]

def quote(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"

def instrument(product_line, i):
    base = bases[i % len(bases)]
    underlying_ticks = underlying_prices[i % len(underlying_prices)]
    if product_line == "SPOT":
        return {
            "symbol": f"{base}-USDT-SPOT",
            "instrument_type": "SPOT",
            "contract_type": "SPOT",
            "base": base,
            "quote": "USDT",
            "settle": "USDT",
            "price_ticks": underlying_ticks,
            "price_tick_units": 10000000,
            "reduce_only": "FALSE",
            "market_order": "FALSE",
            "expiry": "NULL",
            "delivery": "NULL",
            "underlying": None,
            "strike": "NULL",
            "option_type": None,
            "exercise": None,
            "settlement": "NULL",
        }
    if product_line == "LINEAR_PERPETUAL":
        return {
            "symbol": f"{base}-USDT",
            "instrument_type": "PERPETUAL",
            "contract_type": "LINEAR_PERPETUAL",
            "base": base,
            "quote": "USDT",
            "settle": "USDT",
            "price_ticks": underlying_ticks,
            "price_tick_units": 10000000,
            "reduce_only": "TRUE",
            "market_order": "TRUE",
            "expiry": "NULL",
            "delivery": "NULL",
            "underlying": None,
            "strike": "NULL",
            "option_type": None,
            "exercise": None,
            "settlement": "NULL",
        }
    if product_line == "LINEAR_DELIVERY":
        return {
            "symbol": f"{base}-USDT-260925",
            "instrument_type": "DELIVERY",
            "contract_type": "LINEAR_DELIVERY",
            "base": base,
            "quote": "USDT",
            "settle": "USDT",
            "price_ticks": underlying_ticks,
            "price_tick_units": 10000000,
            "reduce_only": "TRUE",
            "market_order": "TRUE",
            "expiry": "now() + interval '30 days'",
            "delivery": "now() + interval '30 days'",
            "underlying": None,
            "strike": "NULL",
            "option_type": None,
            "exercise": None,
            "settlement": "'CASH'",
        }
    if product_line == "OPTION":
        strike = 59000 + i * 500
        option_type = "CALL" if i % 2 == 0 else "PUT"
        return {
            "symbol": f"{base}-USDT-260925-{strike}-{'C' if option_type == 'CALL' else 'P'}",
            "instrument_type": "OPTION",
            "contract_type": "VANILLA_OPTION",
            "base": base,
            "quote": "USDT",
            "settle": "USDT",
            "price_ticks": 1000 + i * 25,
            "price_tick_units": 10000000,
            "reduce_only": "TRUE",
            "market_order": "TRUE",
            "expiry": "now() + interval '30 days'",
            "delivery": "now() + interval '30 days'",
            "underlying": f"{base}-USDT",
            "strike": str(strike * 100000000),
            "option_type": option_type,
            "exercise": "EUROPEAN",
            "settlement": "'CASH'",
            "underlying_ticks": underlying_ticks,
        }
    raise SystemExit(f"unsupported stress product line {product_line}")

rows = [instrument(product_line, i) for i in range(count)]
print("INSERT INTO instruments (")
print("    symbol, version, instrument_type, contract_type, base_asset, quote_asset, settle_asset,")
print("    contract_multiplier_ppm, contract_value_asset, price_tick_units, quantity_step_units,")
print("    min_quantity_steps, max_quantity_steps, min_notional_units, max_notional_units,")
print("    notional_multiplier_units, price_precision, quantity_precision,")
print("    supported_order_types, supported_time_in_force, post_only_enabled, reduce_only_enabled, market_order_enabled,")
print("    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm,")
print("    maker_fee_rate_ppm, taker_fee_rate_ppm, max_position_notional_units,")
print("    user_open_interest_limit_rate_ppm, user_open_interest_limit_floor_units,")
print("    funding_interval_hours, interest_rate_ppm, funding_rate_cap_ppm, funding_rate_floor_ppm,")
print("    impact_notional_units, min_valid_index_sources,")
print("    expiry_time, delivery_time, underlying_symbol, strike_price_units, option_type, option_exercise_style,")
print("    settlement_method, status, effective_time, created_at, updated_at")
print(") VALUES")
values = []
for r in rows:
    values.append(
        "("
        f"{quote(r['symbol'])}, 1, {quote(r['instrument_type'])}, {quote(r['contract_type'])}, "
        f"{quote(r['base'])}, {quote(r['quote'])}, {quote(r['settle'])}, "
        "1000000, 'USDT', "
        f"{r['price_tick_units']}, 100000, 1, 1000000, 1, 1000000000000000, "
        "10000, 1, 3, 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, "
        f"{r['reduce_only']}, {r['market_order']}, "
        "100000000, 10000, 5000, 200, 500, 1000000000000000000, 1000000, 1000000000000000000, "
        "8, 0, 0, 0, 1000000000000, 1, "
        f"{r['expiry']}, {r['delivery']}, {quote(r['underlying'])}, {r['strike']}, "
        f"{quote(r['option_type'])}, {quote(r['exercise'])}, {r['settlement']}, "
        "'TRADING', now(), now(), now())"
    )
print(",\n".join(values))
print("""ON CONFLICT (symbol, version) DO UPDATE SET
    instrument_type = EXCLUDED.instrument_type,
    contract_type = EXCLUDED.contract_type,
    base_asset = EXCLUDED.base_asset,
    quote_asset = EXCLUDED.quote_asset,
    settle_asset = EXCLUDED.settle_asset,
    contract_multiplier_ppm = EXCLUDED.contract_multiplier_ppm,
    contract_value_asset = EXCLUDED.contract_value_asset,
    price_tick_units = EXCLUDED.price_tick_units,
    quantity_step_units = EXCLUDED.quantity_step_units,
    min_quantity_steps = EXCLUDED.min_quantity_steps,
    max_quantity_steps = EXCLUDED.max_quantity_steps,
    min_notional_units = EXCLUDED.min_notional_units,
    max_notional_units = EXCLUDED.max_notional_units,
    notional_multiplier_units = EXCLUDED.notional_multiplier_units,
    supported_order_types = EXCLUDED.supported_order_types,
    supported_time_in_force = EXCLUDED.supported_time_in_force,
    post_only_enabled = EXCLUDED.post_only_enabled,
    reduce_only_enabled = EXCLUDED.reduce_only_enabled,
    market_order_enabled = EXCLUDED.market_order_enabled,
    max_leverage_ppm = EXCLUDED.max_leverage_ppm,
    initial_margin_rate_ppm = EXCLUDED.initial_margin_rate_ppm,
    maintenance_margin_rate_ppm = EXCLUDED.maintenance_margin_rate_ppm,
    maker_fee_rate_ppm = EXCLUDED.maker_fee_rate_ppm,
    taker_fee_rate_ppm = EXCLUDED.taker_fee_rate_ppm,
    max_position_notional_units = EXCLUDED.max_position_notional_units,
    user_open_interest_limit_rate_ppm = EXCLUDED.user_open_interest_limit_rate_ppm,
    user_open_interest_limit_floor_units = EXCLUDED.user_open_interest_limit_floor_units,
    funding_interval_hours = EXCLUDED.funding_interval_hours,
    interest_rate_ppm = EXCLUDED.interest_rate_ppm,
    funding_rate_cap_ppm = EXCLUDED.funding_rate_cap_ppm,
    funding_rate_floor_ppm = EXCLUDED.funding_rate_floor_ppm,
    impact_notional_units = EXCLUDED.impact_notional_units,
    min_valid_index_sources = EXCLUDED.min_valid_index_sources,
    expiry_time = EXCLUDED.expiry_time,
    delivery_time = EXCLUDED.delivery_time,
    underlying_symbol = EXCLUDED.underlying_symbol,
    strike_price_units = EXCLUDED.strike_price_units,
    option_type = EXCLUDED.option_type,
    option_exercise_style = EXCLUDED.option_exercise_style,
    settlement_method = EXCLUDED.settlement_method,
    status = EXCLUDED.status,
    updated_at = now();""")

print("INSERT INTO instrument_current_versions (symbol, version, updated_at) VALUES")
print(",\n".join(f"({quote(r['symbol'])}, 1, now())" for r in rows))
print("ON CONFLICT (symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;")

print("INSERT INTO instrument_product_current_versions (product_line, symbol, version, updated_at) VALUES")
print(",\n".join(f"({quote(product_line)}, {quote(r['symbol'])}, 1, now())" for r in rows))
print("ON CONFLICT (product_line, symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;")

print("INSERT INTO instrument_symbol_sequences (symbol, version, updated_at) VALUES")
print(",\n".join(f"({quote(r['symbol'])}, 1, now())" for r in rows))
print("ON CONFLICT (symbol) DO UPDATE SET version = GREATEST(instrument_symbol_sequences.version, EXCLUDED.version), updated_at = EXCLUDED.updated_at;")

print("INSERT INTO instrument_risk_brackets (symbol, version, bracket_no, notional_floor_units, notional_cap_units, max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm) VALUES")
print(",\n".join(
    f"({quote(r['symbol'])}, 1, 1, 0, 1000000000000000000, "
    f"{'1000000' if product_line == 'SPOT' else '100000000'}, "
    f"{'1000000' if product_line == 'SPOT' else '10000'}, "
    f"{'1000000' if product_line == 'SPOT' else '5000'})"
    for r in rows
))
print("ON CONFLICT (symbol, version, bracket_no) DO NOTHING;")
PY
  seed_stress_prices "${product_line}"
}

stress_rules_json() {
  local product_line="$1"
  psql_exec -At <<SQL
SELECT COALESCE(jsonb_object_agg(i.symbol, jsonb_build_object(
           'minQuantitySteps', i.min_quantity_steps,
           'maxQuantitySteps', i.max_quantity_steps,
           'minNotionalUnits', i.min_notional_units,
           'notionalMultiplierUnits', i.notional_multiplier_units
       ))::text, '{}')
  FROM instrument_product_current_versions pcv
  JOIN instruments i ON i.symbol = pcv.symbol AND i.version = pcv.version
 WHERE pcv.product_line = '${product_line}';
SQL
}

stress_quantity_steps_for_symbol() {
  local product_line="$1"
  local symbol="$2"
  local price_ticks="$3"
  local configured_steps="$4"
  query_value "
SELECT GREATEST(
           ${configured_steps},
           min_quantity_steps,
           CEIL(min_notional_units::numeric / GREATEST(1, ${price_ticks}::numeric * notional_multiplier_units::numeric))::bigint
       )
  FROM instruments i
  JOIN instrument_product_current_versions pcv ON pcv.symbol = i.symbol AND pcv.version = i.version
 WHERE pcv.product_line = '${product_line}'
   AND i.symbol = '${symbol}'"
}

emit_stress_price_payloads() {
  local product_line="$1"
  # Price consumers reject out-of-order events.  Use wall-clock milliseconds
  # rather than the smoke run id so a fresh run always supersedes retained
  # Kafka records from an earlier run.
  local price_sequence_base
  price_sequence_base="$(current_epoch_millis)"
  python3 - "${product_line}" "${STRESS_SYMBOL_COUNT}" "${price_sequence_base}" \
    "${STRESS_MARK_PRICE_FACTOR_PPM}" <<'PY'
import datetime
import decimal
import json
import sys

product_line = sys.argv[1]
count = int(sys.argv[2])
run_seq = int(sys.argv[3])
price_factor_ppm = int(sys.argv[4])
bases = [
    "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "TRX", "TON", "AVAX",
    "LINK", "BCH", "DOT", "LTC", "NEAR", "UNI", "AAVE", "ETC", "FIL", "OP",
]
underlying_prices = [
    600000, 30000, 1500, 1000, 6000, 1000, 1200, 1000, 3000, 3000,
    1500, 4500, 1000, 8500, 1000, 1000, 9000, 2000, 1000, 1000,
]

def symbol_and_price(i):
    base = bases[i % len(bases)]
    underlying_ticks = underlying_prices[i % len(underlying_prices)]
    if product_line == "SPOT":
        return f"{base}-USDT-SPOT", underlying_ticks, None, None
    if product_line == "LINEAR_PERPETUAL":
        return f"{base}-USDT", underlying_ticks, None, None
    if product_line == "LINEAR_DELIVERY":
        return f"{base}-USDT-260925", underlying_ticks, None, None
    if product_line == "OPTION":
        strike = 59000 + i * 500
        option_type = "C" if i % 2 == 0 else "P"
        return f"{base}-USDT-260925-{strike}-{option_type}", 1000 + i * 25, f"{base}-USDT", underlying_ticks
    raise SystemExit(f"unsupported stress product line {product_line}")

price_rows = []
for i in range(count):
    symbol, ticks, underlying, underlying_ticks = symbol_and_price(i)
    ticks = max(1, ticks * price_factor_ppm // 1_000_000)
    if underlying_ticks is not None:
        underlying_ticks = max(1, underlying_ticks * price_factor_ppm // 1_000_000)
    # The mark-price consumers discard non-increasing sequences.  The refresher
    # runs throughout a stress test, so derive every per-symbol sequence from
    # the wall-clock millisecond base without truncating it.  Multiplication
    # reserves enough consecutive values for all instruments emitted in a pass.
    price_rows.append((symbol, ticks, run_seq * 100 + i))
    if underlying:
        price_rows.append((underlying, underlying_ticks, run_seq * 100 + count + i))

deduped = {}
for symbol, ticks, sequence in price_rows:
    deduped[symbol] = (ticks, sequence)

event_time = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
tick_units = 10_000_000
scale = decimal.Decimal(100_000_000)
for symbol, (ticks, sequence) in deduped.items():
    price = (decimal.Decimal(ticks * tick_units) / scale).quantize(decimal.Decimal("0.00000001"))
    price_value = float(price)
    payload = {
        # 标记价由服务根据指数价、盘口和成交快照计算；这里仅注入指数事件。
        "symbol": symbol, "indexPrice": price_value, "sequence": sequence,
        "status": "HEALTHY", "componentCount": 1, "validComponentCount": 1,
        "totalConfiguredWeight": 1, "eventTime": event_time, "components": [],
    }
    try:
        print(f"{symbol}:{json.dumps(payload, separators=(',', ':'))}")
    except BrokenPipeError:
        # 强平结束时生产者可能已关闭管道，刷新线程应安静退出而不是打印误导性堆栈。
        sys.exit(0)
PY
}

seed_stress_prices() {
  local product_line="$1"
  local producer_cmd topic
  # 当前生产链路由指数价计算服务生成标记价；烟测必须从指数价入口注入，
  # 不能绕过标记价计算器直接伪造标记价事件。
  topic="$(topic_name "${product_line}" "index.price")"
  producer_cmd="$(kafka_producer_cmd)"
  emit_stress_price_payloads "${product_line}" | "${producer_cmd}" \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
    --topic "${topic}" \
    --property parse.key=true \
    --property key.separator=: >/dev/null
}

delete_topic_list() {
  local topics="$1"
  local topics_cmd
  topics_cmd="$(kafka_topics_cmd)"
  if [[ -z "${topics}" ]]; then
    return
  fi
  while IFS= read -r topic; do
    [[ -n "${topic}" ]] || continue
    "${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --delete --if-exists --topic "${topic}" >/dev/null 2>&1 || true
  done <<<"${topics}"
  local deadline=$((SECONDS + KAFKA_TOPIC_RESET_TIMEOUT_SECONDS))
  while true; do
    local current_topics remaining=false topic
    current_topics="$(${topics_cmd} --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null || true)"
    while IFS= read -r topic; do
      [[ -n "${topic}" ]] || continue
      if grep -Fqx -- "${topic}" <<<"${current_topics}"; then
        remaining=true
        break
      fi
    done <<<"${topics}"
    if [[ "${remaining}" == "false" ]]; then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for Kafka topics to be deleted before recreation" >&2
      return 1
    fi
    sleep 1
  done
}

delete_surprising_topics() {
  local product_line="${1:-}"
  local topics_cmd
  topics_cmd="$(kafka_topics_cmd)"
  local topics
  topics="$("${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null | grep '^surprising\.' || true)"
  if [[ -n "${product_line}" ]]; then
    local product_topic_line
    product_topic_line="$(product_slug "${product_line}")"
    topics="$(printf '%s\n' "${topics}" | grep -E "^surprising\\.${product_topic_line}\\." || true)"
    if [[ "${KAFKA_RESET_SHARED_TOPICS}" == "true" ]]; then
      topics+=$'\n'
      topics+="surprising.instrument.events.v1"$'\n'
      topics+="surprising.account.position.events.v1"$'\n'
      topics+="surprising.account.liquidation-fee.events.v1"$'\n'
      topics+="surprising.account.state.events.v1"$'\n'
      topics+="surprising.risk.account.events.v1"$'\n'
      topics+="surprising.risk.position.events.v1"
    fi
    if [[ "${product_line}" == "LINEAR_PERPETUAL" && "${KAFKA_RESET_LEGACY_PERP_TOPICS}" == "true" ]]; then
      local legacy_topics
      legacy_topics="$("${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null | grep '^surprising\.perp\.' || true)"
      if [[ -n "${legacy_topics}" ]]; then
        topics+=$'\n'
        topics+="${legacy_topics}"
      fi
    fi
  fi
  if [[ -z "${topics}" ]]; then
    return
  fi
  while IFS= read -r topic; do
    [[ -n "${topic}" ]] || continue
    "${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --delete --if-exists --topic "${topic}" >/dev/null 2>&1 || true
  done <<<"${topics}"

  local deadline=$((SECONDS + KAFKA_TOPIC_RESET_TIMEOUT_SECONDS))
  while true; do
    local current_topics remaining=false topic
    current_topics="$("${topics_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null || true)"
    while IFS= read -r topic; do
      [[ -n "${topic}" ]] || continue
      if grep -Fqx -- "${topic}" <<<"${current_topics}"; then
        remaining=true
        break
      fi
    done <<<"${topics}"
    if [[ "${remaining}" == "false" ]]; then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for Kafka topics to be deleted before recreation" >&2
      return 1
    fi
    sleep 1
  done
}

truncate_surprising_topics() {
  local product_line="${1:-}"
  local topics_cmd offsets_cmd delete_records_cmd topics all_offsets offsets_json
  local truncatable_topics="" compacted_topics="" topic
  topics_cmd="$(kafka_topics_cmd)"
  offsets_cmd="$(kafka_get_offsets_cmd)"
  delete_records_cmd="$(kafka_delete_records_cmd)"
  topics="$(${topics_cmd} --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null | grep '^surprising\.' || true)"
  if [[ -n "${product_line}" ]]; then
    local product_topic_line
    product_topic_line="$(product_slug "${product_line}")"
    topics="$(printf '%s\n' "${topics}" | grep -E "^surprising\\.${product_topic_line}\\." || true)"
    if [[ "${KAFKA_RESET_SHARED_TOPICS}" == "true" ]]; then
      topics+=$'\n'
      topics+="surprising.instrument.events.v1"$'\n'
      topics+="surprising.account.position.events.v1"$'\n'
      topics+="surprising.account.liquidation-fee.events.v1"$'\n'
      topics+="surprising.account.state.events.v1"$'\n'
      topics+="surprising.risk.account.events.v1"$'\n'
      topics+="surprising.risk.position.events.v1"
    fi
    if [[ "${product_line}" == "LINEAR_PERPETUAL" && "${KAFKA_RESET_LEGACY_PERP_TOPICS}" == "true" ]]; then
      local legacy_topics
      legacy_topics="$(${topics_cmd} --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" --list 2>/dev/null | grep '^surprising\\.perp\\.' || true)"
      if [[ -n "${legacy_topics}" ]]; then
        topics+=$'\n'
        topics+="${legacy_topics}"
      fi
    fi
  fi
  if [[ -z "${topics}" ]]; then
    return
  fi
  while IFS= read -r topic; do
    [[ -n "${topic}" ]] || continue
    case "${topic}" in
      *.order.state.events.v1|*.account.state.events.v1|surprising.instrument.events.v1)
        compacted_topics+="${topic}"$'\n'
        ;;
      *)
        truncatable_topics+="${topic}"$'\n'
        ;;
    esac
  done < <(printf '%s\n' "${topics}" | sort -u)
  delete_topic_list "${compacted_topics}"
  if [[ -z "${truncatable_topics}" ]]; then
    return
  fi
  all_offsets="${TMP_DIR}/kafka-latest-offsets.txt"
  offsets_json="${TMP_DIR}/kafka-delete-records.json"
  "${offsets_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
    --time -1 >"${all_offsets}"
  python3 - "${all_offsets}" "${offsets_json}" ${truncatable_topics} <<'PY'
import json
import pathlib
import sys

offsets_path = pathlib.Path(sys.argv[1])
output_path = pathlib.Path(sys.argv[2])
selected = set(sys.argv[3:])
partitions = []
for line in offsets_path.read_text().splitlines():
    topic, partition, offset = line.rsplit(":", 2)
    if topic in selected:
        partitions.append({"topic": topic, "partition": int(partition), "offset": int(offset)})
output_path.write_text(json.dumps({"partitions": partitions, "version": 1}))
PY
  if [[ "$(python3 - "${offsets_json}" <<'PY'
import json
import sys
print(len(json.load(open(sys.argv[1]))["partitions"]))
PY
)" != "0" ]]; then
    "${delete_records_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
      --offset-json-file "${offsets_json}" >/dev/null
  fi
}

create_topics() {
  local product_line="$1"
  local product_topic_line symbol_count=0 stream_threads=2
  product_topic_line="$(product_slug "${product_line}")"
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    symbol_count="${STRESS_SYMBOL_COUNT}"
    stream_threads="${STRESS_MATCHING_KAFKA_CONCURRENCY}"
  fi
  BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    REPLICATION_FACTOR=1 \
    SYMBOL_COUNT="${symbol_count}" \
    STREAM_THREADS="${stream_threads}" \
    INCLUDE_SHARED_TOPICS="${KAFKA_INCLUDE_SHARED_TOPICS}" \
    INCLUDE_LEGACY_PERP_TOPICS="${KAFKA_INCLUDE_LEGACY_PERP_TOPICS}" \
    INCLUDE_PRODUCT_TOPICS=true \
    PRODUCT_TOPIC_LINES="${product_topic_line}" \
    "${ROOT_DIR}/scripts/create-topics.sh" >/dev/null
}

wait_product_topics_ready() {
  local product_line="$1"
  local product_topic_line prefix topic topics ready=false topic_metadata
  product_topic_line="$(product_slug "${product_line}")"
  prefix="surprising.${product_topic_line}"
  # 启动前检查当前产品线全部会被 provider 使用的 Topic；确认 leader 已选出后再启动，
  # 避免刚建 Topic 时 Producer 把短暂的 metadata 未就绪误判为业务失败。
  topics=(
    "${prefix}.trade.events.v1"
    "${prefix}.candle.events.v1"
    "${prefix}.order.commands.v1"
    "${prefix}.order.events.v1"
    "${prefix}.fee.schedule.events.v1"
    "${prefix}.leverage.setting.events.v1"
    "${prefix}.trigger-order.events.v1"
    "${prefix}.order.user.commands.v1"
    "${prefix}.order.user.command.results.v1"
    "${prefix}.account.user.commands.v1"
    "${prefix}.account.user.commands.dlt.v1"
    "${prefix}.account.command.results.v1"
    "${prefix}.match.results.v1"
    "${prefix}.match.trades.v1"
    "${prefix}.orderbook.depth.v1"
    "${prefix}.mark.price.v1"
    "${prefix}.order.state.events.v1"
  )
  if is_margin_product "${product_line}"; then
    topics+=(
      "${prefix}.index.price.v1"
      "${prefix}.book.ticker.v1"
      "${prefix}.account.position.events.v1"
      "${prefix}.account.open-interest.events.v1"
      "${prefix}.account.liquidation-fee.events.v1"
      "${prefix}.account.state.events.v1"
      "${prefix}.risk.account.events.v1"
      "${prefix}.risk.position.events.v1"
      "${prefix}.liquidation.candidates.v1"
    )
  fi
  if is_funding_product "${product_line}"; then
    topics+=("${prefix}.funding.rate.v1")
  elif is_delivery_product "${product_line}"; then
    topics+=("${prefix}.delivery.settlements.v1")
  elif is_option_product "${product_line}"; then
    topics+=("${prefix}.option.exercises.v1")
  fi
  local deadline=$((SECONDS + 90))
  while (( SECONDS < deadline )); do
    ready=true
    # Kafka CLI 每次启动 JVM 成本很高，必须一次性读取元数据，否则多分区 Topic
    # 会让逐 Topic 检查超过等待窗口。
    topic_metadata="$(kafka-topics --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
      --describe 2>/dev/null || true)"
    for topic in "${topics[@]}"; do
      if ! printf '%s\n' "${topic_metadata}" \
          | grep -F "${topic}" \
          | grep -qE 'Leader: [0-9]'; then
        ready=false
        break
      fi
    done
    if [[ "${ready}" == "true" ]]; then
      # 主题 leader 已出现后还需要给单节点 Kafka 加载全部分区日志；
      # 再启动撮合，避免首个盘口快照发送时仍收到 UNKNOWN_TOPIC_OR_PARTITION。
      # 这里不能只依赖 describe 返回，因为 Kafka 元数据传播和日志目录初始化
      # 可能比 leader 选举晚几个心跳周期。
      sleep 30
      return
    fi
    sleep 1
  done
  echo "Kafka product topics leader 在等待窗口内未就绪: ${product_line}" >&2
  return 1
}

reset_kafka_topics() {
  local product_line="$1"
  if [[ "${RESET_KAFKA}" == "true" ]]; then
    delete_surprising_topics "${product_line}"
    if [[ "${KAFKA_RESET_SHARED_TOPICS}" == "true" ]]; then
      local groups_cmd
      groups_cmd="$(kafka_consumer_groups_cmd)"
      "${groups_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
        --delete --group "$(product_slug "${product_line}")-account-state-projection-v1" \
        >/dev/null 2>&1 || true
    fi
  elif [[ "${KAFKA_TRUNCATE_TOPICS}" == "true" ]]; then
    truncate_surprising_topics "${product_line}"
    if [[ "${KAFKA_RESET_SHARED_TOPICS}" == "true" ]]; then
      local groups_cmd
      groups_cmd="$(kafka_consumer_groups_cmd)"
      "${groups_cmd}" --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
        --delete --group "$(product_slug "${product_line}")-account-state-projection-v1" \
        >/dev/null 2>&1 || true
    fi
  fi
  if [[ "${CREATE_KAFKA_TOPICS}" == "true" ]]; then
    create_topics "${product_line}"
  fi
  wait_product_topics_ready "${product_line}"
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
    price) echo "surprising-price/surprising-price-provider" ;;
    mark-price) echo "surprising-price/surprising-mark-price-provider" ;;
    trading-entry) echo "surprising-trading/surprising-trading-entry-provider" ;;
    order) echo "surprising-trading/surprising-order-provider" ;;
    matching) echo "surprising-trading/surprising-matching-provider" ;;
    account) echo "surprising-account/surprising-account-provider" ;;
    margin-ops) echo "surprising-margin-ops/surprising-margin-ops-provider" ;;
    risk) echo "surprising-margin-ops/surprising-risk-provider" ;;
    liquidation) echo "surprising-margin-ops/surprising-liquidation-provider" ;;
    insurance) echo "surprising-margin-ops/surprising-insurance-provider" ;;
    funding) echo "surprising-margin-ops/surprising-funding-provider" ;;
    edge) echo "surprising-edge/surprising-edge-provider" ;;
    gateway) echo "surprising-edge/surprising-gateway/surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker/surprising-market-maker-provider" ;;
    *) echo "unknown provider: $1" >&2; exit 1 ;;
  esac
}

provider_artifact() {
  case "$1" in
    instrument) echo "surprising-instrument-provider" ;;
    price) echo "surprising-price-provider" ;;
    mark-price) echo "surprising-mark-price-provider" ;;
    trading-entry) echo "surprising-trading-entry-provider" ;;
    order) echo "surprising-order-provider" ;;
    matching) echo "surprising-matching-provider" ;;
    account) echo "surprising-account-provider" ;;
    margin-ops) echo "surprising-margin-ops-provider" ;;
    risk) echo "surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation-provider" ;;
    insurance) echo "surprising-insurance-provider" ;;
    funding) echo "surprising-funding-provider" ;;
    edge) echo "surprising-edge-provider" ;;
    gateway) echo "surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker-provider" ;;
    *) echo "unknown provider: $1" >&2; exit 1 ;;
  esac
}

provider_port() {
  case "$1" in
    instrument) echo 9080 ;;
    price) echo 9082 ;;
    mark-price) echo 9083 ;;
    trading-entry) echo 9084 ;;
    order) echo 9084 ;;
    matching) echo 9085 ;;
    account) echo 9086 ;;
    margin-ops) echo 9088 ;;
    risk) echo 9087 ;;
    liquidation) echo 9088 ;;
    insurance) echo 9090 ;;
    funding) echo 9089 ;;
    edge) echo 9094 ;;
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
  local stale=false
  local name
  for name in instrument price trading-entry matching account margin-ops edge market-maker; do
    local jar
    jar="$(boot_jar "$(provider_module "${name}")" "$(provider_artifact "${name}")" 2>/dev/null || true)"
    if [[ -z "${jar}" ]]; then
      missing+=(":$(provider_artifact "${name}")")
    elif find "${ROOT_DIR}" -type f \( -path '*/src/main/*' -o -name 'pom.xml' \) \
        -newer "${jar}" -print -quit | grep -q .; then
      stale=true
    fi
  done
  if [[ "${BUILD_SERVICES}" == "auto" && ${#missing[@]} -eq 0 && "${stale}" == "false" ]]; then
    echo "Provider jars are current; skipping Maven package (BUILD_SERVICES=auto)"
    return
  fi
  local selectors
  if [[ "${BUILD_SERVICES}" == "true" || "${stale}" == "true" ]]; then
    selectors=":surprising-instrument-provider,:surprising-price-provider,:surprising-trading-entry-provider,:surprising-matching-provider,:surprising-account-provider,:surprising-margin-ops-provider,:surprising-edge-provider,:surprising-market-maker-provider"
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
    price)
      printf '%s\n' \
        "--surprising.price.index.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.price.index.kafka.product-line=${product_line}" \
        "--surprising.price.index.kafka.product-topics-enabled=true" \
        "--surprising.price.index.coordination.node-id=product-smoke-${RUN_ID}-${slug}-index" \
        "--surprising.price.index.web-socket.enabled=false" \
        "--surprising.price.index.calculation.poll-delay-ms=600000" \
        "--surprising.price.mark.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.price.mark.kafka.product-line=${product_line}" \
        "--surprising.price.mark.kafka.product-topics-enabled=true" \
        "--surprising.price.mark.kafka.group-id=product-smoke-${RUN_ID}-${slug}-mark-price" \
        "--surprising.price.mark.coordination.node-id=product-smoke-${RUN_ID}-${slug}-mark" \
        "--spring.kafka.listener.auto-startup=false"
      ;;
    trading-entry)
      printf '%s\n' \
        "--surprising.clients.order.base-url=http://localhost:9084" \
        "--surprising.clients.trigger.base-url=http://localhost:9084" \
        "--surprising.trading.order.wal.directory=${TMP_DIR}/order-wal" \
        "--surprising.trading.order.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.trading.order.kafka.product-line=${product_line}" \
        "--surprising.trading.order.kafka.product-topics-enabled=true" \
        "--surprising.trading.order.outbox.batch-size=${STRESS_ORDER_OUTBOX_BATCH_SIZE}" \
        "--surprising.trading.order.outbox.publish-delay-ms=${STRESS_ORDER_OUTBOX_PUBLISH_DELAY_MS}" \
        "--surprising.trading.order.outbox.max-in-flight=${STRESS_ORDER_OUTBOX_MAX_IN_FLIGHT}" \
        "--surprising.trading.order.outbox.max-rows-per-key=${STRESS_ORDER_OUTBOX_MAX_ROWS_PER_KEY}" \
        "--surprising.trading.order.kafka.user-command-concurrency=${ACCOUNT_COMMAND_PARTITIONS}" \
        "--surprising.trading.order.risk.market-max-mark-age-ms=30000" \
        "--surprising.trading.order.risk.limit-price-max-mark-age-ms=30000" \
        "--surprising.price.consumer.concurrency=4" \
        "--surprising.trading.trigger.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.trading.trigger.kafka.product-line=${product_line}" \
        "--surprising.trading.trigger.kafka.product-topics-enabled=true" \
        "--surprising.trading.trigger.kafka.group-id=product-smoke-${RUN_ID}-${slug}-trigger"
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        local readiness_index readiness_symbol
        for ((readiness_index = 0; readiness_index < STRESS_SYMBOL_COUNT; readiness_index++)); do
          readiness_symbol="$(stress_symbol_for_index "${product_line}" "${readiness_index}")"
          printf '%s\n' "--surprising.price.consumer.required-symbols[${readiness_index}]=${readiness_symbol}"
        done
      fi
      ;;
    order)
      printf '%s\n' \
        "--surprising.trading.order.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.trading.order.kafka.product-line=${product_line}" \
        "--surprising.trading.order.kafka.product-topics-enabled=true" \
        "--surprising.trading.order.outbox.batch-size=${STRESS_ORDER_OUTBOX_BATCH_SIZE}" \
        "--surprising.trading.order.outbox.publish-delay-ms=${STRESS_ORDER_OUTBOX_PUBLISH_DELAY_MS}" \
        "--surprising.trading.order.outbox.max-in-flight=${STRESS_ORDER_OUTBOX_MAX_IN_FLIGHT}" \
        "--surprising.trading.order.outbox.max-rows-per-key=${STRESS_ORDER_OUTBOX_MAX_ROWS_PER_KEY}" \
        "--surprising.trading.order.kafka.user-command-concurrency=${ACCOUNT_COMMAND_PARTITIONS}" \
        "--surprising.trading.order.risk.market-max-mark-age-ms=30000"
      ;;
    matching)
      local matching_concurrency=1
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        matching_concurrency="${STRESS_MATCHING_KAFKA_CONCURRENCY}"
      fi
      printf '%s\n' \
        "--surprising.trading.matching.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.trading.matching.kafka.product-line=${product_line}" \
        "--surprising.trading.matching.kafka.product-topics-enabled=true" \
        "--surprising.trading.matching.kafka.group-id=product-smoke-${RUN_ID}-${slug}-matching" \
        "--surprising.trading.matching.kafka.client-id=product-smoke-${RUN_ID}-${slug}-matching" \
        "--surprising.trading.matching.kafka.concurrency=${matching_concurrency}" \
        "--surprising.trading.matching.kafka.max-poll-records=${STRESS_MATCHING_MAX_POLL_RECORDS}" \
        "--surprising.trading.matching.protection.market-max-mark-age-ms=30000" \
        "--surprising.trading.matching.outbox.batch-size=${STRESS_MATCHING_OUTBOX_BATCH_SIZE}" \
        "--surprising.trading.matching.outbox.publish-delay-ms=${STRESS_MATCHING_OUTBOX_PUBLISH_DELAY_MS}" \
        "--surprising.trading.matching.outbox.max-in-flight=${STRESS_MATCHING_OUTBOX_MAX_IN_FLIGHT}" \
        "--surprising.trading.matching.outbox.max-rows-per-key=${STRESS_MATCHING_OUTBOX_MAX_ROWS_PER_KEY}" \
        "--surprising.trading.matching.wal.directory=${TMP_DIR}/matching-wal" \
        "--surprising.trading.matching.engine.exchange-id=product-smoke-${slug}" \
        "--surprising.trading.matching.engine.matching-engines=${STRESS_MATCHING_ENGINE_SHARDS}" \
        "--surprising.trading.matching.engine.risk-engines=${STRESS_MATCHING_RISK_SHARDS}"
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        printf '%s\n' "--surprising.price.consumer.concurrency=4"
        local readiness_index readiness_symbol
        for ((readiness_index = 0; readiness_index < STRESS_SYMBOL_COUNT; readiness_index++)); do
          readiness_symbol="$(stress_symbol_for_index "${product_line}" "${readiness_index}")"
          printf '%s\n' "--surprising.price.consumer.required-symbols[${readiness_index}]=${readiness_symbol}"
        done
      fi
      if [[ "${MULTI_SYMBOL_STRESS}" == "true"
            && "${STRESS_INTERNAL_MARKET_MAKER_WHITELIST}" == "true" ]]; then
        local maker_index maker_user
        for ((maker_index = 0; maker_index < STRESS_SYMBOL_COUNT * 2; maker_index++)); do
          if ((maker_index < STRESS_SYMBOL_COUNT)); then
            maker_user=$((STRESS_MM_USER_START + maker_index))
          else
            maker_user=$((STRESS_BOOK_USER_START + maker_index - STRESS_SYMBOL_COUNT))
          fi
          printf '%s\n' \
            "--surprising.trading.matching.protection.internal-market-maker-user-ids[${maker_index}]=${maker_user}"
        done
      fi
      ;;
    account)
      local account_concurrency=1
      local account_outbox_batch_size=200 account_outbox_publish_delay_ms=200
      local account_outbox_max_in_flight=32 account_outbox_max_rows_per_key=32
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        account_concurrency="${STRESS_ACCOUNT_KAFKA_CONCURRENCY}"
        account_outbox_batch_size="${STRESS_ACCOUNT_OUTBOX_BATCH_SIZE}"
        account_outbox_publish_delay_ms="${STRESS_ACCOUNT_OUTBOX_PUBLISH_DELAY_MS}"
        account_outbox_max_in_flight="${STRESS_ACCOUNT_OUTBOX_MAX_IN_FLIGHT}"
        account_outbox_max_rows_per_key="${STRESS_ACCOUNT_OUTBOX_MAX_ROWS_PER_KEY}"
      fi
      printf '%s\n' \
        "--surprising.account.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.account.kafka.product-line=${product_line}" \
        "--surprising.account.kafka.product-topics-enabled=true" \
        "--surprising.account.kafka.group-id=product-smoke-${RUN_ID}-${slug}-account" \
        "--surprising.account.kafka.client-id=product-smoke-${RUN_ID}-${slug}-account" \
        "--surprising.account.kafka.concurrency=${account_concurrency}" \
        "--surprising.account.kafka.user-command-concurrency=${ACCOUNT_COMMAND_PARTITIONS}" \
        "--surprising.account.wal.directory=${TMP_DIR}/account-wal" \
        "--surprising.account.outbox.batch-size=${account_outbox_batch_size}" \
        "--surprising.account.outbox.publish-delay-ms=${account_outbox_publish_delay_ms}" \
        "--surprising.account.outbox.max-in-flight=${account_outbox_max_in_flight}" \
        "--surprising.account.outbox.max-rows-per-key=${account_outbox_max_rows_per_key}" \
        "--surprising.account.expiring-settlement.settlement-price-window=PT0S"
      ;;
    risk)
      local risk_concurrency=1 risk_outbox_batch_size=200 risk_outbox_publish_delay_ms=200
      local risk_outbox_max_rows_per_key=32
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        risk_concurrency="${STRESS_RISK_KAFKA_CONCURRENCY}"
        risk_outbox_batch_size="${STRESS_RISK_OUTBOX_BATCH_SIZE}"
        risk_outbox_publish_delay_ms="${STRESS_RISK_OUTBOX_PUBLISH_DELAY_MS}"
        risk_outbox_max_rows_per_key="${STRESS_RISK_OUTBOX_MAX_ROWS_PER_KEY}"
      fi
      printf '%s\n' \
        "--surprising.risk.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.risk.kafka.product-line=${product_line}" \
        "--surprising.risk.kafka.product-topics-enabled=true" \
        "--surprising.risk.kafka.group-id=product-smoke-${RUN_ID}-${slug}-risk" \
        "--surprising.risk.kafka.concurrency=${risk_concurrency}" \
        "--surprising.risk.local-state.wal-directory=${TMP_DIR}/risk-projection-wal" \
        "--surprising.risk.calculation.scan-delay-ms=500" \
        "--surprising.risk.outbox.batch-size=${risk_outbox_batch_size}" \
        "--surprising.risk.outbox.publish-delay-ms=${risk_outbox_publish_delay_ms}" \
        "--surprising.risk.outbox.max-rows-per-key=${risk_outbox_max_rows_per_key}"
      ;;
    liquidation)
      printf '%s\n' \
        "--surprising.liquidation.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.liquidation.kafka.product-line=${product_line}" \
        "--surprising.liquidation.kafka.product-topics-enabled=true" \
        "--surprising.liquidation.kafka.group-id=product-smoke-${RUN_ID}-${slug}-liquidation" \
        "--surprising.liquidation.kafka.candidate-concurrency=1"
      ;;
    insurance)
      printf '%s\n' \
        "--surprising.insurance.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.insurance.kafka.product-line=${product_line}" \
        "--surprising.insurance.kafka.product-topics-enabled=true" \
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
    margin-ops)
      local risk_concurrency=1 risk_outbox_batch_size=200 risk_outbox_publish_delay_ms=200
      local risk_outbox_max_rows_per_key=32 funding_enabled=false
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        risk_concurrency="${STRESS_RISK_KAFKA_CONCURRENCY}"
        risk_outbox_batch_size="${STRESS_RISK_OUTBOX_BATCH_SIZE}"
        risk_outbox_publish_delay_ms="${STRESS_RISK_OUTBOX_PUBLISH_DELAY_MS}"
        risk_outbox_max_rows_per_key="${STRESS_RISK_OUTBOX_MAX_ROWS_PER_KEY}"
      fi
      if is_funding_product "${product_line}"; then
        funding_enabled=true
      fi
      printf '%s\n' \
        "--surprising.clients.risk.base-url=http://localhost:9088" \
        "--surprising.risk.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.risk.kafka.product-line=${product_line}" \
        "--surprising.risk.kafka.product-topics-enabled=true" \
        "--surprising.risk.kafka.group-id=product-smoke-${RUN_ID}-${slug}-risk" \
        "--surprising.risk.kafka.concurrency=${risk_concurrency}" \
        "--surprising.risk.local-state.wal-directory=${TMP_DIR}/risk-projection-wal" \
        "--surprising.risk.calculation.scan-delay-ms=500" \
        "--surprising.risk.outbox.batch-size=${risk_outbox_batch_size}" \
        "--surprising.risk.outbox.publish-delay-ms=${risk_outbox_publish_delay_ms}" \
        "--surprising.risk.outbox.max-rows-per-key=${risk_outbox_max_rows_per_key}" \
        "--surprising.liquidation.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.liquidation.kafka.product-line=${product_line}" \
        "--surprising.liquidation.kafka.product-topics-enabled=true" \
        "--surprising.liquidation.kafka.group-id=product-smoke-${RUN_ID}-${slug}-liquidation" \
        "--surprising.liquidation.kafka.candidate-concurrency=${STRESS_LIQUIDATION_KAFKA_CONCURRENCY}" \
        "--surprising.funding.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.funding.kafka.product-line=${product_line}" \
        "--surprising.funding.kafka.product-topics-enabled=true" \
        "--surprising.funding.calculation.enabled=false" \
        "--surprising.funding.settlement.enabled=${funding_enabled}" \
        "--surprising.funding.settlement.scan-delay-ms=500" \
        "--surprising.funding.coordination.node-id=product-smoke-${RUN_ID}-${slug}-funding" \
        "--surprising.funding.wal.directory=${TMP_DIR}/funding-wal" \
        "--surprising.insurance.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.insurance.kafka.product-line=${product_line}" \
        "--surprising.insurance.kafka.product-topics-enabled=true" \
        "--surprising.insurance.kafka.liquidation-fee-events-topic=$(topic_name "${product_line}" "account.liquidation-fee.events")" \
        "--surprising.insurance.kafka.group-id=product-smoke-${RUN_ID}-${slug}-insurance" \
        "--surprising.insurance.kafka.concurrency=1" \
        "--surprising.insurance.coverage.scan-delay-ms=500" \
        "--surprising.adl.kafka.product-line=${product_line}" \
        "--surprising.adl.kafka.product-topics-enabled=true"
      ;;
    edge)
      printf '%s\n' \
        "--surprising.websocket.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}" \
        "--surprising.websocket.kafka.product-line=${product_line}" \
        "--surprising.websocket.kafka.product-topics-enabled=true" \
        "--surprising.websocket.kafka.group-id=product-smoke-${RUN_ID}-${slug}-edge-websocket" \
        "--surprising.gateway.routes.price-mark.base-url=http://localhost:9082" \
        "--surprising.gateway.admin-routes.price-mark.base-url=http://localhost:9082" \
        "--surprising.gateway.routes.risk.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.risk.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.risk-admin.base-url=http://localhost:9088" \
        "--surprising.gateway.routes.liquidation.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.liquidation.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.liquidation-admin.base-url=http://localhost:9088" \
        "--surprising.gateway.routes.funding.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.funding.base-url=http://localhost:9088" \
        "--surprising.gateway.routes.insurance.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.insurance.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.insurance-admin.base-url=http://localhost:9088" \
        "--surprising.gateway.routes.adl.base-url=http://localhost:9088" \
        "--surprising.gateway.admin-routes.adl.base-url=http://localhost:9088"
      ;;
    market-maker)
      local symbol
      symbol="$(symbol_for "${product_line}")"
      # 单节点业务烟测不需要分布式租约，避免做市报价被非交易主链路的数据库租约阻塞。
      printf '%s\n' \
        "--surprising.clients.account.base-url=http://localhost:9086" \
        "--surprising.clients.instrument.base-url=http://localhost:9080" \
        "--surprising.clients.mark-price.base-url=http://localhost:9082" \
        "--surprising.clients.matching.base-url=http://localhost:9085" \
        "--surprising.clients.order.base-url=http://localhost:9084" \
        "--surprising.market-maker.engine.enabled=true" \
        "--surprising.market-maker.engine.cycle-delay-ms=${MARKET_MAKER_CYCLE_DELAY_MS}" \
        "--surprising.market-maker.engine.node-id=product-smoke-${RUN_ID}-${slug}-mm" \
        "--surprising.market-maker.coordination.enabled=false" \
        "--surprising.market-maker.quoting.order-levels=2" \
        "--surprising.market-maker.quoting.min-spread-ticks=20" \
        "--surprising.market-maker.quoting.level-spacing-ticks=10" \
        "--surprising.market-maker.quoting.max-open-orders-per-account-symbol=12" \
        "--surprising.price.consumer.concurrency=4" \
        "--surprising.price.consumer.max-poll-records=1000" \
        "--surprising.market-maker.trade.enabled=false"
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        local i stress_symbol stress_price maker_user maker_quantity_steps
        for ((i = 0; i < STRESS_SYMBOL_COUNT; i++)); do
          stress_symbol="$(stress_symbol_for_index "${product_line}" "${i}")"
          stress_price="$(stress_price_ticks_for_index "${product_line}" "${i}")"
          maker_user=$((STRESS_MM_USER_START + i))
          maker_quantity_steps="$(stress_quantity_steps_for_symbol "${product_line}" "${stress_symbol}" "${stress_price}" "${STRESS_TAKER_QUANTITY_STEPS}")"
          printf '%s\n' \
            "--surprising.market-maker.strategies[${i}].strategy-id=stress-mm-${i}" \
            "--surprising.market-maker.strategies[${i}].product-line=${product_line}" \
            "--surprising.market-maker.strategies[${i}].enabled=true" \
            "--surprising.market-maker.strategies[${i}].account-ids[0]=${maker_user}" \
            "--surprising.market-maker.strategies[${i}].symbols[0]=${stress_symbol}" \
            "--surprising.market-maker.strategies[${i}].base-quantity-steps=${maker_quantity_steps}" \
            "--surprising.market-maker.strategies[${i}].initialAnchorPriceTicks=${stress_price}" \
            "--surprising.market-maker.strategies[${i}].margin-mode=CROSS" \
            "--surprising.market-maker.strategies[${i}].order-levels=2"
          printf '%s\n' \
            "--surprising.price.consumer.required-symbols[${i}]=${stress_symbol}"
        done
      else
        printf '%s\n' \
          "--surprising.market-maker.strategies[0].strategy-id=product-smoke-mm" \
          "--surprising.market-maker.strategies[0].product-line=${product_line}" \
          "--surprising.market-maker.strategies[0].enabled=true" \
          "--surprising.market-maker.strategies[0].account-ids[0]=${MM_USER_A}" \
          "--surprising.market-maker.strategies[0].account-ids[1]=${MM_USER_B}" \
          "--surprising.market-maker.strategies[0].symbols[0]=${symbol}" \
          "--surprising.market-maker.strategies[0].base-quantity-steps=2" \
          "--surprising.market-maker.strategies[0].initialAnchorPriceTicks=$(price_ticks_for "${product_line}")" \
          "--surprising.market-maker.strategies[0].margin-mode=CROSS" \
          "--surprising.market-maker.strategies[0].order-levels=2"
      fi
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
  local mark_price_max_age=30s
  if [[ "${name}" == "trading-entry" ]]; then
    mark_price_max_age=15s
  fi
  local app_args=(
    "--server.port=${port}"
    "--surprising.price.consumer.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}"
    "--surprising.price.consumer.product-line=${product_line}"
    "--surprising.price.consumer.product-topics-enabled=true"
    "--surprising.price.consumer.group-id=product-smoke-${RUN_ID}-$(product_slug "${product_line}")-${name}-mark-price"
    "--surprising.price.consumer.max-age=${mark_price_max_age}"
  )
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
      "ACCOUNT_WAL_DIR=${TMP_DIR}/account-wal" \
      "PRODUCT_LINE=${product_line}" \
      "PRODUCT_TOPICS_ENABLED=true" \
      java ${java_args[@]+"${java_args[@]}"} -jar "${jar}" "${app_args[@]}"
  ) >"${log_file}" 2>&1 &
  register_provider_pid "${name}" "$!"
  wait_http "${product_line}-${name}" "${port}"
}

start_providers_for_line() {
  local product_line="$1"
  start_provider instrument "${product_line}"
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    start_provider price "${product_line}"
  fi
  start_provider matching "${product_line}"
  start_provider account "${product_line}"
  if is_margin_product "${product_line}"; then
    start_provider margin-ops "${product_line}"
  fi
  start_provider trading-entry "${product_line}"
  start_provider edge "${product_line}"
  if [[ "${MULTI_SYMBOL_STRESS}" != "true" ]]; then
    start_provider price "${product_line}"
  fi
}

start_price_refresher() {
  local product_line="$1"
  local log_file="${TMP_DIR}/${product_line}-price-refresher.log"
  local refresh_delay_seconds=5
  local producer_cmd topic fifo
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    refresh_delay_seconds="${STRESS_PRICE_REFRESH_DELAY_SECONDS}"
  fi
  echo "Starting ${product_line} synthetic mark-price refresher"
  producer_cmd="$(kafka_producer_cmd)"
  # 当前生产链路由指数价计算服务生成标记价；常驻刷新器从指数价入口注入。
  topic="$(topic_name "${product_line}" "index.price")"
  fifo="${TMP_DIR}/${product_line}-price-refresher.fifo"
  rm -f "${fifo}"
  mkfifo "${fifo}"
  # 使用一个常驻生产者，避免每次刷新重新启动命令行客户端导致价格断流。
  "${producer_cmd}" \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
    --topic "${topic}" \
    --property parse.key=true \
    --property key.separator=: <"${fifo}" >"${log_file}" 2>&1 &
  register_provider_pid "price-refresher-producer" "$!"
  (
    while true; do
      if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
        emit_stress_price_payloads "${product_line}" || true
      else
        STRESS_SYMBOL_COUNT=1 emit_stress_price_payloads "${product_line}" || true
      fi
      sleep "${refresh_delay_seconds}"
    done
  ) >"${fifo}" &
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

publish_mark_price() {
  local product_line="$1"
  local symbol="$2"
  local tick_units="$3"
  local sequence="$4"
  local price_ticks="$5"
  local output_fifo="${6:-}"
  local price event_time payload
  price="$(decimal_price "${price_ticks}" "${tick_units}")"
  event_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  payload="{\"symbol\":\"${symbol}\",\"indexPrice\":${price},\"sequence\":${sequence},\"status\":\"HEALTHY\",\"componentCount\":1,\"validComponentCount\":1,\"totalConfiguredWeight\":1,\"eventTime\":\"${event_time}\",\"components\":[]}"
  if [[ -n "${output_fifo}" ]]; then
    # 常驻生产者已经打开 FIFO；单行写入不再启动新的 Kafka JVM，且不超过
    # PIPE_BUF，多个测试线程同时刷新时不会产生半条 JSON。
    # FIFO 在生产者 JVM 尚未完成打开时会阻塞写端；放到后台等待读端，不能
    # 让做市商健康检查被测试工具自身的启动竞态卡住。
    printf '%s:%s\n' "${symbol}" "${payload}" >"${output_fifo}" &
  else
    produce_json "$(topic_name "${product_line}" "index.price")" "${symbol}" "${payload}"
  fi
}

next_mark_sequence() {
  local symbol="$1"
  # 生命周期测试必须发布严格递增的序列，避免被行情模块按序列检查丢弃。
  query_value "SELECT COALESCE(MAX(sequence), 0) + 1 FROM price_mark_ticks WHERE symbol = '${symbol}'"
}

current_epoch_millis() {
  python3 - <<'PY'
import time
print(time.time_ns() // 1_000_000)
PY
}

seed_prices_for_line() {
  local product_line="$1"
  local symbol price_ticks tick_units sequence_base
  symbol="$(symbol_for "${product_line}")"
  price_ticks="$(price_ticks_for "${product_line}")"
  tick_units="$(price_tick_units_for "${product_line}")"
  sequence_base="$(current_epoch_millis)"
  publish_mark_price "${product_line}" "${symbol}" "${tick_units}" "${sequence_base}" "${price_ticks}"
  publish_mark_price "${product_line}" "BTC-USDT" 10000000 "$((sequence_base + 1))" 600000
}

seed_lifecycle_settlement_price() {
  local product_line="$1"
  local symbol price_ticks tick_units sequence
  symbol="$(symbol_for "${product_line}")"
  price_ticks="$(price_ticks_for "${product_line}")"
  tick_units="$(price_tick_units_for "${product_line}")"
  sequence="$(next_mark_sequence "${symbol}")"
  if is_delivery_product "${product_line}"; then
    publish_mark_price "${product_line}" "${symbol}" "${tick_units}" "${sequence}" "$((price_ticks + 100))"
  elif is_option_product "${product_line}"; then
    publish_mark_price "${product_line}" "BTC-USDT" 10000000 "${sequence}" 600100
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
  local response_file="${TMP_DIR}/${product_line}-api-post-response-${RANDOM}.json"
  local http_code
  # 网关启动后的 Kafka consumer 重平衡期间可能短暂返回 503；相同 trace 和业务
  # 请求重试必须命中 WAL 幂等键。启动阶段允许更长的重平衡窗口，不能把瞬态
  # 503 误判为资金链路失败，也不能让调用方生成第二个业务幂等键。
  http_code="$(curl --retry 20 --retry-delay 1 --retry-max-time 90 --retry-all-errors \
    -sS -o "${response_file}" -w "%{http_code}" -X POST "http://localhost:9094/api/v1/gateway/${path}" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Product-Line: ${product_line}" \
    -H "X-Trace-Id: ${trace_id}" \
    -d "${payload}")"
  if [[ ! "${http_code}" =~ ^2 ]]; then
    echo "API 请求失败 HTTP ${http_code}: ${path} user=${user_id} trace=${trace_id}" >&2
    cat "${response_file}" >&2 || true
    return 22
  fi
  cat "${response_file}"
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

initialize_account_snapshot() {
  local product_line="$1"
  local user_id="$2"
  # 只有这个内部入口允许在 JVM 快照缺失时读取一次数据库基线；下单和资金热路径不会回退查库。
  psql_exec <<SQL >/dev/null
INSERT INTO account_risk_state_revisions (product_line, user_id, revision, updated_at)
VALUES ('${product_line}', ${user_id}, 1, now())
ON CONFLICT (product_line, user_id) DO NOTHING;
SQL
  curl --retry 3 --retry-delay 1 --retry-max-time 45 --retry-all-errors -fsS \
    "http://localhost:9086/internal/v1/accounts/perpetual-state/snapshot?productLine=${product_line}&userId=${user_id}" \
    >/dev/null
}

fund_user_for_line() {
  local product_line="$1"
  local user_id="$2"
  local asset amount type
  type="$(account_type "${product_line}")"
  initialize_account_snapshot "${product_line}" "${user_id}"
  if is_spot "${product_line}"; then
    adjust_product_balance "${user_id}" "${type}" "USDT" 200000000000000 "smoke-${RUN_ID}-${product_line}-${user_id}-usdt"
    adjust_product_balance "${user_id}" "${type}" "BTC" 100000000000 "smoke-${RUN_ID}-${product_line}-${user_id}-btc"
  else
    asset="$(settle_asset_for "${product_line}")"
    amount="$(settle_funding_amount "${asset}")"
    adjust_product_balance "${user_id}" "${type}" "${asset}" "${amount}" "smoke-${RUN_ID}-${product_line}-${user_id}-${asset}"
  fi
  # 资金调整命令会产生新的账户状态版本；再次调用显式初始化入口，确保下游
  # 做市、下单和风控在启动前已经拿到包含最新余额的 JVM 快照。
  initialize_account_snapshot "${product_line}" "${user_id}"
}

fund_liquidation_user_for_line() {
  local product_line="$1"
  local user_id="$2"
  local asset amount type
  type="$(account_type "${product_line}")"
  initialize_account_snapshot "${product_line}" "${user_id}"
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
  http_code="$(curl --retry 20 --retry-delay 1 --retry-max-time 90 --retry-all-errors \
    -sS -o "${body_file}" -w "%{http_code}" -X POST "http://localhost:9094/api/v1/gateway/trading" \
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

cancel_order_if_open() {
  local product_line="$1"
  local user_id="$2"
  local order_id="$3"
  if ! cancel_order "${product_line}" "${user_id}" "${order_id}"; then
    local status
    status="$(query_value "SELECT COALESCE((SELECT status FROM trading_orders WHERE product_line = '${product_line}' AND order_id = ${order_id}), '')")"
    [[ "${status}" == "FILLED" || "${status}" == "CANCELED" ]]
  fi
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

wait_order_terminal() {
  local product_line="$1"
  local order_id="$2"
  local deadline=$((SECONDS + 180))
  local status
  while true; do
    status="$(query_value "SELECT COALESCE((SELECT status FROM trading_orders WHERE product_line = '${product_line}' AND order_id = ${order_id}), '')")"
    if [[ "${status}" == "FILLED" || "${status}" == "CANCELED" ]]; then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for order ${order_id} terminal status: got '${status}'" >&2
      exit 1
    fi
    sleep 1
  done
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
  # 账户主状态已经迁移到 JVM 单写者；account_trade_settlement_completions
  # 只属于旧的数据库投影，不能再作为热链路完成条件。account_commands
  # 仍保留异步审计记录，因此用其已应用的成交结算结果确认账户命令已执行。
  wait_sql_nonzero "account processed order ${order_id}" \
    "SELECT count(*)
       FROM account_commands
      WHERE product_line = '${product_line}'
        AND command_type = 'TRADE_SIDE_SETTLE'
        AND status = 'APPLIED'
        AND (result_payload ->> 'orderId')::bigint = ${order_id}"
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

close_market_maker_inventory() {
  local product_line="$1"
  local symbol="$2"
  local close_price="$3"
  local a_qty b_qty short_user long_user close_qty resting_order taking_order
  wait_sql_equals "market-maker inventory projection balanced ${product_line}" \
    "SELECT COALESCE(sum(signed_quantity_steps), 0) FROM account_positions WHERE product_line = '${product_line}' AND symbol = '${symbol}' AND user_id IN (${MM_USER_A}, ${MM_USER_B})" \
    "0"
  a_qty="$(query_value "SELECT COALESCE((SELECT signed_quantity_steps FROM account_positions WHERE product_line = '${product_line}' AND user_id = ${MM_USER_A} AND symbol = '${symbol}' AND margin_mode = 'CROSS' AND position_side = 'NET'), 0)")"
  b_qty="$(query_value "SELECT COALESCE((SELECT signed_quantity_steps FROM account_positions WHERE product_line = '${product_line}' AND user_id = ${MM_USER_B} AND symbol = '${symbol}' AND margin_mode = 'CROSS' AND position_side = 'NET'), 0)")"
  if ((a_qty == 0 && b_qty == 0)); then
    return
  fi
  if ((a_qty + b_qty != 0)); then
    echo "Expected market-maker inventory to be balanced, got user ${MM_USER_A}=${a_qty}, user ${MM_USER_B}=${b_qty}" >&2
    exit 1
  fi
  if ((a_qty < 0 && b_qty > 0)); then
    short_user="${MM_USER_A}"
    long_user="${MM_USER_B}"
    close_qty=$((-a_qty))
  elif ((b_qty < 0 && a_qty > 0)); then
    short_user="${MM_USER_B}"
    long_user="${MM_USER_A}"
    close_qty=$((-b_qty))
  else
    echo "Expected one long and one short market-maker inventory, got user ${MM_USER_A}=${a_qty}, user ${MM_USER_B}=${b_qty}" >&2
    exit 1
  fi
  resting_order="$(place_order "${product_line}" "${short_user}" "mm-inventory-close-bid-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "${close_price}" "${close_qty}" true false)"
  wait_order_status "${product_line}" "${resting_order}" "ACCEPTED"
  taking_order="$(place_order "${product_line}" "${long_user}" "mm-inventory-close-ask-${product_line}-${RUN_ID}" "SELL" "LIMIT" "IOC" "${close_price}" "${close_qty}" true false)"
  wait_order_filled "${product_line}" "${taking_order}" "${close_qty}"
  wait_account_processed_order "${product_line}" "${taking_order}"
  wait_order_filled "${product_line}" "${resting_order}" "${close_qty}"
  wait_position "${product_line}" "${short_user}" "${symbol}" "0" "0"
  wait_position "${product_line}" "${long_user}" "${symbol}" "0" "0"
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
  local outbox_timeout=180
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    outbox_timeout="${STRESS_WAIT_SECONDS}"
  fi
  wait_sql_equals "trading outbox drained ${product_line}" \
    "SELECT count(*) FROM trading_outbox_events WHERE published_at IS NULL" \
    "0" "${outbox_timeout}"
  wait_sql_equals "account outbox drained ${product_line}" \
    "SELECT count(*) FROM account_outbox_events WHERE published_at IS NULL" \
    "0" "${outbox_timeout}"
  if is_margin_product "${product_line}"; then
    wait_sql_equals "risk outbox drained ${product_line}" \
      "SELECT count(*) FROM risk_outbox_events WHERE published_at IS NULL" \
      "0" "${outbox_timeout}"
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

stress_product_slug() {
  product_slug "$1"
}

stress_user_scope_predicate() {
  echo "((user_id >= ${STRESS_MM_USER_START} AND user_id < $((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT))) OR (user_id >= ${STRESS_BOOK_USER_START} AND user_id < $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT))) OR (user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT))))"
}

fund_stress_accounts_for_line() {
  local product_line="$1"
  local type
  type="$(account_type "${product_line}")"
  if [[ "${product_line}" == "LINEAR_PERPETUAL" ]]; then
    psql_exec <<SQL >/dev/null
WITH stress_prices(symbol_index, symbol, base_price_ticks) AS (
    VALUES
      (0, 'BTC-USDT', 600000::bigint), (1, 'ETH-USDT', 30000::bigint),
      (2, 'SOL-USDT', 1500::bigint), (3, 'XRP-USDT', 1000::bigint),
      (4, 'BNB-USDT', 6000::bigint), (5, 'DOGE-USDT', 1000::bigint),
      (6, 'ADA-USDT', 1200::bigint), (7, 'TRX-USDT', 1000::bigint),
      (8, 'TON-USDT', 3000::bigint), (9, 'AVAX-USDT', 3000::bigint),
      (10, 'LINK-USDT', 1500::bigint), (11, 'BCH-USDT', 4500::bigint),
      (12, 'DOT-USDT', 1000::bigint), (13, 'LTC-USDT', 8500::bigint),
      (14, 'NEAR-USDT', 1000::bigint), (15, 'UNI-USDT', 1000::bigint),
      (16, 'AAVE-USDT', 9000::bigint), (17, 'ETC-USDT', 2000::bigint),
      (18, 'FIL-USDT', 1000::bigint), (19, 'OP-USDT', 1000::bigint)
),
taker_funding AS MATERIALIZED (
    SELECT gs,
           CASE
             WHEN '${STRESS_SCENARIO}' = 'liquidation' THEN
               CEIL(
                 GREATEST(
                   ${STRESS_TAKER_QUANTITY_STEPS}::numeric,
                   i.min_quantity_steps::numeric,
                   CEIL(i.min_notional_units::numeric /
                     GREATEST(1, sp.base_price_ticks - $((STRESS_MAKER_DEPTH_LEVELS + STRESS_MAKER_REFRESH_CYCLES * STRESS_MAKER_REFRESH_LEVELS + 20)))::numeric
                     / i.notional_multiplier_units::numeric)
                 )
                 * (sp.base_price_ticks + 1)::numeric
                 * i.notional_multiplier_units::numeric
                 * (${STRESS_LIQUIDATION_WALLET_RATE_PPM} + i.taker_fee_rate_ppm)::numeric
                 / 1000000::numeric
               )::bigint
             ELSE 200000000000000::bigint
           END AS amount_units
      FROM generate_series(0, ${STRESS_USER_COUNT} - 1) AS gs
      LEFT JOIN stress_prices sp ON sp.symbol_index = (gs % ${STRESS_SYMBOL_COUNT})
      LEFT JOIN instruments i ON i.symbol = sp.symbol AND i.version = 1
),
requested AS (
    SELECT (${STRESS_MM_USER_START} + gs)::bigint AS user_id,
           'USDT'::text AS asset,
           200000000000000::bigint AS amount_units,
           ('stress-${RUN_ID}-${product_line}-mm-' || gs || '-USDT')::text AS reference_id
      FROM generate_series(0, ${STRESS_SYMBOL_COUNT} - 1) AS gs
    UNION ALL
    SELECT (${STRESS_BOOK_USER_START} + gs)::bigint AS user_id,
           'USDT'::text AS asset,
           200000000000000::bigint AS amount_units,
           ('stress-${RUN_ID}-${product_line}-book-' || gs || '-USDT')::text AS reference_id
      FROM generate_series(0, ${STRESS_SYMBOL_COUNT} - 1) AS gs
    UNION ALL
    SELECT (${STRESS_TAKER_USER_START} + gs)::bigint AS user_id,
           'USDT'::text AS asset,
           amount_units,
           ('stress-${RUN_ID}-${product_line}-user-' || gs || '-USDT')::text AS reference_id
      FROM taker_funding
),
numbered AS (
    SELECT r.*, row_number() OVER (ORDER BY r.user_id, r.asset) AS rn
      FROM requested r
),
block_requests AS (
    SELECT ((rn - 1) / 10000)::bigint AS block_index
      FROM numbered
     GROUP BY ((rn - 1) / 10000)::bigint
),
allocated_blocks AS MATERIALIZED (
    SELECT block_index,
           nextval('account_product_ledger_entry_seq') AS high
      FROM block_requests
),
ledger_rows AS (
    INSERT INTO account_product_ledger_entries (
        entry_id, user_id, account_type, asset, amount_units, balance_after_units,
        reference_type, reference_id, reason, created_at
    )
    SELECT ((allocated_blocks.high - 1) * 10000
              + numbered.rn - allocated_blocks.block_index * 10000)::bigint,
           numbered.user_id,
           'USDT_PERPETUAL',
           numbered.asset,
           numbered.amount_units,
           numbered.amount_units,
           'PRODUCT_BALANCE_ADJUSTMENT',
           numbered.reference_id,
           'PRODUCT_LINE_MULTI_SYMBOL_STRESS',
           now()
      FROM numbered
      JOIN allocated_blocks
        ON allocated_blocks.block_index = ((numbered.rn - 1) / 10000)::bigint
    ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
    RETURNING user_id, account_type, asset, amount_units, reference_id
),
balance_rows AS (
    INSERT INTO account_product_balances (account_type, user_id, asset, available_units, locked_units, updated_at)
    SELECT account_type, user_id, asset, amount_units, 0, now()
      FROM ledger_rows
    ON CONFLICT (account_type, user_id, asset) DO UPDATE SET
        available_units = account_product_balances.available_units + EXCLUDED.available_units,
        updated_at = EXCLUDED.updated_at
    RETURNING account_type, user_id, asset
)
INSERT INTO account_admin_balance_adjustments (
    reference_key, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
    asset, amount_units, balance_after_units, reference_id, reason, created_at
)
SELECT 'PRODUCT|' || user_id || '|' || account_type || '|' || asset || '|' || reference_id,
       'PRODUCT',
       1,
       'product-line-stress',
       user_id,
       account_type,
       asset,
       amount_units,
       amount_units,
       reference_id,
       'PRODUCT_LINE_MULTI_SYMBOL_STRESS',
       now()
  FROM ledger_rows
ON CONFLICT (reference_key) DO NOTHING;
SQL
    psql_exec <<SQL >/dev/null
INSERT INTO account_risk_state_revisions (product_line, user_id, revision, updated_at)
SELECT 'LINEAR_PERPETUAL', user_id, 1, now()
  FROM (
    SELECT generate_series(${STRESS_MM_USER_START}, $((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT - 1))) AS user_id
    UNION ALL
    SELECT generate_series(${STRESS_BOOK_USER_START}, $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT - 1))) AS user_id
    UNION ALL
    SELECT generate_series(${STRESS_TAKER_USER_START}, $((STRESS_TAKER_USER_START + STRESS_USER_COUNT - 1))) AS user_id
  ) users
ON CONFLICT (product_line, user_id) DO NOTHING;
SQL
    # 直接写入余额表只适合资金数值准备，不能产生账户修订号和完整账户快照。
    # 永续订单入口严格依赖 JVM 账户快照，因此补一笔真实的账户调整命令，
    # 让账户单写者发布修订事件；后续下单仍只读取快照，不允许回退查库。
    local snapshot_commands=()
    local snapshot_user_id snapshot_reference
    for ((snapshot_user_id = STRESS_MM_USER_START;
          snapshot_user_id < STRESS_MM_USER_START + STRESS_SYMBOL_COUNT;
          snapshot_user_id++)); do
      snapshot_reference="stress-${RUN_ID}-${product_line}-snapshot-${snapshot_user_id}"
      snapshot_commands+=(
        "curl -fsS 'http://localhost:9086/internal/v1/accounts/perpetual-state/snapshot?productLine=${product_line}&userId=${snapshot_user_id}' >/dev/null && curl -fsS -X POST 'http://localhost:9086/api/v1/accounts/admin/product-balance-adjustments' -H 'Content-Type: application/json' -d '{\"userId\":${snapshot_user_id},\"accountType\":\"USDT_PERPETUAL\",\"asset\":\"USDT\",\"amountUnits\":1,\"referenceId\":\"${snapshot_reference}\",\"reason\":\"PRODUCT_LINE_STRESS_SNAPSHOT_INIT\"}' >/dev/null"
      )
    done
    for ((snapshot_user_id = STRESS_BOOK_USER_START;
          snapshot_user_id < STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT;
          snapshot_user_id++)); do
      snapshot_reference="stress-${RUN_ID}-${product_line}-snapshot-${snapshot_user_id}"
      snapshot_commands+=(
        "curl -fsS 'http://localhost:9086/internal/v1/accounts/perpetual-state/snapshot?productLine=${product_line}&userId=${snapshot_user_id}' >/dev/null && curl -fsS -X POST 'http://localhost:9086/api/v1/accounts/admin/product-balance-adjustments' -H 'Content-Type: application/json' -d '{\"userId\":${snapshot_user_id},\"accountType\":\"USDT_PERPETUAL\",\"asset\":\"USDT\",\"amountUnits\":1,\"referenceId\":\"${snapshot_reference}\",\"reason\":\"PRODUCT_LINE_STRESS_SNAPSHOT_INIT\"}' >/dev/null"
      )
    done
    for ((snapshot_user_id = STRESS_TAKER_USER_START;
          snapshot_user_id < STRESS_TAKER_USER_START + STRESS_USER_COUNT;
          snapshot_user_id++)); do
      snapshot_reference="stress-${RUN_ID}-${product_line}-snapshot-${snapshot_user_id}"
      snapshot_commands+=(
        "curl -fsS 'http://localhost:9086/internal/v1/accounts/perpetual-state/snapshot?productLine=${product_line}&userId=${snapshot_user_id}' >/dev/null && curl -fsS -X POST 'http://localhost:9086/api/v1/accounts/admin/product-balance-adjustments' -H 'Content-Type: application/json' -d '{\"userId\":${snapshot_user_id},\"accountType\":\"USDT_PERPETUAL\",\"asset\":\"USDT\",\"amountUnits\":1,\"referenceId\":\"${snapshot_reference}\",\"reason\":\"PRODUCT_LINE_STRESS_SNAPSHOT_INIT\"}' >/dev/null"
      )
    done
    run_with_concurrency 16 "${snapshot_commands[@]}"
    if ((RUN_FAILURES > 0)); then
      echo "永续压测账户快照初始化失败: ${RUN_FAILURES}" >&2
      exit 1
    fi
    return
  fi

  python3 - "${product_line}" "${type}" "${STRESS_SYMBOL_COUNT}" "${STRESS_USER_COUNT}" \
    "${STRESS_BOOK_USER_START}" "${STRESS_TAKER_USER_START}" "${RUN_ID}" <<'PY' | psql_exec >/dev/null
import sys

product_line, account_type = sys.argv[1], sys.argv[2]
symbol_count, user_count = int(sys.argv[3]), int(sys.argv[4])
mm_start, taker_start = int(sys.argv[5]), int(sys.argv[6])
run_id = sys.argv[7]
bases = [
    "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "TRX", "TON", "AVAX",
    "LINK", "BCH", "DOT", "LTC", "NEAR", "UNI", "AAVE", "ETC", "FIL", "OP",
]

rows = []
for i in range(symbol_count):
    user_id = mm_start + i
    rows.append((user_id, "USDT", 200000000000000, f"stress-{run_id}-{product_line}-mm-{i}-USDT"))
    if product_line == "SPOT":
        base = bases[i % len(bases)]
        rows.append((user_id, base, 100000000000, f"stress-{run_id}-{product_line}-mm-{i}-{base}"))
for i in range(user_count):
    rows.append((taker_start + i, "USDT", 200000000000000, f"stress-{run_id}-{product_line}-user-{i}-USDT"))

def quote(value):
    return "'" + str(value).replace("'", "''") + "'"

values = ",\n".join(
    f"({user_id}, {quote(asset)}, {amount}, {quote(reference_id)})"
    for user_id, asset, amount, reference_id in rows
)
print(f"""
WITH requested(user_id, asset, amount_units, reference_id) AS (
    VALUES
{values}
),
numbered AS (
    SELECT r.*, row_number() OVER (ORDER BY r.user_id, r.asset) AS rn
      FROM requested r
),
block_requests AS (
    SELECT ((rn - 1) / 10000)::bigint AS block_index
      FROM numbered
     GROUP BY ((rn - 1) / 10000)::bigint
),
allocated_blocks AS MATERIALIZED (
    SELECT block_index,
           nextval('account_product_ledger_entry_seq') AS high
      FROM block_requests
),
ledger_rows AS (
    INSERT INTO account_product_ledger_entries (
        entry_id, user_id, account_type, asset, amount_units, balance_after_units,
        reference_type, reference_id, reason, created_at
    )
    SELECT ((allocated_blocks.high - 1) * 10000
              + numbered.rn - allocated_blocks.block_index * 10000)::bigint,
           numbered.user_id,
           {quote(account_type)},
           numbered.asset,
           numbered.amount_units,
           numbered.amount_units,
           'PRODUCT_BALANCE_ADJUSTMENT',
           numbered.reference_id,
           'PRODUCT_LINE_MULTI_SYMBOL_STRESS',
           now()
      FROM numbered
      JOIN allocated_blocks
        ON allocated_blocks.block_index = ((numbered.rn - 1) / 10000)::bigint
    ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
    RETURNING user_id, account_type, asset, amount_units, reference_id
),
balance_rows AS (
    INSERT INTO account_product_balances (account_type, user_id, asset, available_units, locked_units, updated_at)
    SELECT account_type, user_id, asset, amount_units, 0, now()
      FROM ledger_rows
    ON CONFLICT (account_type, user_id, asset) DO UPDATE SET
        available_units = account_product_balances.available_units + EXCLUDED.available_units,
        updated_at = EXCLUDED.updated_at
    RETURNING account_type, user_id, asset
)
INSERT INTO account_admin_balance_adjustments (
    reference_key, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
    asset, amount_units, balance_after_units, reference_id, reason, created_at
)
SELECT 'PRODUCT|' || user_id || '|' || account_type || '|' || asset || '|' || reference_id,
       'PRODUCT',
       1,
       'product-line-stress',
       user_id,
       account_type,
       asset,
       amount_units,
       amount_units,
       reference_id,
       'PRODUCT_LINE_MULTI_SYMBOL_STRESS',
       now()
  FROM ledger_rows
ON CONFLICT (reference_key) DO NOTHING;
""")
PY

  psql_exec <<SQL >/dev/null
INSERT INTO account_risk_state_revisions (product_line, user_id, revision, updated_at)
SELECT '${product_line}', user_id, 1, now()
  FROM (
    SELECT generate_series(${STRESS_MM_USER_START}, $((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT - 1))) AS user_id
    UNION ALL
    SELECT generate_series(${STRESS_BOOK_USER_START}, $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT - 1))) AS user_id
    UNION ALL
    SELECT generate_series(${STRESS_TAKER_USER_START}, $((STRESS_TAKER_USER_START + STRESS_USER_COUNT - 1))) AS user_id
  ) users
ON CONFLICT (product_line, user_id) DO NOTHING;
SQL

  local snapshot_commands=()
  local snapshot_user_id
  for ((snapshot_user_id = STRESS_MM_USER_START;
        snapshot_user_id < STRESS_MM_USER_START + STRESS_SYMBOL_COUNT;
        snapshot_user_id++)); do
    snapshot_commands+=("initialize_account_snapshot '${product_line}' '${snapshot_user_id}'")
  done
  for ((snapshot_user_id = STRESS_BOOK_USER_START;
        snapshot_user_id < STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT;
        snapshot_user_id++)); do
    snapshot_commands+=("initialize_account_snapshot '${product_line}' '${snapshot_user_id}'")
  done
  for ((snapshot_user_id = STRESS_TAKER_USER_START;
        snapshot_user_id < STRESS_TAKER_USER_START + STRESS_USER_COUNT;
        snapshot_user_id++)); do
    snapshot_commands+=("initialize_account_snapshot '${product_line}' '${snapshot_user_id}'")
  done
  run_with_concurrency 32 "${snapshot_commands[@]}"
  if ((RUN_FAILURES > 0)); then
    echo "${product_line} 压测账户 JVM 快照初始化失败: ${RUN_FAILURES}" >&2
    exit 1
  fi
}

stress_order_payload() {
  local user_id="$1"
  local client_order_id="$2"
  local symbol="$3"
  local side="$4"
  local tif="$5"
  local price_ticks="$6"
  local quantity_steps="$7"
  local reduce_only="$8"
  printf '{"userId":%s,"clientOrderId":"%s","symbol":"%s","side":"%s","orderType":"LIMIT","timeInForce":"%s","priceTicks":%s,"quantitySteps":%s,"marginMode":"CROSS","positionSide":"NET","reduceOnly":%s,"postOnly":false}' \
    "${user_id}" "${client_order_id}" "${symbol}" "${side}" "${tif}" "${price_ticks}" "${quantity_steps}" "${reduce_only}"
}

stress_order_command() {
  local product_line="$1"
  local user_id="$2"
  local client_order_id="$3"
  local symbol="$4"
  local side="$5"
  local tif="$6"
  local price_ticks="$7"
  local quantity_steps="$8"
  local reduce_only="$9"
  local trace="${10}"
  local payload
  payload="$(stress_order_payload "${user_id}" "${client_order_id}" "${symbol}" "${side}" "${tif}" "${price_ticks}" "${quantity_steps}" "${reduce_only}")"
  printf "curl --retry 3 --retry-delay 1 --retry-max-time 45 --retry-all-errors -fsS -X POST 'http://localhost:9094/api/v1/gateway/trading' -H 'Content-Type: application/json' -H 'X-User-Id: %s' -H 'X-Product-Line: %s' -H 'X-Trace-Id: %s' -d '%s' >/dev/null\n" \
    "${user_id}" "${product_line}" "${trace}" "${payload}"
}

stress_batch_command() {
  local product_line="$1"
  local user_id="$2"
  local trace="$3"
  local items="$4"
  printf "curl --retry 3 --retry-delay 1 --retry-max-time 45 --retry-all-errors -fsS -X POST 'http://localhost:9094/api/v1/gateway/trading/batch' -H 'Content-Type: application/json' -H 'X-User-Id: %s' -H 'X-Product-Line: %s' -H 'X-Trace-Id: %s' -d '{\"orders\":[%s]}' >/dev/null\n" \
    "${user_id}" "${product_line}" "${trace}" "${items}"
}

emit_stress_maker_commands() {
  local product_line="$1"
  local phase="$2"
  local outer_offset="$3"
  local level_count="$4"
  local quantity_steps="$5"
  local rules_json
  rules_json="$(stress_rules_json "${product_line}")"
  python3 - "${product_line}" "${phase}" "${outer_offset}" "${level_count}" "${quantity_steps}" \
    "${RUN_ID}" "${STRESS_SYMBOL_COUNT}" "${STRESS_BOOK_USER_START}" "${STRESS_MAKER_BATCH_SIZE}" \
    "${rules_json}" <<'PY'
import json
import sys

product_line = sys.argv[1]
phase = sys.argv[2]
outer_offset = int(sys.argv[3])
level_count = int(sys.argv[4])
quantity_steps = int(sys.argv[5])
run_id = sys.argv[6]
symbol_count = int(sys.argv[7])
mm_user_start = int(sys.argv[8])
batch_size = int(sys.argv[9])
rules = json.loads(sys.argv[10])
bases = [
    "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "TRX", "TON", "AVAX",
    "LINK", "BCH", "DOT", "LTC", "NEAR", "UNI", "AAVE", "ETC", "FIL", "OP",
]
underlying_prices = [
    600000, 30000, 1500, 1000, 6000, 1000, 1200, 1000, 3000, 3000,
    1500, 4500, 1000, 8500, 1000, 1000, 9000, 2000, 1000, 1000,
]
slug_map = {
    "SPOT": "spot",
    "LINEAR_PERPETUAL": "linear-perp",
    "LINEAR_DELIVERY": "linear-delivery",
    "OPTION": "option",
}

def symbol_and_price(i):
    base = bases[i % len(bases)]
    underlying_ticks = underlying_prices[i % len(underlying_prices)]
    if product_line == "SPOT":
        return f"{base}-USDT-SPOT", underlying_ticks
    if product_line == "LINEAR_PERPETUAL":
        return f"{base}-USDT", underlying_ticks
    if product_line == "LINEAR_DELIVERY":
        return f"{base}-USDT-260925", underlying_ticks
    if product_line == "OPTION":
        strike = 59000 + i * 500
        option_type = "C" if i % 2 == 0 else "P"
        return f"{base}-USDT-260925-{strike}-{option_type}", 1000 + i * 25
    raise SystemExit(f"unsupported product line {product_line}")

def quantity_for(symbol, price_ticks):
    rule = rules.get(symbol, {})
    min_quantity = int(rule.get("minQuantitySteps", 1))
    max_quantity = int(rule.get("maxQuantitySteps", 9223372036854775807))
    min_notional = int(rule.get("minNotionalUnits", 1))
    notional_multiplier = int(rule.get("notionalMultiplierUnits", 10000))
    denominator = max(1, price_ticks * notional_multiplier)
    required_by_notional = (min_notional + denominator - 1) // denominator
    required = max(quantity_steps, min_quantity, required_by_notional)
    if required > max_quantity:
        raise SystemExit(
            f"{symbol} required quantity {required} exceeds max_quantity_steps {max_quantity}"
        )
    return required

slug = slug_map[product_line]
for i in range(symbol_count):
    symbol, base_price = symbol_and_price(i)
    maker_user = mm_user_start + i
    batch = []
    for side, side_label, direction in (("BUY", "bid", -1), ("SELL", "ask", 1)):
        for level in range(1, level_count + 1):
            price = max(1, base_price + direction * (outer_offset + level))
            order_quantity_steps = quantity_for(symbol, price)
            payload = {
                "userId": maker_user,
                "clientOrderId": f"stress-mm-{run_id}-{slug}-{phase}-{i}-{side_label}-{level}",
                "symbol": symbol,
                "side": side,
                "orderType": "LIMIT",
                "timeInForce": "GTC",
                "priceTicks": price,
                "quantitySteps": order_quantity_steps,
                "marginMode": "CROSS",
                "positionSide": "NET",
                "reduceOnly": False,
                "postOnly": False,
            }
            batch.append(json.dumps(payload, separators=(",", ":")))
    # 同一用户的买卖两侧必须由同一个批次形成修订号链，不能并发提交两个独立批次。
    for offset in range(0, len(batch), batch_size):
        items = ",".join(batch[offset:offset + batch_size])
        trace = f"stress-{run_id}-{slug}-{phase}-{i}-batch-{offset // batch_size + 1}"
        print(
            "curl --retry 3 --retry-delay 1 --retry-max-time 45 --retry-all-errors -fsS "
            "-X POST 'http://localhost:9094/api/v1/gateway/trading/batch' "
            f"-H 'Content-Type: application/json' -H 'X-User-Id: {maker_user}' "
            f"-H 'X-Product-Line: {product_line}' -H 'X-Trace-Id: {trace}' "
            f"-d '{{\"orders\":[{items}]}}' >/dev/null"
        )
PY
}

emit_stress_taker_commands() {
  local product_line="$1"
  local phase="$2"
  local rules_json
  rules_json="$(stress_rules_json "${product_line}")"
  python3 - "${product_line}" "${phase}" "${RUN_ID}" "${STRESS_SYMBOL_COUNT}" "${STRESS_USER_COUNT}" \
    "${STRESS_TAKER_USER_START}" "${STRESS_TAKER_QUANTITY_STEPS}" \
    "$((STRESS_MAKER_DEPTH_LEVELS + STRESS_MAKER_REFRESH_CYCLES * STRESS_MAKER_REFRESH_LEVELS + 20))" \
    "${STRESS_HOT_SYMBOL_COUNT}" "${STRESS_HOT_TRAFFIC_PERCENT}" "${rules_json}" <<'PY'
import json
import sys

product_line = sys.argv[1]
phase = sys.argv[2]
run_id = sys.argv[3]
symbol_count = int(sys.argv[4])
user_count = int(sys.argv[5])
taker_user_start = int(sys.argv[6])
quantity_steps = int(sys.argv[7])
offset = int(sys.argv[8])
hot_symbol_count = int(sys.argv[9])
hot_traffic_percent = int(sys.argv[10])
rules = json.loads(sys.argv[11])
bases = [
    "BTC", "ETH", "SOL", "XRP", "BNB", "DOGE", "ADA", "TRX", "TON", "AVAX",
    "LINK", "BCH", "DOT", "LTC", "NEAR", "UNI", "AAVE", "ETC", "FIL", "OP",
]
underlying_prices = [
    600000, 30000, 1500, 1000, 6000, 1000, 1200, 1000, 3000, 3000,
    1500, 4500, 1000, 8500, 1000, 1000, 9000, 2000, 1000, 1000,
]
slug_map = {
    "SPOT": "spot",
    "LINEAR_PERPETUAL": "linear-perp",
    "LINEAR_DELIVERY": "linear-delivery",
    "OPTION": "option",
}

def symbol_and_price(i):
    base = bases[i % len(bases)]
    underlying_ticks = underlying_prices[i % len(underlying_prices)]
    if product_line == "SPOT":
        return f"{base}-USDT-SPOT", underlying_ticks
    if product_line == "LINEAR_PERPETUAL":
        return f"{base}-USDT", underlying_ticks
    if product_line == "LINEAR_DELIVERY":
        return f"{base}-USDT-260925", underlying_ticks
    if product_line == "OPTION":
        strike = 59000 + i * 500
        option_type = "C" if i % 2 == 0 else "P"
        return f"{base}-USDT-260925-{strike}-{option_type}", 1000 + i * 25
    raise SystemExit(f"unsupported product line {product_line}")

def quantity_for(symbol, price_ticks):
    rule = rules.get(symbol, {})
    min_quantity = int(rule.get("minQuantitySteps", 1))
    max_quantity = int(rule.get("maxQuantitySteps", 9223372036854775807))
    min_notional = int(rule.get("minNotionalUnits", 1))
    notional_multiplier = int(rule.get("notionalMultiplierUnits", 10000))
    denominator = max(1, price_ticks * notional_multiplier)
    required_by_notional = (min_notional + denominator - 1) // denominator
    required = max(quantity_steps, min_quantity, required_by_notional)
    if required > max_quantity:
        raise SystemExit(
            f"{symbol} required quantity {required} exceeds max_quantity_steps {max_quantity}"
        )
    return required

slug = slug_map[product_line]
for i in range(user_count):
    if hot_symbol_count <= 0 or hot_symbol_count >= symbol_count:
        symbol_index = i % symbol_count
    elif i % 100 < hot_traffic_percent:
        symbol_index = i % hot_symbol_count
    else:
        symbol_index = hot_symbol_count + (i % (symbol_count - hot_symbol_count))
    symbol, base_price = symbol_and_price(symbol_index)
    user_id = taker_user_start + i
    close_price = max(1, base_price - offset)
    order_quantity_steps = quantity_for(symbol, close_price)
    if phase == "open":
        side = "BUY"
        price = base_price + offset
        reduce_only = False
    else:
        side = "SELL"
        price = close_price
        reduce_only = product_line != "SPOT"
    payload = {
        "userId": user_id,
        "clientOrderId": f"stress-user-{run_id}-{slug}-{phase}-{i}",
        "symbol": symbol,
        "side": side,
        "orderType": "LIMIT",
        "timeInForce": "IOC",
        "priceTicks": price,
        "quantitySteps": order_quantity_steps,
        "marginMode": "CROSS",
        "positionSide": "NET",
        "reduceOnly": reduce_only,
        "postOnly": False,
    }
    body = json.dumps(payload, separators=(",", ":"))
    trace = f"stress-{run_id}-{slug}-{phase}-{i}"
    print(
        "curl --retry 3 --retry-delay 1 --retry-max-time 45 --retry-all-errors -fsS "
        "-X POST 'http://localhost:9094/api/v1/gateway/trading' "
        f"-H 'Content-Type: application/json' -H 'X-User-Id: {user_id}' "
        f"-H 'X-Product-Line: {product_line}' -H 'X-Trace-Id: {trace}' "
        f"-d '{body}' >/dev/null"
    )
PY
}

run_with_concurrency() {
  local max_jobs="$1"
  shift
  local active_pids=()
  local failures=0
  local command pid
  for command in "$@"; do
    bash -c "${command}" &
    active_pids+=("$!")
    if ((${#active_pids[@]} >= max_jobs)); then
      if ! wait "${active_pids[0]}"; then
        failures=$((failures + 1))
      fi
      active_pids=("${active_pids[@]:1}")
    fi
  done
  for pid in "${active_pids[@]}"; do
    if ! wait "${pid}"; then
      failures=$((failures + 1))
    fi
  done
  RUN_FAILURES="${failures}"
}

run_with_concurrency_at_rate() {
  local max_jobs="$1"
  local target_tps="$2"
  shift 2
  if ((target_tps <= 0)); then
    run_with_concurrency "${max_jobs}" "$@"
    return
  fi
  local interval_seconds
  interval_seconds="$(python3 - "${target_tps}" <<'PY'
import sys
print(f"{1 / int(sys.argv[1]):.9f}")
PY
)"
  local active_pids=()
  local failures=0
  local command pid
  for command in "$@"; do
    bash -c "${command}" &
    active_pids+=("$!")
    if ((${#active_pids[@]} >= max_jobs)); then
      if ! wait "${active_pids[0]}"; then
        failures=$((failures + 1))
      fi
      active_pids=("${active_pids[@]:1}")
    fi
    sleep "${interval_seconds}"
  done
  for pid in "${active_pids[@]}"; do
    if ! wait "${pid}"; then
      failures=$((failures + 1))
    fi
  done
  RUN_FAILURES="${failures}"
}

run_stress_maker_refresh_loop() {
  local product_line="$1"
  local phase="$2"
  local cycle offset commands=()
  for ((cycle = 1; cycle <= STRESS_MAKER_REFRESH_CYCLES; cycle++)); do
    commands=()
    offset=$((STRESS_MAKER_DEPTH_LEVELS + (cycle - 1) * STRESS_MAKER_REFRESH_LEVELS))
    while IFS= read -r command; do
      commands+=("${command}")
    done < <(emit_stress_maker_commands "${product_line}" "${phase}-refresh-${cycle}" "${offset}" \
      "${STRESS_MAKER_REFRESH_LEVELS}" "${STRESS_MAKER_LEVEL_QUANTITY_STEPS}")
    run_with_concurrency "${STRESS_MAKER_LOAD_CONCURRENCY}" "${commands[@]}"
    if ((RUN_FAILURES > 0)); then
      return "${RUN_FAILURES}"
    fi
  done
}

wait_stress_maker_phase_processed() {
  local product_line="$1"
  local phase="$2"
  local level_count="$3"
  local slug expected
  slug="$(stress_product_slug "${product_line}")"
  expected=$((STRESS_SYMBOL_COUNT * 2 * level_count))
  # 同一做市账户在一个批次内并发预占时，只有拿到当前账户 revision 的命令可以成功；
  # 旧 revision 必须安全拒绝，不能为了铺满盘口而放宽账户资金栅栏。
  # 因此这里验收整批命令均已得到撮合结果，并且拒绝只能是 revision 冲突或其依赖拒绝，
  # 实际可用盘口由持续运行的做市循环补齐。
  wait_sql_equals "${product_line} ${phase} maker orders terminal" \
    "SELECT count(*)
       FROM trading_orders o
      WHERE o.product_line = '${product_line}'
        AND o.client_order_id LIKE 'stress-mm-${RUN_ID}-${slug}-${phase}-%'
        AND o.status IN ('ACCEPTED', 'REJECTED')" \
    "${expected}" "${STRESS_WAIT_SECONDS}"
  wait_sql_equals "${product_line} ${phase} maker unexpected rejects" \
    "SELECT count(*)
       FROM trading_orders o
      WHERE o.product_line = '${product_line}'
        AND o.client_order_id LIKE 'stress-mm-${RUN_ID}-${slug}-${phase}-%'
        AND o.status = 'REJECTED'
        AND o.reject_reason NOT LIKE 'account snapshot revision is stale:%'
        AND o.reject_reason NOT LIKE 'dependency command was rejected:%'" \
    "0" "${STRESS_WAIT_SECONDS}"
}

stress_expected_active_symbol_count() {
  if ((STRESS_HOT_SYMBOL_COUNT > 0 && STRESS_HOT_TRAFFIC_PERCENT == 100)); then
    echo "${STRESS_HOT_SYMBOL_COUNT}"
  else
    echo "${STRESS_SYMBOL_COUNT}"
  fi
}

stress_kafka_lag_sample() {
  local group="$1"
  local topic="$2"
  local output summary
  output="$("$(kafka_consumer_groups_cmd)" \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVERS}" \
    --timeout 5000 \
    --describe \
    --group "${group}" 2>/dev/null || true)"
  summary="$(awk -v topic="${topic}" '
    $2 == topic && $6 ~ /^[0-9]+$/ {
      sum += $6
      if ($6 > max) max = $6
      found = 1
    }
    END {
      if (found) print sum "\t" max
    }
  ' <<<"${output}")"
  if [[ -n "${summary}" ]]; then
    printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "${group}" "${topic}" "${summary}" \
      >>"${STRESS_KAFKA_LAG_FILE}"
  fi
}

stress_kafka_lag_monitor() {
  local product_line="$1"
  local matching_group account_group order_result_group position_group risk_group liquidation_group
  matching_group="$(consumer_group "${product_line}" "matching")"
  account_group="$(consumer_group "${product_line}" "account-user-command")"
  order_result_group="$(consumer_group "${product_line}" "order-account-results")"
  position_group="$(consumer_group "${product_line}" "order-position-maintenance")"
  risk_group="$(consumer_group "${product_line}" "risk")"
  liquidation_group="$(consumer_group "${product_line}" "liquidation")"
  while [[ ! -f "${STRESS_KAFKA_LAG_STOP_FILE}" ]]; do
    stress_kafka_lag_sample "${matching_group}" "$(topic_name "${product_line}" "order.commands")"
    stress_kafka_lag_sample "${account_group}" "$(topic_name "${product_line}" "account.user.commands")"
    stress_kafka_lag_sample "${order_result_group}" "$(topic_name "${product_line}" "account.command.results")"
    stress_kafka_lag_sample "${position_group}" "$(topic_name "${product_line}" "account.position.events")"
    stress_kafka_lag_sample "${risk_group}" "$(topic_name "${product_line}" "account.position.events")"
    stress_kafka_lag_sample "${liquidation_group}" "$(topic_name "${product_line}" "liquidation.candidates")"
    sleep "${STRESS_KAFKA_LAG_SAMPLE_SECONDS}"
  done
}

start_stress_kafka_lag_monitor() {
  local product_line="$1"
  STRESS_KAFKA_LAG_FILE="${TMP_DIR}/${product_line}-kafka-lag.tsv"
  STRESS_KAFKA_LAG_STOP_FILE="${TMP_DIR}/${product_line}-kafka-lag.stop"
  rm -f "${STRESS_KAFKA_LAG_FILE}" "${STRESS_KAFKA_LAG_STOP_FILE}"
  stress_kafka_lag_monitor "${product_line}" &
  STRESS_KAFKA_LAG_MONITOR_PID="$!"
}

stop_stress_kafka_lag_monitor() {
  if [[ -n "${STRESS_KAFKA_LAG_STOP_FILE}" ]]; then
    touch "${STRESS_KAFKA_LAG_STOP_FILE}" 2>/dev/null || true
  fi
  if [[ -n "${STRESS_KAFKA_LAG_MONITOR_PID}"
        && "${STRESS_KAFKA_LAG_MONITOR_PID}" =~ ^[0-9]+$ ]]; then
    wait "${STRESS_KAFKA_LAG_MONITOR_PID}" >/dev/null 2>&1 || true
  fi
  STRESS_KAFKA_LAG_MONITOR_PID=""
}

stress_kafka_lag_rows() {
  if [[ -z "${STRESS_KAFKA_LAG_FILE}" || ! -s "${STRESS_KAFKA_LAG_FILE}" ]]; then
    echo "| N/A | N/A | 0 | N/A | N/A | N/A |"
    return
  fi
  awk -F '\t' '
    {
      key = $2 SUBSEP $3
      samples[key]++
      if ($4 > peak_sum[key]) peak_sum[key] = $4
      if ($5 > peak_partition[key]) peak_partition[key] = $5
      final_sum[key] = $4
      group[key] = $2
      topic[key] = $3
    }
    END {
      for (key in samples) {
        printf "| %s | %s | %d | %d | %d | %d |\n",
          group[key], topic[key], samples[key], peak_sum[key], peak_partition[key], final_sum[key]
      }
    }
  ' "${STRESS_KAFKA_LAG_FILE}" | sort
}

start_stress_outbox_observation() {
  STRESS_OUTBOX_START_ID="$(query_value "SELECT COALESCE(max(id), 0) FROM trading_outbox_events")"
}

stress_outbox_backlog_rows() {
  local product_line="$1"
  local slug
  slug="$(stress_product_slug "${product_line}")"
  psql_exec -At <<SQL
WITH scoped AS (
  SELECT CASE
           WHEN payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-open-%' THEN 'open'
           WHEN payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-close-%' THEN 'close'
           WHEN payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-initial-%' THEN 'initial'
           ELSE 'other'
         END AS phase,
         CASE aggregate_type
           WHEN 'ORDER' THEN 'order-provider'
           WHEN 'TRIGGER_ORDER' THEN 'trigger-provider'
           ELSE 'matching-provider'
         END AS provider_owner,
         aggregate_type,
         replace(topic, 'surprising.', '') AS topic,
         event_type,
         created_at,
         published_at
    FROM trading_outbox_events
   WHERE id > ${STRESS_OUTBOX_START_ID}
     AND (
          payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-%'
          OR (
           payload ->> 'userId' ~ '^[0-9]+$'
           AND (
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_MM_USER_START}
                                                    AND $((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT - 1))
             OR
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_BOOK_USER_START}
                                                    AND $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT - 1))
             OR
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_TAKER_USER_START}
                                                    AND $((STRESS_TAKER_USER_START + STRESS_USER_COUNT - 1))
           )
         )
       )
),
expanded AS (
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         created_at,
         published_at
    FROM scoped
  UNION ALL
  SELECT 'all' AS phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         created_at,
         published_at
    FROM scoped
),
timeline AS (
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         created_at AS event_time,
         0 AS event_order,
         1 AS delta
    FROM expanded
  UNION ALL
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         published_at AS event_time,
         1 AS event_order,
         -1 AS delta
    FROM expanded
   WHERE published_at IS NOT NULL
),
timeline_points AS (
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         event_time,
         event_order,
         sum(delta) AS delta
    FROM timeline
   GROUP BY phase, provider_owner, aggregate_type, topic, event_type, event_time, event_order
),
backlog AS (
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         sum(delta) OVER (
           PARTITION BY phase, provider_owner, aggregate_type, topic, event_type
           ORDER BY event_time, event_order
           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
         ) AS pending_count
    FROM timeline_points
),
backlog_summary AS (
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         max(pending_count) AS peak_pending
    FROM backlog
   GROUP BY phase, provider_owner, aggregate_type, topic, event_type
),
age_summary AS (
  SELECT phase,
         provider_owner,
         aggregate_type,
         topic,
         event_type,
         count(*) AS event_count,
         max(EXTRACT(EPOCH FROM (COALESCE(published_at, clock_timestamp()) - created_at)) * 1000)
           AS max_pending_age_ms,
         count(*) FILTER (WHERE published_at IS NULL) AS final_pending
    FROM expanded
   GROUP BY phase, provider_owner, aggregate_type, topic, event_type
)
SELECT '| ' || a.phase || ' | ' || a.provider_owner || ' | ' ||
       a.aggregate_type || ' | ' || a.topic || ' | ' || a.event_type || ' | ' ||
       a.event_count || ' | ' || b.peak_pending || ' | ' ||
       round(a.max_pending_age_ms::numeric, 3) || ' | ' || a.final_pending || ' |'
  FROM age_summary a
  JOIN backlog_summary b
    ON b.phase = a.phase
   AND b.provider_owner = a.provider_owner
   AND b.aggregate_type = a.aggregate_type
   AND b.topic = a.topic
   AND b.event_type = a.event_type
 ORDER BY a.phase, a.provider_owner, a.aggregate_type, a.topic, a.event_type;
SQL
}

wait_stress_phase_settled() {
  local product_line="$1"
  local phase="$2"
  local slug expected_active_symbols
  slug="$(stress_product_slug "${product_line}")"
  expected_active_symbols="$(stress_expected_active_symbol_count)"
  wait_sql_equals "${product_line} ${phase} orders filled" \
    "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND client_order_id LIKE 'stress-user-${RUN_ID}-${slug}-${phase}-%' AND status = 'FILLED'" \
    "${STRESS_USER_COUNT}" "${STRESS_WAIT_SECONDS}"
  wait_sql_equals "${product_line} ${phase} taker orders matched" \
    "SELECT count(DISTINCT taker_order_id) FROM trading_match_trades WHERE product_line = '${product_line}' AND trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'" \
    "${STRESS_USER_COUNT}" "${STRESS_WAIT_SECONDS}"
  wait_sql_equals "${product_line} ${phase} account settled taker orders" \
    "SELECT count(DISTINCT t.taker_order_id) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.product_line = '${product_line}' AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'" \
    "${STRESS_USER_COUNT}" "${STRESS_WAIT_SECONDS}"
  wait_sql_equals "${product_line} ${phase} active symbols" \
    "SELECT count(DISTINCT symbol) FROM trading_match_trades WHERE product_line = '${product_line}' AND trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'" \
    "${expected_active_symbols}" "${STRESS_WAIT_SECONDS}"
}

stress_latency_summary() {
  local product_line="$1"
  local phase="$2"
  local slug
  slug="$(stress_product_slug "${product_line}")"
  query_value "
WITH lat AS (
  SELECT EXTRACT(EPOCH FROM (s.completed_at - e.event_time)) * 1000 AS ms
    FROM trading_orders o
    JOIN trading_order_events e ON e.order_id = o.order_id AND e.event_type = 'ACCEPTED'
    JOIN trading_match_trades t ON t.taker_order_id = o.order_id
    JOIN account_trade_settlement_completions s ON s.product_line = t.product_line AND s.symbol = t.symbol AND s.trade_id = t.trade_id
   WHERE o.product_line = '${product_line}'
     AND o.client_order_id LIKE 'stress-user-${RUN_ID}-${slug}-${phase}-%'
)
SELECT count(*) || ' ' || round(min(ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(max(ms)::numeric, 3)
  FROM lat"
}

stress_account_consumer_lag_summary() {
  local product_line="$1"
  local phase="$2"
  local slug
  slug="$(stress_product_slug "${product_line}")"
  query_value "
WITH lag AS (
  SELECT EXTRACT(EPOCH FROM (s.completed_at - t.event_time)) * 1000 AS ms
    FROM trading_match_trades t
    JOIN account_trade_settlement_completions s
      ON s.product_line = t.product_line
     AND s.symbol = t.symbol
     AND s.trade_id = t.trade_id
   WHERE t.product_line = '${product_line}'
     AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
)
SELECT count(*) || ' ' || round(min(ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(max(ms)::numeric, 3)
  FROM lag"
}

stress_phase_throughput_summary() {
  local product_line="$1"
  local phase="$2"
  local source="$3"
  local slug sql
  slug="$(stress_product_slug "${product_line}")"
  case "${source}" in
    accepted)
      sql="
WITH events AS (
  SELECT e.event_time AS ts
    FROM trading_orders o
    JOIN trading_order_events e ON e.order_id = o.order_id AND e.event_type = 'ACCEPTED'
   WHERE o.product_line = '${product_line}'
     AND o.client_order_id LIKE 'stress-user-${RUN_ID}-${slug}-${phase}-%'
)"
      ;;
    matched)
      sql="
WITH events AS (
  SELECT t.event_time AS ts
    FROM trading_match_trades t
   WHERE t.product_line = '${product_line}'
     AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
)"
      ;;
    account)
      sql="
WITH events AS (
  SELECT s.completed_at AS ts
    FROM account_trade_settlement_completions s
    JOIN trading_match_trades t
      ON t.product_line = s.product_line
     AND t.symbol = s.symbol
     AND t.trade_id = s.trade_id
   WHERE t.product_line = '${product_line}'
     AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
)"
      ;;
    *)
      echo "unknown throughput source ${source}" >&2
      exit 1
      ;;
  esac
  query_value "${sql}
, summary AS (
  SELECT count(*)::numeric AS event_count,
         COALESCE(EXTRACT(EPOCH FROM max(ts) - min(ts)), 0)::numeric AS duration_seconds
    FROM events
)
SELECT event_count::bigint || ' ' ||
       round(duration_seconds, 3) || ' ' ||
       round(event_count / GREATEST(duration_seconds, 0.001), 3)
  FROM summary"
}

stress_outbox_publish_summary() {
  local table_name="$1"
  local product_line="$2"
  local slug
  slug="$(stress_product_slug "${product_line}")"
  query_value "
WITH scoped AS (
  SELECT created_at, published_at
    FROM ${table_name}
   WHERE payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-%'
      OR (
           payload ->> 'userId' ~ '^[0-9]+$'
           AND (
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_MM_USER_START}
                                                    AND $((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT - 1))
             OR
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_BOOK_USER_START}
                                                    AND $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT - 1))
             OR
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_TAKER_USER_START}
                                                    AND $((STRESS_TAKER_USER_START + STRESS_USER_COUNT - 1))
           )
         )
),
published AS (
  SELECT EXTRACT(EPOCH FROM (published_at - created_at)) * 1000 AS age_ms,
         created_at,
         published_at
    FROM scoped
   WHERE published_at IS NOT NULL
),
summary AS (
  SELECT count(*) FILTER (WHERE published_at IS NOT NULL)::numeric AS published_count,
         count(*) FILTER (WHERE published_at IS NULL)::bigint AS pending_count,
         COALESCE(EXTRACT(EPOCH FROM max(published_at) - min(created_at)), 0)::numeric AS duration_seconds
    FROM scoped
)
SELECT published_count::bigint || ' ' || pending_count || ' ' ||
       round(duration_seconds, 3) || ' ' ||
       round(published_count / GREATEST(duration_seconds, 0.001), 3) || ' ' ||
       COALESCE((SELECT round(percentile_disc(0.50) WITHIN GROUP (ORDER BY age_ms)::numeric, 3)
                   FROM published), 0) || ' ' ||
       COALESCE((SELECT round(percentile_disc(0.95) WITHIN GROUP (ORDER BY age_ms)::numeric, 3)
                   FROM published), 0) || ' ' ||
       COALESCE((SELECT round(percentile_disc(0.99) WITHIN GROUP (ORDER BY age_ms)::numeric, 3)
                   FROM published), 0) || ' ' ||
       COALESCE((SELECT round(max(age_ms)::numeric, 3) FROM published), 0)
  FROM summary"
}

stress_outbox_detail_rows() {
  local table_name="$1"
  local product_line="$2"
  local source_name="$3"
  local slug owner_expression aggregate_expression
  slug="$(stress_product_slug "${product_line}")"
  case "${table_name}" in
    trading_outbox_events)
      owner_expression="CASE aggregate_type WHEN 'ORDER' THEN 'order-provider' WHEN 'TRIGGER_ORDER' THEN 'trigger-provider' ELSE 'matching-provider' END"
      aggregate_expression="aggregate_type"
      ;;
    account_outbox_events)
      owner_expression="'account-provider'"
      aggregate_expression="aggregate_type"
      ;;
    risk_outbox_events)
      owner_expression="'risk-provider'"
      aggregate_expression="'RISK_EVENT'"
      ;;
    *)
      echo "unsupported outbox table ${table_name}" >&2
      return 1
      ;;
  esac
  psql_exec -At <<SQL
WITH scoped AS (
  SELECT ${owner_expression} AS provider_owner,
         ${aggregate_expression} AS aggregate_type,
         topic,
         event_type,
         created_at,
         published_at,
         EXTRACT(EPOCH FROM (published_at - created_at)) * 1000 AS age_ms
    FROM ${table_name}
   WHERE payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-%'
      OR (
           payload ->> 'userId' ~ '^[0-9]+$'
           AND (
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_MM_USER_START}
                                                    AND $((STRESS_MM_USER_START + STRESS_SYMBOL_COUNT - 1))
             OR
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_BOOK_USER_START}
                                                    AND $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT - 1))
             OR
             (payload ->> 'userId')::bigint BETWEEN ${STRESS_TAKER_USER_START}
                                                    AND $((STRESS_TAKER_USER_START + STRESS_USER_COUNT - 1))
           )
         )
),
summary AS (
  SELECT provider_owner,
         aggregate_type,
         topic,
         event_type,
         count(*) FILTER (WHERE published_at IS NOT NULL) AS published_count,
         count(*) FILTER (WHERE published_at IS NULL) AS pending_count,
         COALESCE(percentile_disc(0.50) WITHIN GROUP (ORDER BY age_ms)
                    FILTER (WHERE published_at IS NOT NULL), 0) AS p50_ms,
         COALESCE(percentile_disc(0.95) WITHIN GROUP (ORDER BY age_ms)
                    FILTER (WHERE published_at IS NOT NULL), 0) AS p95_ms,
         COALESCE(percentile_disc(0.99) WITHIN GROUP (ORDER BY age_ms)
                    FILTER (WHERE published_at IS NOT NULL), 0) AS p99_ms,
          COALESCE(max(age_ms) FILTER (WHERE published_at IS NOT NULL), 0) AS max_ms
     FROM scoped
    GROUP BY provider_owner, aggregate_type, topic, event_type
)
SELECT '| ${source_name} | ' || provider_owner || ' | ' ||
       aggregate_type || ' | ' ||
       replace(topic, 'surprising.', '') || ' | ' ||
       event_type || ' | ' ||
       published_count || ' | ' || pending_count || ' | ' ||
       round(p50_ms::numeric, 3) || ' | ' ||
       round(p95_ms::numeric, 3) || ' | ' ||
       round(p99_ms::numeric, 3) || ' | ' ||
       round(max_ms::numeric, 3) || ' |'
  FROM summary
 ORDER BY provider_owner, aggregate_type, topic, event_type;
SQL
}

stress_trading_outbox_phase_rows() {
  local product_line="$1"
  local phase="$2"
  local slug
  slug="$(stress_product_slug "${product_line}")"
  psql_exec -At <<SQL
WITH scoped AS (
  SELECT CASE aggregate_type
           WHEN 'ORDER' THEN 'order-provider'
           WHEN 'TRIGGER_ORDER' THEN 'trigger-provider'
           ELSE 'matching-provider'
         END AS provider_owner,
         aggregate_type,
         replace(topic, 'surprising.', '') AS topic,
         event_type,
         published_at,
         EXTRACT(EPOCH FROM (published_at - created_at)) * 1000 AS age_ms
    FROM trading_outbox_events
   WHERE payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
),
summary AS (
  SELECT provider_owner,
         aggregate_type,
         topic,
         event_type,
         count(*) FILTER (WHERE published_at IS NOT NULL) AS published_count,
         count(*) FILTER (WHERE published_at IS NULL) AS pending_count,
         COALESCE(percentile_disc(0.50) WITHIN GROUP (ORDER BY age_ms)
                    FILTER (WHERE published_at IS NOT NULL), 0) AS p50_ms,
         COALESCE(percentile_disc(0.95) WITHIN GROUP (ORDER BY age_ms)
                    FILTER (WHERE published_at IS NOT NULL), 0) AS p95_ms,
         COALESCE(percentile_disc(0.99) WITHIN GROUP (ORDER BY age_ms)
                    FILTER (WHERE published_at IS NOT NULL), 0) AS p99_ms,
         COALESCE(max(age_ms) FILTER (WHERE published_at IS NOT NULL), 0) AS max_ms
    FROM scoped
   GROUP BY provider_owner, aggregate_type, topic, event_type
)
SELECT '| ${phase} | ' || provider_owner || ' | ' ||
       aggregate_type || ' | ' || topic || ' | ' || event_type || ' | ' ||
       published_count || ' | ' || pending_count || ' | ' ||
       round(p50_ms::numeric, 3) || ' | ' ||
       round(p95_ms::numeric, 3) || ' | ' ||
       round(p99_ms::numeric, 3) || ' | ' ||
       round(max_ms::numeric, 3) || ' |'
  FROM summary
 ORDER BY provider_owner, aggregate_type, topic, event_type;
SQL
}

stress_pipeline_latency_rows() {
  local product_line="$1"
  local phase="$2"
  local slug order_commands_topic
  slug="$(stress_product_slug "${product_line}")"
  order_commands_topic="$(topic_name "${product_line}" "order.commands")"
  psql_exec -At <<SQL
WITH taker_orders AS (
  SELECT o.order_id,
         o.created_at AS order_created_at,
         e.event_time AS accepted_at
    FROM trading_orders o
    JOIN trading_order_events e
      ON e.order_id = o.order_id
     AND e.event_type = 'ACCEPTED'
   WHERE o.product_line = '${product_line}'
     AND o.client_order_id LIKE 'stress-user-${RUN_ID}-${slug}-${phase}-%'
),
order_commands AS (
  SELECT aggregate_id AS order_id,
         min(created_at) AS created_at,
         max(published_at) AS published_at
    FROM trading_outbox_events
   WHERE aggregate_type = 'ORDER'
     AND event_type = 'PLACE'
     AND topic = '${order_commands_topic}'
     AND payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
   GROUP BY aggregate_id
),
reservation_results AS (
  SELECT c.source_reference::bigint AS order_id,
         c.completed_at AS account_completed_at,
         max(a.published_at) AS result_published_at
    FROM account_commands c
    JOIN account_outbox_events a
      ON a.aggregate_type = 'ACCOUNT_COMMAND_RESULT'
     AND a.payload ->> 'commandId' = c.command_id
    JOIN taker_orders o
      ON o.order_id = c.source_reference::bigint
   WHERE c.product_line = '${product_line}'
     AND c.command_type = 'ORDER_RESERVE'
     AND c.source = 'ORDER'
   GROUP BY c.source_reference, c.completed_at
),
matches AS (
  SELECT r.order_id,
         r.command_id,
         r.event_time AS match_event_at
    FROM trading_match_results r
    JOIN taker_orders o ON o.order_id = r.order_id
   WHERE r.product_line = '${product_line}'
     AND r.command_type = 'PLACE'
),
match_results AS (
  SELECT aggregate_id AS command_id,
         max(published_at) AS published_at
    FROM trading_outbox_events
   WHERE aggregate_type = 'MATCH_RESULT'
     AND event_type = 'PLACE'
     AND payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
   GROUP BY aggregate_id
),
trades AS (
  SELECT t.trade_id,
         t.taker_order_id AS order_id,
         t.symbol,
         t.event_time AS match_event_at
    FROM trading_match_trades t
   WHERE t.product_line = '${product_line}'
     AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
),
account_commands AS (
  SELECT aggregate_id AS trade_id,
         max(created_at) AS created_at,
         max(published_at) AS published_at
    FROM trading_outbox_events
   WHERE aggregate_type = 'ACCOUNT_COMMAND'
     AND event_type = 'TRADE_SIDE_SETTLE'
     AND payload ->> 'traceId' LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
   GROUP BY aggregate_id
  HAVING count(*) = 2
     AND count(published_at) = 2
),
settlements AS (
  SELECT s.trade_id,
         s.completed_at
    FROM account_trade_settlement_completions s
    JOIN trades t
      ON t.trade_id = s.trade_id
     AND s.product_line = '${product_line}'
     AND s.symbol = t.symbol
),
samples(metric_order, metric, ms) AS (
  SELECT 1, 'order created → ACCEPTED',
         EXTRACT(EPOCH FROM (o.accepted_at - o.order_created_at)) * 1000
    FROM taker_orders o
  UNION ALL
  SELECT 2, 'account reservation completed → result published',
         EXTRACT(EPOCH FROM (r.result_published_at - r.account_completed_at)) * 1000
    FROM reservation_results r
   WHERE r.result_published_at IS NOT NULL
  UNION ALL
  SELECT 3, 'account result created → ACCEPTED',
         EXTRACT(EPOCH FROM (o.accepted_at - r.account_completed_at)) * 1000
    FROM taker_orders o JOIN reservation_results r USING (order_id)
  UNION ALL
  SELECT 4, 'ACCEPTED → order command published',
         EXTRACT(EPOCH FROM (c.published_at - o.accepted_at)) * 1000
    FROM taker_orders o JOIN order_commands c USING (order_id)
   WHERE c.published_at IS NOT NULL
  UNION ALL
  SELECT 5, 'order outbox created → published',
         EXTRACT(EPOCH FROM (c.published_at - c.created_at)) * 1000
    FROM order_commands c
   WHERE c.published_at IS NOT NULL
  UNION ALL
  SELECT 6, 'order command created → matching started',
         EXTRACT(EPOCH FROM (m.match_event_at - c.created_at)) * 1000
    FROM matches m JOIN order_commands c USING (order_id)
  UNION ALL
  SELECT 7, 'matching started → match result published',
         EXTRACT(EPOCH FROM (r.published_at - m.match_event_at)) * 1000
    FROM matches m JOIN match_results r USING (command_id)
   WHERE r.published_at IS NOT NULL
  UNION ALL
  SELECT 8, 'matching started → account commands published',
         EXTRACT(EPOCH FROM (a.published_at - t.match_event_at)) * 1000
    FROM trades t JOIN account_commands a USING (trade_id)
  UNION ALL
  SELECT 9, 'account commands created → bilateral settled',
         EXTRACT(EPOCH FROM (s.completed_at - a.created_at)) * 1000
    FROM account_commands a JOIN settlements s USING (trade_id)
  UNION ALL
  SELECT 10, 'ACCEPTED → bilateral settled',
         EXTRACT(EPOCH FROM (s.completed_at - o.accepted_at)) * 1000
    FROM taker_orders o
    JOIN trades t USING (order_id)
    JOIN settlements s USING (trade_id)
)
SELECT '| ' || metric || ' | ' || count(*) || ' | ' ||
       round(min(ms)::numeric, 3) || ' | ' ||
       round(percentile_disc(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' | ' ||
       round(percentile_disc(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' | ' ||
       round(percentile_disc(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' | ' ||
       round(max(ms)::numeric, 3) || ' |'
  FROM samples
 GROUP BY metric_order, metric
 ORDER BY metric_order;
SQL
}

stress_matching_symbol_rows() {
  local product_line="$1"
  local phase="$2"
  local slug
  slug="$(stress_product_slug "${product_line}")"
  psql_exec -At <<SQL
WITH trades AS (
  SELECT symbol, count(*) AS trade_count
    FROM trading_match_trades
   WHERE product_line = '${product_line}'
     AND trace_id LIKE 'stress-${RUN_ID}-${slug}-${phase}-%'
   GROUP BY symbol
)
SELECT '| ' || t.symbol || ' | ' || sum(t.trade_count) || ' | ' ||
       round((sum(t.trade_count)::numeric /
             GREATEST((SELECT sum(trade_count) FROM trades), 1) * 100), 3) || '% |'
  FROM trades t
 GROUP BY t.symbol
 ORDER BY t.symbol;
SQL
}

prepare_pg_stat_statements() {
  STRESS_PG_STAT_STATEMENTS_AVAILABLE=false
  local installed
  installed="$(query_value "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')")"
  if [[ "${installed}" != "t" ]]; then
    return
  fi
  if [[ "${STRESS_RESET_PG_STAT_STATEMENTS}" == "true" ]]; then
    if ! query_value "SELECT pg_stat_statements_reset()" >/dev/null 2>&1; then
      echo "pg_stat_statements is installed but reset failed; slow SQL report disabled" >&2
      return
    fi
  fi
  if ! query_value "SELECT count(*) FROM pg_stat_statements" >/dev/null 2>&1; then
    echo "pg_stat_statements view is unavailable; check shared_preload_libraries" >&2
    return
  fi
  STRESS_PG_STAT_STATEMENTS_AVAILABLE=true
}

stress_pg_stat_statement_rows() {
  if [[ "${STRESS_PG_STAT_STATEMENTS_AVAILABLE}" != "true" ]]; then
    echo "| N/A | N/A | N/A | N/A | pg_stat_statements 未安装、未 preload 或无 reset 权限 |"
    return
  fi
  psql_exec -At <<'SQL'
SELECT '| ' || calls || ' | ' ||
       round(total_exec_time::numeric, 3) || ' | ' ||
       round(mean_exec_time::numeric, 3) || ' | ' ||
       rows || ' | ' ||
       regexp_replace(left(query, 240), '[[:space:]|]+', ' ', 'g') || ' |'
  FROM pg_stat_statements
 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
   AND query NOT ILIKE '%pg_stat_statements%'
 ORDER BY total_exec_time DESC
 LIMIT 20;
SQL
}

capture_stress_pg_stat_statements() {
  STRESS_PG_STAT_STATEMENTS_FILE="${TMP_DIR}/pg-stat-statements-top.tsv"
  stress_pg_stat_statement_rows >"${STRESS_PG_STAT_STATEMENTS_FILE}"
}

collect_stress_funds_rows() {
  local product_line="$1"
  local type scope
  type="$(account_type "${product_line}")"
  scope="$(stress_user_scope_predicate)"
  if [[ "${product_line}" == "LINEAR_PERPETUAL" ]]; then
    psql_exec -At <<SQL
WITH ledger AS (
    SELECT asset, amount_units, reference_type, reason
      FROM account_ledger_entries
     WHERE ${scope}
),
balances AS (
    SELECT asset, SUM(available_units + locked_units)::bigint AS final_units
      FROM account_balances
     WHERE ${scope}
     GROUP BY asset
),
assets AS (
    SELECT asset FROM ledger
    UNION
    SELECT asset FROM balances
),
summary AS (
    SELECT a.asset,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type = 'BALANCE_ADJUSTMENT'), 0)::bigint AS adjustment_units,
           COALESCE(SUM(l.amount_units) FILTER (
               WHERE l.reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM')
                  OR (l.reference_type = 'SPOT_TRADE' AND COALESCE(l.reason, '') NOT LIKE '%FEE%')
           ), 0)::bigint AS trade_net_units,
           COALESCE(SUM(abs(l.amount_units)) FILTER (
               WHERE l.reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM')
                  OR (l.reference_type = 'SPOT_TRADE' AND COALESCE(l.reason, '') NOT LIKE '%FEE%')
           ), 0)::bigint AS trade_gross_units,
           COALESCE(SUM(l.amount_units) FILTER (
               WHERE l.reference_type = 'TRADE_FEE'
                  OR (l.reference_type = 'SPOT_TRADE' AND COALESCE(l.reason, '') LIKE '%FEE%')
           ), 0)::bigint AS fee_units,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type IN ('FUNDING', 'FUNDING_FEE')), 0)::bigint AS funding_units,
           COALESCE(SUM(abs(l.amount_units)) FILTER (WHERE l.reference_type IN ('FUNDING', 'FUNDING_FEE')), 0)::bigint AS funding_gross_units,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type = 'LIQUIDATION_FEE'), 0)::bigint AS liquidation_fee_units,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')), 0)::bigint AS delivery_exercise_units,
           COALESCE(SUM(abs(l.amount_units)) FILTER (WHERE l.reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')), 0)::bigint AS delivery_exercise_gross_units,
           COALESCE(SUM(l.amount_units), 0)::bigint AS expected_final_units
      FROM assets a
      LEFT JOIN ledger l ON l.asset = a.asset
     GROUP BY a.asset
)
SELECT '| ${product_line} | ' || s.asset || ' | 0 | ' || s.adjustment_units || ' | ' ||
       s.trade_net_units || ' | ' || s.trade_gross_units || ' | ' || s.fee_units || ' | ' ||
       s.funding_units || ' / ' || s.funding_gross_units || ' | ' || s.liquidation_fee_units || ' | ' ||
       s.delivery_exercise_units || ' / ' || s.delivery_exercise_gross_units || ' | ' ||
       s.expected_final_units || ' | ' || COALESCE(b.final_units, 0) || ' | ' ||
       CASE WHEN s.expected_final_units = COALESCE(b.final_units, 0) THEN 'OK' ELSE 'FAIL' END || ' |'
  FROM summary s
  LEFT JOIN balances b ON b.asset = s.asset
 ORDER BY s.asset;
SQL
    return
  fi
  psql_exec -At <<SQL
WITH ledger AS (
    SELECT asset, amount_units, reference_type, reason
      FROM account_product_ledger_entries
     WHERE account_type = '${type}'
       AND ${scope}
),
balances AS (
    SELECT asset, SUM(available_units + locked_units)::bigint AS final_units
      FROM account_product_balances
     WHERE account_type = '${type}'
       AND ${scope}
     GROUP BY asset
),
assets AS (
    SELECT asset FROM ledger
    UNION
    SELECT asset FROM balances
),
summary AS (
    SELECT a.asset,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type = 'PRODUCT_BALANCE_ADJUSTMENT'), 0)::bigint AS adjustment_units,
           COALESCE(SUM(l.amount_units) FILTER (
               WHERE l.reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM')
                  OR (l.reference_type = 'SPOT_TRADE' AND COALESCE(l.reason, '') NOT LIKE '%FEE%')
           ), 0)::bigint AS trade_net_units,
           COALESCE(SUM(abs(l.amount_units)) FILTER (
               WHERE l.reference_type IN ('TRADE_PNL', 'OPTION_PREMIUM')
                  OR (l.reference_type = 'SPOT_TRADE' AND COALESCE(l.reason, '') NOT LIKE '%FEE%')
           ), 0)::bigint AS trade_gross_units,
           COALESCE(SUM(l.amount_units) FILTER (
               WHERE l.reference_type = 'TRADE_FEE'
                  OR (l.reference_type = 'SPOT_TRADE' AND COALESCE(l.reason, '') LIKE '%FEE%')
           ), 0)::bigint AS fee_units,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type IN ('FUNDING', 'FUNDING_FEE')), 0)::bigint AS funding_units,
           COALESCE(SUM(abs(l.amount_units)) FILTER (WHERE l.reference_type IN ('FUNDING', 'FUNDING_FEE')), 0)::bigint AS funding_gross_units,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type = 'LIQUIDATION_FEE'), 0)::bigint AS liquidation_fee_units,
           COALESCE(SUM(l.amount_units) FILTER (WHERE l.reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')), 0)::bigint AS delivery_exercise_units,
           COALESCE(SUM(abs(l.amount_units)) FILTER (WHERE l.reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE')), 0)::bigint AS delivery_exercise_gross_units,
           COALESCE(SUM(l.amount_units), 0)::bigint AS expected_final_units
      FROM assets a
      LEFT JOIN ledger l ON l.asset = a.asset
     GROUP BY a.asset
)
SELECT '| ${product_line} | ' || s.asset || ' | 0 | ' || s.adjustment_units || ' | ' ||
       s.trade_net_units || ' | ' || s.trade_gross_units || ' | ' || s.fee_units || ' | ' ||
       s.funding_units || ' / ' || s.funding_gross_units || ' | ' || s.liquidation_fee_units || ' | ' ||
       s.delivery_exercise_units || ' / ' || s.delivery_exercise_gross_units || ' | ' ||
       s.expected_final_units || ' | ' || COALESCE(b.final_units, 0) || ' | ' ||
       CASE WHEN s.expected_final_units = COALESCE(b.final_units, 0) THEN 'OK' ELSE 'FAIL' END || ' |'
  FROM summary s
  LEFT JOIN balances b ON b.asset = s.asset
 ORDER BY s.asset;
SQL
}

assert_stress_state() {
  local product_line="$1"
  local type scope
  type="$(account_type "${product_line}")"
  scope="$(stress_user_scope_predicate)"
  wait_sql_equals "stress maker accounts cover symbols ${product_line}" \
    "SELECT count(DISTINCT user_id) FROM trading_orders WHERE product_line = '${product_line}' AND client_order_id LIKE 'stress-mm-${RUN_ID}-%' AND user_id >= ${STRESS_BOOK_USER_START} AND user_id < $((STRESS_BOOK_USER_START + STRESS_SYMBOL_COUNT))" \
    "${STRESS_SYMBOL_COUNT}" 60
  if is_margin_product "${product_line}"; then
    wait_sql_equals "stress taker positions closed ${product_line}" \
      "SELECT count(*) FROM account_positions WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND signed_quantity_steps <> 0" \
      "0" 60
  fi
  if [[ "${product_line}" == "LINEAR_PERPETUAL" ]]; then
    wait_sql_equals "stress no negative basic balances ${product_line}" \
      "SELECT count(*) FROM account_balances WHERE ${scope} AND (available_units < 0 OR locked_units < 0)" \
      "0" 60
  else
    wait_sql_equals "stress no negative product balances ${product_line}" \
      "SELECT count(*) FROM account_product_balances WHERE account_type = '${type}' AND ${scope} AND (available_units < 0 OR locked_units < 0)" \
      "0" 60
  fi
  wait_sql_equals "stress no negative position margins ${product_line}" \
    "SELECT count(*) FROM account_position_margins WHERE product_line = '${product_line}' AND margin_units < 0" \
    "0" 60
  wait_sql_equals "stress valid order reservation snapshots ${product_line}" \
    "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND reserved_units < 0 OR (product_line = '${product_line}' AND reserved_units = 0 AND (reservation_account_type IS NOT NULL OR reservation_asset IS NOT NULL)) OR (product_line = '${product_line}' AND reserved_units > 0 AND (reservation_account_type IS NULL OR reservation_asset IS NULL))" \
    "0" 60
  wait_sql_equals "stress spot lock projection is non-negative ${product_line}" \
    "SELECT count(*) FROM account_state_order_locks WHERE product_line = '${product_line}' AND locked_units < 0" \
    "0" 60
  wait_sql_equals "stress no product deficits ${product_line}" \
    "SELECT count(*) FROM account_product_deficits WHERE account_type = '${type}' AND ${scope} AND deficit_units <> 0" \
    "0" 60
}

liquidation_latency_summary() {
  local expression="$1"
  query_value "
WITH successful AS (
  SELECT DISTINCT ON (c.user_id)
         c.*,
         lo.created_at AS liquidation_order_created_at
    FROM risk_liquidation_candidates c
    JOIN liquidation_orders lo ON lo.candidate_id = c.candidate_id
   WHERE c.product_line = 'LINEAR_PERPETUAL'
     AND c.user_id >= ${STRESS_TAKER_USER_START}
     AND c.user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT))
     AND c.event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz
     AND c.status = 'COMPLETED'
     AND lo.status = 'FILLED'
   ORDER BY c.user_id, c.updated_at ASC, c.candidate_id ASC
), lat AS (
  SELECT EXTRACT(EPOCH FROM (${expression})) * 1000 AS ms
    FROM successful c
)
SELECT count(*) || ' ' || round(min(ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(max(ms)::numeric, 3)
  FROM lat"
}

run_multi_symbol_liquidation_stress_flow() {
  local product_line="$1"
  local slug commands=() open_start open_end open_ms
  local open_latency open_trades settled_open safe_scan_ms
  local candidate_latency submit_latency complete_latency liquidation_tps
  local candidate_status order_status remaining_positions liquidation_fees insurance_fees
  slug="$(stress_product_slug "${product_line}")"
  echo "Scenario ${product_line}: simultaneous liquidation stress symbols=${STRESS_SYMBOL_COUNT} users=${STRESS_USER_COUNT}"
  seed_stress_prices "${product_line}"
  fund_stress_accounts_for_line "${product_line}"
  wait_sql_equals "market-maker price coverage ${product_line}" \
    "SELECT count(DISTINCT strategy_id) FROM market_maker_strategy_run_events WHERE product_line = '${product_line}' AND strategy_id LIKE 'stress-mm-%' AND event_type = 'CYCLE_SUCCESS'" \
    "${STRESS_SYMBOL_COUNT}" 180
  seed_stress_prices "${product_line}"
  echo "Warming matching mark-price cache for ${STRESS_PRICE_WARMUP_SECONDS}s"
  sleep "${STRESS_PRICE_WARMUP_SECONDS}"
  wait_stress_mark_price_readiness "${product_line}"

  echo "Placing ${product_line} initial maker book"
  while IFS= read -r command; do commands+=("${command}"); done \
    < <(emit_stress_maker_commands "${product_line}" "initial" 0 "${STRESS_MAKER_DEPTH_LEVELS}" "${STRESS_MAKER_LEVEL_QUANTITY_STEPS}")
  run_with_concurrency "${STRESS_MAKER_LOAD_CONCURRENCY}" "${commands[@]}"
  if ((RUN_FAILURES > 0)); then
    echo "Initial maker placement failed: ${RUN_FAILURES}" >&2
    exit 1
  fi
  wait_stress_maker_phase_processed "${product_line}" "initial" "${STRESS_MAKER_DEPTH_LEVELS}"
  prepare_pg_stat_statements
  start_stress_kafka_lag_monitor "${product_line}"
  start_stress_outbox_observation

  echo "Opening ${STRESS_USER_COUNT} liquidation-stress positions"
  commands=()
  while IFS= read -r command; do commands+=("${command}"); done \
    < <(emit_stress_taker_commands "${product_line}" "open")
  STRESS_LIQUIDATION_CAPITALIZED_AT="$(query_value "SELECT clock_timestamp()")"
  open_start="$(date +%s%N)"
  run_with_concurrency_at_rate "${STRESS_LOAD_CONCURRENCY}" "${STRESS_TARGET_TPS}" "${commands[@]}"
  if ((RUN_FAILURES > 0)); then
    echo "Open taker placement failed: ${RUN_FAILURES}" >&2
    exit 1
  fi
  open_end="$(date +%s%N)"
  wait_stress_phase_settled "${product_line}" "open"

  echo "Verifying pre-funded users at ${STRESS_LIQUIDATION_WALLET_RATE_PPM} ppm of entry notional"
  wait_sql_equals "fresh safe risk snapshots before shock ${product_line}" \
    "WITH latest AS (SELECT DISTINCT ON (user_id) user_id, status, event_time FROM risk_account_snapshots WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) ORDER BY user_id, event_time DESC, snapshot_id DESC) SELECT count(*) FROM latest WHERE status = 'NORMAL' AND event_time >= '${STRESS_LIQUIDATION_CAPITALIZED_AT}'::timestamptz" \
    "${STRESS_USER_COUNT}" "${STRESS_LIQUIDATION_WAIT_SECONDS}"
  safe_scan_ms="$(query_value "SELECT round(EXTRACT(EPOCH FROM (max(event_time) - '${STRESS_LIQUIDATION_CAPITALIZED_AT}'::timestamptz)) * 1000, 3) FROM risk_account_snapshots WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND event_time >= '${STRESS_LIQUIDATION_CAPITALIZED_AT}'::timestamptz")"

  echo "Triggering one ${STRESS_LIQUIDATION_MARK_FACTOR_PPM} ppm mark-price shock for all ${STRESS_SYMBOL_COUNT} symbols"
  stop_provider_by_name price-refresher
  stop_provider_by_name price-refresher-producer
  STRESS_MARK_PRICE_FACTOR_PPM="${STRESS_LIQUIDATION_MARK_FACTOR_PPM}"
  STRESS_LIQUIDATION_TRIGGERED_AT="$(query_value "SELECT clock_timestamp()")"
  seed_stress_prices "${product_line}"
  start_price_refresher "${product_line}"

  wait_sql_equals "liquidation candidates after shock ${product_line}" \
    "SELECT count(DISTINCT user_id) FROM risk_liquidation_candidates WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz" \
    "${STRESS_USER_COUNT}" "${STRESS_LIQUIDATION_WAIT_SECONDS}"
  wait_sql_equals "liquidation orders filled after shock ${product_line}" \
    "SELECT count(DISTINCT lo.user_id) FROM liquidation_orders lo JOIN risk_liquidation_candidates c ON c.candidate_id = lo.candidate_id WHERE c.product_line = '${product_line}' AND lo.user_id >= ${STRESS_TAKER_USER_START} AND lo.user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND c.event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz AND lo.status = 'FILLED'" \
    "${STRESS_USER_COUNT}" "${STRESS_LIQUIDATION_WAIT_SECONDS}"
  wait_sql_equals "positions closed by liquidation ${product_line}" \
    "SELECT count(*) FROM account_positions WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND signed_quantity_steps <> 0" \
    "0" "${STRESS_LIQUIDATION_WAIT_SECONDS}"
  wait_sql_equals "no duplicate filled liquidations per one-step position ${product_line}" \
    "SELECT count(*) FROM (SELECT lo.user_id FROM liquidation_orders lo JOIN risk_liquidation_candidates c ON c.candidate_id = lo.candidate_id WHERE c.product_line = '${product_line}' AND lo.user_id >= ${STRESS_TAKER_USER_START} AND lo.user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND c.event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz AND lo.status = 'FILLED' GROUP BY lo.user_id HAVING count(*) > 1) duplicate_users" \
    "0" "${STRESS_LIQUIDATION_WAIT_SECONDS}"
  assert_outbox_drained "${product_line}"
  capture_stress_pg_stat_statements

  open_ms="$(python3 - "${open_start}" "${open_end}" <<'PY'
import sys
print(round((int(sys.argv[2]) - int(sys.argv[1])) / 1_000_000, 3))
PY
)"
  open_latency="$(stress_latency_summary "${product_line}" "open")"
  open_trades="$(query_value "SELECT count(*) FROM trading_match_trades WHERE product_line = '${product_line}' AND trace_id LIKE 'stress-${RUN_ID}-${slug}-open-%'")"
  settled_open="$(query_value "SELECT count(DISTINCT t.taker_order_id) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.product_line = '${product_line}' AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-open-%'")"
  candidate_latency="$(liquidation_latency_summary "c.event_time - '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz")"
  submit_latency="$(liquidation_latency_summary "c.liquidation_order_created_at - c.event_time")"
  complete_latency="$(liquidation_latency_summary "c.updated_at - '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz")"
  liquidation_tps="$(query_value "SELECT count(*) || ' ' || round(EXTRACT(EPOCH FROM (max(c.updated_at) - '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz))::numeric, 3) || ' ' || round((count(*) / NULLIF(EXTRACT(EPOCH FROM (max(c.updated_at) - '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz)), 0))::numeric, 3) FROM risk_liquidation_candidates c WHERE c.product_line = '${product_line}' AND c.user_id >= ${STRESS_TAKER_USER_START} AND c.user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND c.event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz AND c.status = 'COMPLETED'")"
  candidate_status="$(query_value "SELECT string_agg(status || '=' || count, ', ' ORDER BY status) FROM (SELECT status, count(*) FROM risk_liquidation_candidates WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz GROUP BY status) s")"
  order_status="$(query_value "SELECT string_agg(status || '=' || count, ', ' ORDER BY status) FROM (SELECT lo.status, count(*) FROM liquidation_orders lo JOIN risk_liquidation_candidates c ON c.candidate_id = lo.candidate_id WHERE c.product_line = '${product_line}' AND lo.user_id >= ${STRESS_TAKER_USER_START} AND lo.user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND c.event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz GROUP BY lo.status) s")"
  duplicate_filled_users="$(query_value "SELECT count(*) FROM (SELECT lo.user_id FROM liquidation_orders lo JOIN risk_liquidation_candidates c ON c.candidate_id = lo.candidate_id WHERE c.product_line = '${product_line}' AND lo.user_id >= ${STRESS_TAKER_USER_START} AND lo.user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND c.event_time >= '${STRESS_LIQUIDATION_TRIGGERED_AT}'::timestamptz AND lo.status = 'FILLED' GROUP BY lo.user_id HAVING count(*) > 1) duplicate_users")"
  remaining_positions="$(query_value "SELECT count(*) FROM account_positions WHERE product_line = '${product_line}' AND user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND signed_quantity_steps <> 0")"
  liquidation_fees="$(query_value "SELECT count(*) || ' ' || COALESCE(sum(amount_units), 0) FROM account_ledger_entries WHERE user_id >= ${STRESS_TAKER_USER_START} AND user_id < $((STRESS_TAKER_USER_START + STRESS_USER_COUNT)) AND reference_type = 'LIQUIDATION_FEE'")"
  insurance_fees="$(query_value "SELECT count(*) || ' ' || COALESCE(sum(amount_units), 0) FROM insurance_fund_ledger WHERE reference_type = 'LIQUIDATION_FEE'")"
  assert_stress_state "${product_line}"
  assert_no_negative_balances "${product_line}"
  stop_stress_kafka_lag_monitor

  STRESS_LAST_SUMMARY_FILE="${TMP_DIR}/${product_line}-stress-summary.md"
  {
    echo "## ${product_line} ${STRESS_USER_COUNT}-user simultaneous liquidation"
    echo
    echo "- Run label：${STRESS_RUN_LABEL}"
    echo "- Trigger：20 symbols receive the same sustained mark-price shock; factor=${STRESS_LIQUIDATION_MARK_FACTOR_PPM} ppm"
    echo "- Users：${STRESS_USER_COUNT}; open trades=${open_trades}; settled open orders=${settled_open}; submitMs=${open_ms}"
    echo "- Pre-shock funding：wallet=${STRESS_LIQUIDATION_WALLET_RATE_PPM} ppm of entry notional after the opening fee; all ${STRESS_USER_COUNT} latest snapshots NORMAL; safe projection catch-up ms=${safe_scan_ms}"
    echo "- Liquidation consumer concurrency：${STRESS_LIQUIDATION_KAFKA_CONCURRENCY}"
    echo "- Candidate status：${candidate_status}"
    echo "- Liquidation order status：${order_status}"
    echo "- Users with duplicate FILLED liquidation orders：${duplicate_filled_users}"
    echo "- Remaining user positions：${remaining_positions}"
    echo "- Completed liquidation throughput \`count durationSeconds tps\`：${liquidation_tps}"
    echo "- Trigger -> candidate latency ms \`count min p50 p95 p99 max\`：${candidate_latency}"
    echo "- Candidate -> liquidation order submit latency ms \`count min p50 p95 p99 max\`：${submit_latency}"
    echo "- Trigger -> liquidation completed latency ms \`count min p50 p95 p99 max\`：${complete_latency}"
    echo "- Open accepted -> settled latency ms \`count min p50 p95 p99 max\`：${open_latency}"
    echo "- User liquidation fees \`rows sumUnits\`：${liquidation_fees}"
    echo "- Insurance liquidation credits \`rows sumUnits\`：${insurance_fees}"
    echo
    echo "### Kafka Consumer Lag"
    echo
    echo "| group | topic | samples | peak total lag | peak partition lag | final total lag |"
    echo "|---|---|---:|---:|---:|---:|"
    stress_kafka_lag_rows
    echo
    echo "### PostgreSQL Top SQL"
    echo
    echo "| calls | total exec ms | mean exec ms | rows | query |"
    echo "|---:|---:|---:|---:|---|"
    cat "${STRESS_PG_STAT_STATEMENTS_FILE}"
    echo
    echo "| 产品线 | 资产 | 期初 | 充值/调整 | 成交净额 | 成交发生额 | 手续费 | 资金费净额/发生额 | 强平费 | 交割/行权 | 期末应有 | 期末实际 | 结果 |"
    echo "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"
    collect_stress_funds_rows "${product_line}"
  } >"${STRESS_LAST_SUMMARY_FILE}"
}

run_multi_symbol_stress_flow() {
  local product_line="$1"
  local slug commands=() maker_pid open_start open_end close_start close_end open_ms close_ms
  local open_latency close_latency open_trades close_trades settled_open settled_close active_symbols maker_users
  local maker_orders maker_place_success open_accepted_tps open_matched_tps open_account_tps
  local close_accepted_tps close_matched_tps close_account_tps trading_outbox_tps account_outbox_tps risk_outbox_tps
  local open_account_lag close_account_lag traffic_model target_rate expected_active_symbols
  slug="$(stress_product_slug "${product_line}")"
  expected_active_symbols="$(stress_expected_active_symbol_count)"
  if ((STRESS_HOT_SYMBOL_COUNT > 0)); then
    traffic_model="hotspot(${STRESS_HOT_SYMBOL_COUNT} symbols=${STRESS_HOT_TRAFFIC_PERCENT}%)"
  else
    traffic_model="uniform"
  fi
  if ((STRESS_TARGET_TPS > 0)); then
    target_rate="${STRESS_TARGET_TPS} TPS"
  else
    target_rate="unbounded"
  fi
  echo "Scenario ${product_line}: multi-symbol high-frequency stress symbols=${STRESS_SYMBOL_COUNT} users=${STRESS_USER_COUNT} traffic=${traffic_model} target=${target_rate}"
  seed_stress_prices "${product_line}"
  fund_stress_accounts_for_line "${product_line}"
  wait_sql_equals "market-maker price coverage ${product_line}" \
    "SELECT count(DISTINCT strategy_id) FROM market_maker_strategy_run_events WHERE product_line = '${product_line}' AND strategy_id LIKE 'stress-mm-%' AND event_type = 'CYCLE_SUCCESS'" \
    "${STRESS_SYMBOL_COUNT}" \
    180
  # Each provider keeps an independent in-memory Kafka price cache.  A full
  # market-maker cycle proves the snapshot reached every symbol, then allow the
  # matching consumer group to complete its initial assignment before sending
  # the concurrent market-order burst.  Without this barrier, a new topic can
  # reject the first burst while its matching cache is still empty.
  seed_stress_prices "${product_line}"
  echo "Warming matching mark-price cache for ${STRESS_PRICE_WARMUP_SECONDS}s"
  sleep "${STRESS_PRICE_WARMUP_SECONDS}"

  echo "Placing ${product_line} initial maker book"
  commands=()
  while IFS= read -r command; do
    commands+=("${command}")
  done < <(emit_stress_maker_commands "${product_line}" "initial" 0 "${STRESS_MAKER_DEPTH_LEVELS}" "${STRESS_MAKER_LEVEL_QUANTITY_STEPS}")
  run_with_concurrency "${STRESS_MAKER_LOAD_CONCURRENCY}" "${commands[@]}"
  if ((RUN_FAILURES > 0)); then
    echo "Initial maker placement failed: ${RUN_FAILURES}" >&2
    exit 1
  fi
  wait_stress_maker_phase_processed "${product_line}" "initial" "${STRESS_MAKER_DEPTH_LEVELS}"
  prepare_pg_stat_statements
  start_stress_kafka_lag_monitor "${product_line}"
  start_stress_outbox_observation

  echo "Running ${product_line} concurrent open phase"
  commands=()
  while IFS= read -r command; do
    commands+=("${command}")
  done < <(emit_stress_taker_commands "${product_line}" "open")
  open_start="$(date +%s%N)"
  run_stress_maker_refresh_loop "${product_line}" "open" &
  maker_pid="$!"
  run_with_concurrency_at_rate "${STRESS_LOAD_CONCURRENCY}" "${STRESS_TARGET_TPS}" "${commands[@]}"
  if ((RUN_FAILURES > 0)); then
    echo "Open taker placement failed: ${RUN_FAILURES}" >&2
    exit 1
  fi
  wait "${maker_pid}"
  open_end="$(date +%s%N)"
  wait_stress_phase_settled "${product_line}" "open"

  echo "Running ${product_line} concurrent close/sell phase"
  commands=()
  while IFS= read -r command; do
    commands+=("${command}")
  done < <(emit_stress_taker_commands "${product_line}" "close")
  close_start="$(date +%s%N)"
  run_stress_maker_refresh_loop "${product_line}" "close" &
  maker_pid="$!"
  run_with_concurrency_at_rate "${STRESS_LOAD_CONCURRENCY}" "${STRESS_TARGET_TPS}" "${commands[@]}"
  if ((RUN_FAILURES > 0)); then
    echo "Close taker placement failed: ${RUN_FAILURES}" >&2
    exit 1
  fi
  wait "${maker_pid}"
  close_end="$(date +%s%N)"
  wait_stress_phase_settled "${product_line}" "close"
  capture_stress_pg_stat_statements
  stop_provider_by_name market-maker
  stop_provider_by_name price-refresher

  open_ms="$(python3 - "${open_start}" "${open_end}" <<'PY'
import sys
print(round((int(sys.argv[2]) - int(sys.argv[1])) / 1_000_000, 3))
PY
)"
  close_ms="$(python3 - "${close_start}" "${close_end}" <<'PY'
import sys
print(round((int(sys.argv[2]) - int(sys.argv[1])) / 1_000_000, 3))
PY
)"
  open_latency="$(stress_latency_summary "${product_line}" "open")"
  close_latency="$(stress_latency_summary "${product_line}" "close")"
  open_account_lag="$(stress_account_consumer_lag_summary "${product_line}" "open")"
  close_account_lag="$(stress_account_consumer_lag_summary "${product_line}" "close")"
  open_accepted_tps="$(stress_phase_throughput_summary "${product_line}" "open" "accepted")"
  open_matched_tps="$(stress_phase_throughput_summary "${product_line}" "open" "matched")"
  open_account_tps="$(stress_phase_throughput_summary "${product_line}" "open" "account")"
  close_accepted_tps="$(stress_phase_throughput_summary "${product_line}" "close" "accepted")"
  close_matched_tps="$(stress_phase_throughput_summary "${product_line}" "close" "matched")"
  close_account_tps="$(stress_phase_throughput_summary "${product_line}" "close" "account")"
  open_trades="$(query_value "SELECT count(*) FROM trading_match_trades WHERE product_line = '${product_line}' AND trace_id LIKE 'stress-${RUN_ID}-${slug}-open-%'")"
  close_trades="$(query_value "SELECT count(*) FROM trading_match_trades WHERE product_line = '${product_line}' AND trace_id LIKE 'stress-${RUN_ID}-${slug}-close-%'")"
  settled_open="$(query_value "SELECT count(DISTINCT t.taker_order_id) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.product_line = '${product_line}' AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-open-%'")"
  settled_close="$(query_value "SELECT count(DISTINCT t.taker_order_id) FROM account_trade_settlement_completions s JOIN trading_match_trades t ON t.product_line = s.product_line AND t.symbol = s.symbol AND t.trade_id = s.trade_id WHERE t.product_line = '${product_line}' AND t.trace_id LIKE 'stress-${RUN_ID}-${slug}-close-%'")"
  active_symbols="$(query_value "SELECT count(DISTINCT symbol) FROM trading_match_trades WHERE product_line = '${product_line}' AND trace_id LIKE 'stress-${RUN_ID}-${slug}-%'")"
  maker_users="$(query_value "SELECT count(DISTINCT user_id) FROM trading_orders WHERE product_line = '${product_line}' AND client_order_id LIKE 'stress-mm-${RUN_ID}-${slug}-%'")"
  maker_orders="$(query_value "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND client_order_id LIKE 'stress-mm-${RUN_ID}-${slug}-%'")"
  maker_place_success="$(query_value "SELECT count(*) FROM trading_match_results r JOIN trading_orders o ON o.product_line = r.product_line AND o.order_id = r.order_id WHERE r.product_line = '${product_line}' AND r.command_type = 'PLACE' AND r.result_code = 'SUCCESS' AND o.client_order_id LIKE 'stress-mm-${RUN_ID}-${slug}-%'")"

  assert_stress_state "${product_line}"
  assert_no_negative_balances "${product_line}"
  assert_outbox_drained "${product_line}"
  stop_stress_kafka_lag_monitor
  trading_outbox_tps="$(stress_outbox_publish_summary "trading_outbox_events" "${product_line}")"
  account_outbox_tps="$(stress_outbox_publish_summary "account_outbox_events" "${product_line}")"
  risk_outbox_tps="$(stress_outbox_publish_summary "risk_outbox_events" "${product_line}")"

  STRESS_LAST_SUMMARY_FILE="${TMP_DIR}/${product_line}-stress-summary.md"
  {
    echo "## ${product_line}"
    echo
    if [[ -n "${STRESS_RUN_LABEL}" ]]; then
      echo "- Run label：${STRESS_RUN_LABEL}"
    fi
    echo "- Symbols active：${active_symbols}/${expected_active_symbols}（instrument universe=${STRESS_SYMBOL_COUNT}）"
    echo "- Maker accounts：${maker_users}/${STRESS_SYMBOL_COUNT}"
    echo "- Maker orders：orders=${maker_orders}, placeSuccess=${maker_place_success}, initialLevels=${STRESS_MAKER_DEPTH_LEVELS}, refreshCycles=${STRESS_MAKER_REFRESH_CYCLES}, refreshLevels=${STRESS_MAKER_REFRESH_LEVELS}, concurrency=${STRESS_MAKER_LOAD_CONCURRENCY}"
    echo "- Users：${STRESS_USER_COUNT}"
    echo "- Traffic：model=${traffic_model}, targetRate=${target_rate}"
    echo "- Matching：kafkaConcurrency=${STRESS_MATCHING_KAFKA_CONCURRENCY}, maxPollRecords=${STRESS_MATCHING_MAX_POLL_RECORDS}, matchingEngines=${STRESS_MATCHING_ENGINE_SHARDS}, riskEngines=${STRESS_MATCHING_RISK_SHARDS}"
    echo "- Internal maker whitelist：${STRESS_INTERNAL_MARKET_MAKER_WHITELIST}"
    echo "- Open phase：orders=${STRESS_USER_COUNT}, trades=${open_trades}, settledOrders=${settled_open}, submitMs=${open_ms}, concurrency=${STRESS_LOAD_CONCURRENCY}, targetTps=${STRESS_TARGET_TPS}"
    echo "- Close/sell phase：orders=${STRESS_USER_COUNT}, trades=${close_trades}, settledOrders=${settled_close}, submitMs=${close_ms}, concurrency=${STRESS_LOAD_CONCURRENCY}, targetTps=${STRESS_TARGET_TPS}"
    echo "- Account settlement latency ms \`count min p50 p95 p99 max\`：open=${open_latency}; close=${close_latency}"
    echo "- Account consumer lag ms \`matchEventTime -> accountProcessedAt count min p50 p95 p99 max\`：open=${open_account_lag}; close=${close_account_lag}"
    echo "- Phase throughput \`count durationSeconds tps\`：openAccepted=${open_accepted_tps}; openMatched=${open_matched_tps}; openAccount=${open_account_tps}; closeAccepted=${close_accepted_tps}; closeMatched=${close_matched_tps}; closeAccount=${close_account_tps}"
    echo "- Scoped outbox publish \`published pending spanSeconds effectiveTps ageP50Ms ageP95Ms ageP99Ms ageMaxMs\`：trading=${trading_outbox_tps}; account=${account_outbox_tps}; risk=${risk_outbox_tps}"
    echo "- Trading Outbox observation：id>${STRESS_OUTBOX_START_ID}；ORDER=order-provider，TRIGGER_ORDER=trigger-provider，其余压测 aggregate=matching-provider；phase=other 表示事件未携带 open/close trace。"
    echo
    echo "### Open 链路分段延迟"
    echo
    echo "| 阶段 | 样本 | min ms | p50 ms | p95 ms | p99 ms | max ms |"
    echo "|---|---:|---:|---:|---:|---:|---:|"
    stress_pipeline_latency_rows "${product_line}" "open"
    echo
    echo "### Close 链路分段延迟"
    echo
    echo "| 阶段 | 样本 | min ms | p50 ms | p95 ms | p99 ms | max ms |"
    echo "|---|---:|---:|---:|---:|---:|---:|"
    stress_pipeline_latency_rows "${product_line}" "close"
    echo
    echo "### Outbox 分组发布延迟"
    echo
    echo "| 来源 | provider owner | aggregate type | topic | event type | published | pending | p50 ms | p95 ms | p99 ms | max ms |"
    echo "|---|---|---|---|---|---:|---:|---:|---:|---:|---:|"
    stress_outbox_detail_rows "trading_outbox_events" "${product_line}" "trading"
    stress_outbox_detail_rows "account_outbox_events" "${product_line}" "account"
    stress_outbox_detail_rows "risk_outbox_events" "${product_line}" "risk"
    echo
    echo "### Trading Outbox Open/Close 发布延迟"
    echo
    echo "| phase | provider owner | aggregate type | topic | event type | published | pending | p50 ms | p95 ms | p99 ms | max ms |"
    echo "|---|---|---|---|---|---:|---:|---:|---:|---:|---:|"
    stress_trading_outbox_phase_rows "${product_line}" "open"
    stress_trading_outbox_phase_rows "${product_line}" "close"
    echo
    echo "### Trading Outbox 积压峰值"
    echo
    echo "| phase | provider owner | aggregate type | topic | event type | events | peak pending | max pending age ms | final pending |"
    echo "|---|---|---|---|---|---:|---:|---:|---:|"
    stress_outbox_backlog_rows "${product_line}"
    echo
    echo "### Kafka Consumer Lag"
    echo
    echo "| group | topic | samples | peak total lag | peak partition lag | final total lag |"
    echo "|---|---|---:|---:|---:|---:|"
    stress_kafka_lag_rows
    echo
    echo "### Matching shard 流量分布"
    echo
    echo "Open："
    echo
    echo "| symbol | trades | share |"
    echo "|---|---:|---:|"
    stress_matching_symbol_rows "${product_line}" "open"
    echo
    echo "Close："
    echo
    echo "| symbol | trades | share |"
    echo "|---|---:|---:|"
    stress_matching_symbol_rows "${product_line}" "close"
    echo
    echo "### PostgreSQL Top SQL"
    echo
    echo "| calls | total exec ms | mean exec ms | rows | query |"
    echo "|---:|---:|---:|---:|---|"
    cat "${STRESS_PG_STAT_STATEMENTS_FILE}"
    echo
    echo "| 产品线 | 资产 | 期初 | 充值/调整 | 成交/权利金净额 | 成交/权利金发生额 | 手续费 | 资金费净额/发生额 | 强平费 | 交割/行权净额/发生额 | 期末应有 | 期末实际 | 结果 |"
    echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"
    collect_stress_funds_rows "${product_line}"
    echo
  } >"${STRESS_LAST_SUMMARY_FILE}"
}

wait_market_maker_running() {
  local product_line="$1"
  local symbol tick_units sequence market_maker_log market_price_fifo
  symbol="$(symbol_for "${product_line}")"
  tick_units="$(price_tick_units_for "${product_line}")"
  market_maker_log="${TMP_DIR}/${product_line}-market-maker.log"
  market_price_fifo="${TMP_DIR}/${product_line}-price-refresher.fifo"
  # 做市商健康检查完成时，Kafka 标记价消费者可能仍在进行首次分区分配。
  # latest 会在分配回调中定位到当时的末尾，必须等回调完成后再补发实时价格，
  # 否则启动阶段的种子消息会被故意跳过，做市商会持续判定标记价不可用。
  wait_until "market-maker mark price consumer assigned ${product_line}" 90 \
    grep -Eq 'Adding newly assigned partitions: \[.*mark\.price\.v1-|partitions assigned: \[.*mark\.price\.v1-' \
      "${market_maker_log}"
  sleep 1
  sequence="$(current_epoch_millis)"
  publish_mark_price "${product_line}" "${symbol}" "${tick_units}" "${sequence}" "$(price_ticks_for "${product_line}")" "${market_price_fifo}"
  if is_option_product "${product_line}"; then
    publish_mark_price "${product_line}" "BTC-USDT" 10000000 "$((sequence + 1))" 600000 "${market_price_fifo}"
  fi
  wait_sql_nonzero "market-maker cycle success ${product_line}" \
    "SELECT count(*) FROM market_maker_strategy_run_events WHERE product_line = '${product_line}' AND strategy_id = 'product-smoke-mm' AND event_type = 'CYCLE_SUCCESS'"
  wait_sql_nonzero "market-maker open quotes ${product_line}" \
    "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND symbol = '${symbol}' AND user_id IN (${MM_USER_A}, ${MM_USER_B}) AND client_order_id LIKE 'mm-%' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED')"
}

wait_stress_market_maker_ready() {
  local product_line="$1"
  local market_maker_log="${TMP_DIR}/${product_line}-market-maker.log"
  wait_until "stress market-maker mark price consumer assigned ${product_line}" 90 \
    grep -Eq 'Adding newly assigned partitions: \[.*mark\.price\.v1-|partitions assigned: \[.*mark\.price\.v1-' \
      "${market_maker_log}"
  sleep 1
  seed_stress_prices "${product_line}"
  wait_sql_nonzero "stress market-maker first successful cycle ${product_line}" \
    "SELECT count(*) FROM market_maker_strategy_run_events WHERE product_line = '${product_line}' AND strategy_id LIKE 'stress-mm-%' AND event_type = 'CYCLE_SUCCESS'" \
    90
}

start_live_websocket_smoke() {
  local product_line="$1"
  local symbol="$2"
  WEBSOCKET_SMOKE_EVIDENCE="${TMP_DIR}/${product_line}-websocket.json"
  WEBSOCKET_SMOKE_LOG="${TMP_DIR}/${product_line}-websocket.log"
  PRODUCT_LINE="${product_line}" \
    SYMBOL="${symbol}" \
    WS_USER_ID="${TAKER_USER}" \
    WS_OTHER_USER_ID="$((TAKER_USER + 1))" \
    WS_TIMEOUT_MS="${WS_TIMEOUT_MS}" \
    WS_EVIDENCE="${WEBSOCKET_SMOKE_EVIDENCE}" \
    node "${ROOT_DIR}/scripts/product-line-websocket-smoke.mjs" \
    >"${WEBSOCKET_SMOKE_LOG}" 2>&1 &
  WEBSOCKET_SMOKE_PID=$!
}

finish_live_websocket_smoke() {
  if [[ -z "${WEBSOCKET_SMOKE_PID}" ]]; then
    return
  fi
  wait "${WEBSOCKET_SMOKE_PID}"
  cat "${WEBSOCKET_SMOKE_LOG}"
  WEBSOCKET_SMOKE_PID=""
}

run_market_maker_taker_flow() {
  local product_line="$1"
  local symbol price qty buy_order sell_order
  symbol="$(symbol_for "${product_line}")"
  price="$(price_ticks_for "${product_line}")"
  qty=1
  echo "Scenario ${product_line}: user taker trades against continuously running market-maker"
  wait_market_maker_running "${product_line}"
  # 做市周期成功只代表策略完成一次计算，报价预占仍由账户单写者异步确认。
  # 下单前必须确认买卖两侧都已进入撮合订单簿，避免在撤旧单/等预占窗口内
  # 发起 IOC，随后把流动性竞态误报为撮合或资金故障。
  wait_sql_nonzero "market-maker ask quote ready ${product_line}" \
    "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND symbol = '${symbol}' AND user_id IN (${MM_USER_A}, ${MM_USER_B}) AND side = 'SELL' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED') AND price_ticks <= $(price_with_offset "${product_line}" "${price}" 2000)"
  buy_order="$(place_order "${product_line}" "${TAKER_USER}" "mm-taker-buy-${product_line}-${RUN_ID}" "BUY" "LIMIT" "IOC" "$(price_with_offset "${product_line}" "${price}" 1000)" "${qty}" false false)"
  wait_order_filled "${product_line}" "${buy_order}" "${qty}"
  wait_account_processed_order "${product_line}" "${buy_order}"
  if is_margin_product "${product_line}"; then
    # 订单状态和账户命令可能先于异步成交审计落库；先等待成交价格，再用
    # 同一笔成交的价格校验持仓，避免把尚未写入审计表的空值当成期望值。
    wait_sql_nonzero "market-maker trade price ${buy_order}" \
      "SELECT count(*) FROM trading_match_trades WHERE product_line = '${product_line}' AND taker_order_id = ${buy_order}"
    local entry_price
    entry_price="$(query_value "SELECT price_ticks FROM trading_match_trades WHERE product_line = '${product_line}' AND taker_order_id = ${buy_order} ORDER BY event_time DESC LIMIT 1")"
    wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "${qty}" "${entry_price}"
    wait_sql_nonzero "market-maker bid quote ready ${product_line}" \
      "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND symbol = '${symbol}' AND user_id IN (${MM_USER_A}, ${MM_USER_B}) AND side = 'BUY' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED') AND price_ticks >= $(price_with_offset "${product_line}" "${price}" -2000)"
    sell_order="$(place_order "${product_line}" "${TAKER_USER}" "mm-taker-close-${product_line}-${RUN_ID}" "SELL" "LIMIT" "IOC" "$(price_with_offset "${product_line}" "${price}" -1000)" "${qty}" true false)"
    wait_order_filled "${product_line}" "${sell_order}" "${qty}"
    wait_account_processed_order "${product_line}" "${sell_order}"
    wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "0" "0"
    close_market_maker_inventory "${product_line}" "${symbol}" "${price}"
  else
    wait_sql_nonzero "market-maker bid quote ready ${product_line}" \
      "SELECT count(*) FROM trading_orders WHERE product_line = '${product_line}' AND symbol = '${symbol}' AND user_id IN (${MM_USER_A}, ${MM_USER_B}) AND side = 'BUY' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED') AND price_ticks >= $(price_with_offset "${product_line}" "${price}" -2000)"
    sell_order="$(place_order "${product_line}" "${TAKER_USER}" "mm-taker-spot-sell-${product_line}-${RUN_ID}" "SELL" "LIMIT" "IOC" "$(price_with_offset "${product_line}" "${price}" -1000)" "${qty}" false false)"
    wait_order_filled "${product_line}" "${sell_order}" "${qty}"
    wait_account_processed_order "${product_line}" "${sell_order}"
  fi
}

run_order_api_controls() {
  local product_line="$1"
  local price qty cancel_order_id amend_order_id self_maker self_taker self_taker_client post_maker post_taker post_taker_client replacement_price self_price post_price duplicate_first duplicate_second duplicate_client
  price="$(price_ticks_for "${product_line}")"
  qty=2
  echo "Scenario ${product_line}: API order controls"
  place_order_expect_rejected "${product_line}" "${NO_FUNDS_USER}" "no-funds-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "${price}" "${qty}" false false >/dev/null

  fund_user_for_line "${product_line}" "${CANCEL_USER}"
  duplicate_client="duplicate-place-${product_line}-${RUN_ID}"
  duplicate_first="$(place_order "${product_line}" "${CANCEL_USER}" "${duplicate_client}" "BUY" "LIMIT" "GTC" "$(price_with_offset "${product_line}" "${price}" -4500)" 1 false false)"
  duplicate_second="$(place_order "${product_line}" "${CANCEL_USER}" "${duplicate_client}" "BUY" "LIMIT" "GTC" "$(price_with_offset "${product_line}" "${price}" -4500)" 1 false false)"
  if [[ "${duplicate_first}" != "${duplicate_second}" ]]; then
    echo "Duplicate clientOrderId created two orders: ${duplicate_first} vs ${duplicate_second}" >&2
    exit 1
  fi
  wait_order_status "${product_line}" "${duplicate_first}" "ACCEPTED"
  cancel_order "${product_line}" "${CANCEL_USER}" "${duplicate_first}"
  wait_order_status "${product_line}" "${duplicate_first}" "CANCELED"

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
  price="$(price_with_offset "${product_line}" "$(price_ticks_for "${product_line}")" 5)"
  qty=3
  echo "Scenario ${product_line}: user opens position and closes by reduce-only API order"
  fund_user_for_line "${product_line}" "${MANUAL_MAKER_USER}"
  fund_user_for_line "${product_line}" "${TAKER_USER}"
  maker_order="$(place_order "${product_line}" "${MANUAL_MAKER_USER}" "manual-open-maker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "GTC" "${price}" "${qty}" false true)"
  wait_order_status "${product_line}" "${maker_order}" "ACCEPTED"
  taker_order="$(place_order "${product_line}" "${TAKER_USER}" "manual-open-taker-${product_line}-${RUN_ID}" "BUY" "LIMIT" "IOC" "${price}" "${qty}" false false)"
  if [[ "${ACCOUNT_RESTART_RECOVERY}" == "true" ]]; then
    restart_account_for_recovery "${product_line}"
  fi
  wait_order_filled "${product_line}" "${taker_order}" "${qty}"
  wait_account_processed_order "${product_line}" "${taker_order}"
  entry_price="$(query_value "SELECT price_ticks FROM trading_match_trades WHERE product_line = '${product_line}' AND taker_order_id = ${taker_order} ORDER BY event_time DESC LIMIT 1")"
  wait_position "${product_line}" "${TAKER_USER}" "${symbol}" "${qty}" "${entry_price}"
  wait_position "${product_line}" "${MANUAL_MAKER_USER}" "${symbol}" "-${qty}" "${entry_price}"

  if is_funding_product "${product_line}"; then
    run_funding_settlement_flow "${product_line}" "${symbol}"
  fi

  close_maker="$(place_order "${product_line}" "${MANUAL_MAKER_USER}" "manual-close-maker-${product_line}-${RUN_ID}" "BUY" "LIMIT" "GTC" "${price}" "${qty}" true true)"
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
  local funding_time event_time payload
  echo "Scenario ${product_line}: funding settlement while positions are open"
  funding_time="$(date -u -v-1S '+%Y-%m-%dT%H:%M:%SZ')"
  event_time="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  payload="{\"symbol\":\"${symbol}\",\"fundingRate\":0.001,\"nextFundingTime\":\"${funding_time}\",\"fundingIntervalHours\":8,\"sequence\":${RUN_SEQ},\"eventTime\":\"${event_time}\"}"
  produce_json "$(topic_name "${product_line}" "funding.rate")" "${symbol}" "${payload}"
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
  curl -fsS -X POST "http://localhost:9088/api/v1/risk/admin/runtime-config" \
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
  price="$(price_with_offset "${product_line}" "$(price_ticks_for "${product_line}")" 5)"
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
  open_maker="$(place_order "${product_line}" "${LIQ_MAKER_USER}" "liq-open-maker-${product_line}-${RUN_ID}" "${open_maker_side}" "LIMIT" "GTC" "${price}" "${qty}" false true)"
  wait_order_status "${product_line}" "${open_maker}" "ACCEPTED"
  open_taker="$(place_order "${product_line}" "${LIQ_USER}" "liq-open-taker-${product_line}-${RUN_ID}" "${open_taker_side}" "LIMIT" "IOC" "${price}" "${qty}" false false)"
  wait_order_filled "${product_line}" "${open_taker}" "${qty}"
  wait_account_processed_order "${product_line}" "${open_taker}"
  wait_position "${product_line}" "${LIQ_USER}" "${symbol}" "${expected_liq_qty}" "${price}"
  wait_sql_nonzero "risk snapshot after open ${product_line}" \
    "SELECT count(*) FROM risk_account_snapshots WHERE product_line = '${product_line}' AND user_id = ${LIQ_USER} AND maintenance_margin_units > 0 AND margin_ratio_ppm > 1"
  pre_ratio="$(query_value "SELECT margin_ratio_ppm FROM risk_account_snapshots WHERE product_line = '${product_line}' AND user_id = ${LIQ_USER} AND maintenance_margin_units > 0 AND margin_ratio_ppm > 1 ORDER BY event_time DESC, snapshot_id DESC LIMIT 1")"
  liq_threshold=$((pre_ratio * 80 / 100))
  if ((liq_threshold <= 1)); then
    echo "Unable to derive liquidation threshold from margin ratio ${pre_ratio}" >&2
    exit 1
  fi
  warning=$((liq_threshold - 1))
  close_maker="$(place_order "${product_line}" "${LIQ_MAKER_USER}" "liq-close-maker-${product_line}-${RUN_ID}" "${close_maker_side}" "LIMIT" "GTC" "${price}" "${qty}" false true)"
  wait_order_status "${product_line}" "${close_maker}" "ACCEPTED"
  update_liquidation_runtime_config "${liq_threshold}"
  update_risk_runtime_config "${warning}" "${liq_threshold}"
  adjust_product_balance "${LIQ_USER}" "$(account_type "${product_line}")" "$(settle_asset_for "${product_line}")" 1 \
    "smoke-${RUN_ID}-${product_line}-${LIQ_USER}-risk-recheck"
  wait_sql_nonzero "liquidation candidate ${product_line}" \
    "SELECT count(*) FROM risk_liquidation_candidates WHERE product_line = '${product_line}' AND user_id = ${LIQ_USER} AND symbol = '${symbol}'"
  wait_sql_nonzero "liquidation order filled ${product_line}" \
    "SELECT count(*) FROM liquidation_orders l JOIN risk_liquidation_candidates c ON c.candidate_id = l.candidate_id WHERE c.product_line = '${product_line}' AND l.user_id = ${LIQ_USER} AND l.symbol = '${symbol}' AND l.status = 'FILLED'"
  wait_position "${product_line}" "${LIQ_USER}" "${symbol}" "0" "0"
  local close_maker_status
  close_maker_status="$(query_value "SELECT status FROM trading_orders WHERE product_line = '${product_line}' AND order_id = ${close_maker}")"
  if [[ "${close_maker_status}" == "ACCEPTED" || "${close_maker_status}" == "PARTIALLY_FILLED" ]]; then
    cancel_order_if_open "${product_line}" "${LIQ_MAKER_USER}" "${close_maker}"
    wait_order_terminal "${product_line}" "${close_maker}"
  fi
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
  local settlement_price underlying_price
  settlement_price="$(( $(price_ticks_for "${product_line}") + 100 ))"
  underlying_price=0
  if is_option_product "${product_line}"; then
    # 期权事件使用资产最小单位，行情 tick 需要按价格精度转换，不能直接把 tick 当成 units。
    underlying_price=$((600100 * 10000000))
  fi
  # 生命周期测试必须走 instrument 唯一管理入口：先推进 SETTLING，再由入口固化
  # 结算价并发布不可变交割/行权事件，禁止脚本直接改表或伪造 Kafka 资金事件。
  curl --retry 3 --retry-delay 1 --retry-max-time 30 --retry-all-errors -fsS -X POST \
    "http://localhost:9080/api/v1/instruments/admin/${symbol}/status?status=SETTLING&productLine=${product_line}" \
    -H "X-Product-Line: ${product_line}" >/dev/null
  curl --retry 3 --retry-delay 1 --retry-max-time 30 --retry-all-errors -fsS -X POST \
    "http://localhost:9080/api/v1/instruments/admin/${symbol}/settlement?productLine=${product_line}&settlementPriceTicks=${settlement_price}&underlyingSettlementPriceUnits=${underlying_price}" \
    -H "X-Product-Line: ${product_line}" >/dev/null
}

run_lifecycle_flow() {
  local product_line="$1"
  local symbol price lifecycle_price qty maker taker settlement_price underlying_price lifecycle_ledger_count lifecycle_ledger_count_after expected_lifecycle_ledger_count
  if ! is_delivery_product "${product_line}" && ! is_option_product "${product_line}"; then
    return
  fi
  symbol="$(symbol_for "${product_line}")"
  price="$(price_ticks_for "${product_line}")"
  lifecycle_price="$(price_with_offset "${product_line}" "${price}" 5)"
  qty=2
  echo "Scenario ${product_line}: lifecycle settlement/exercise closes open positions"
  fund_user_for_line "${product_line}" "${LIFECYCLE_USER}"
  fund_user_for_line "${product_line}" "${LIFECYCLE_MAKER_USER}"
  maker="$(place_order "${product_line}" "${LIFECYCLE_MAKER_USER}" "life-open-maker-${product_line}-${RUN_ID}" "SELL" "LIMIT" "GTC" "${lifecycle_price}" "${qty}" false true)"
  wait_order_status "${product_line}" "${maker}" "ACCEPTED"
  taker="$(place_order "${product_line}" "${LIFECYCLE_USER}" "life-open-taker-${product_line}-${RUN_ID}" "BUY" "LIMIT" "IOC" "${lifecycle_price}" "${qty}" false false)"
  wait_order_filled "${product_line}" "${taker}" "${qty}"
  wait_account_processed_order "${product_line}" "${taker}"
  wait_position "${product_line}" "${LIFECYCLE_USER}" "${symbol}" "${qty}" "${lifecycle_price}"
  stop_provider_by_name price-refresher
  seed_lifecycle_settlement_price "${product_line}"
  sleep 1
  publish_lifecycle_event "${product_line}" "${symbol}"
  wait_position "${product_line}" "${LIFECYCLE_USER}" "${symbol}" "0" "0"
  wait_position "${product_line}" "${LIFECYCLE_MAKER_USER}" "${symbol}" "0" "0"
  wait_sql_equals "${product_line} lifecycle risk position projection zero" \
    "SELECT count(*) FROM (SELECT DISTINCT ON (user_id, symbol, margin_mode, position_side) signed_quantity_steps FROM risk_position_snapshots WHERE product_line = '${product_line}' AND user_id IN (${LIFECYCLE_USER}, ${LIFECYCLE_MAKER_USER}) AND symbol = '${symbol}' ORDER BY user_id, symbol, margin_mode, position_side, event_time DESC, snapshot_id DESC) latest WHERE signed_quantity_steps <> 0" \
    "0"
  wait_sql_equals "${product_line} instrument closed" \
    "SELECT count(*) FROM instrument_product_current_versions p JOIN instruments i ON i.symbol = p.symbol AND i.version = p.version WHERE p.product_line = '${product_line}' AND p.symbol = '${symbol}' AND i.status = 'CLOSED'" \
    "1"
  expected_lifecycle_ledger_count=2
  wait_sql_equals "${product_line} lifecycle ledger projection" \
    "SELECT count(*) FROM account_product_ledger_entries WHERE user_id IN (${LIFECYCLE_USER}, ${LIFECYCLE_MAKER_USER}) AND account_type = '$(account_type "${product_line}")' AND reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE') AND amount_units <> 0" \
    "${expected_lifecycle_ledger_count}"
  lifecycle_ledger_count="$(query_value "SELECT count(*) FROM account_product_ledger_entries WHERE user_id IN (${LIFECYCLE_USER}, ${LIFECYCLE_MAKER_USER}) AND account_type = '$(account_type "${product_line}")' AND reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE') AND amount_units <> 0")"
  if [[ "${product_line}" == "OPTION" ]]; then
    underlying_price=$((600100 * 10000000))
  else
    underlying_price=0
  fi
  settlement_price="$(( $(price_ticks_for "${product_line}") + 100 ))"
  # CLOSED 状态的重复确认只能返回当前版本，不能再次发布生命周期事件或重复记账。
  curl --retry 3 --retry-delay 1 --retry-max-time 30 --retry-all-errors -fsS -X POST \
    "http://localhost:9080/api/v1/instruments/admin/${symbol}/settlement?productLine=${product_line}&settlementPriceTicks=${settlement_price}&underlyingSettlementPriceUnits=${underlying_price}" \
    -H "X-Product-Line: ${product_line}" >/dev/null
  sleep 2
  lifecycle_ledger_count_after="$(query_value "SELECT count(*) FROM account_product_ledger_entries WHERE user_id IN (${LIFECYCLE_USER}, ${LIFECYCLE_MAKER_USER}) AND account_type = '$(account_type "${product_line}")' AND reference_type IN ('DELIVERY_SETTLEMENT', 'OPTION_EXERCISE') AND amount_units <> 0")"
  if [[ "${lifecycle_ledger_count_after}" != "${lifecycle_ledger_count}" ]]; then
    echo "${product_line} lifecycle duplicate settlement changed ledger count: before=${lifecycle_ledger_count} after=${lifecycle_ledger_count_after}" >&2
    exit 1
  fi
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
  if [[ "${ACCOUNT_RESTART_RECOVERY}" == "true" ]]; then
    # 现货没有衍生品持仓，但账户余额、资产冻结和现货预占同样必须经受进程重启恢复。
    restart_account_for_recovery "${product_line}"
    wait_account_processed_order "${product_line}" "${taker}"
  fi
  wait_sql_equals "spot has no derivative positions" \
    "SELECT count(*) FROM account_positions WHERE product_line = 'SPOT' AND user_id IN (${MANUAL_MAKER_USER}, ${TAKER_USER})" \
    "0"
  wait_sql_equals "spot asset balances projected after recovery" \
    "SELECT CASE WHEN COALESCE((SELECT available_units FROM account_product_balances WHERE user_id = ${TAKER_USER} AND account_type = 'SPOT' AND asset = 'BTC'), 0) > COALESCE((SELECT available_units FROM account_product_balances WHERE user_id = ${MANUAL_MAKER_USER} AND account_type = 'SPOT' AND asset = 'BTC'), 0) THEN 1 ELSE 0 END" \
    "1"
  seller_btc="$(query_value "SELECT COALESCE((SELECT available_units FROM account_product_balances WHERE user_id = ${MANUAL_MAKER_USER} AND account_type = 'SPOT' AND asset = 'BTC'), 0)")"
  buyer_btc="$(query_value "SELECT COALESCE((SELECT available_units FROM account_product_balances WHERE user_id = ${TAKER_USER} AND account_type = 'SPOT' AND asset = 'BTC'), 0)")"
  if ((buyer_btc <= seller_btc)); then
    echo "Expected spot buyer BTC to increase relative to seller after trade, seller=${seller_btc}, buyer=${buyer_btc}" >&2
    exit 1
  fi
  wait_sql_equals "spot order locks released or terminal" \
    "SELECT COALESCE(SUM(locked_units), 0) FROM account_state_order_locks WHERE product_line = 'SPOT' AND user_id IN (${MANUAL_MAKER_USER}, ${TAKER_USER})" \
    "0"
}

is_power_of_two() {
  local value="$1"
  ((value > 0 && (value & (value - 1)) == 0))
}

require_non_negative_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[0-9]+$ ]]; then
    echo "${name} must be a non-negative integer, actual=${value}" >&2
    exit 1
  fi
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  require_non_negative_integer "${name}" "${value}"
  if ((value <= 0)); then
    echo "${name} must be positive, actual=${value}" >&2
    exit 1
  fi
}

validate_multi_symbol_stress_config() {
  require_positive_integer "STRESS_SYMBOL_COUNT" "${STRESS_SYMBOL_COUNT}"
  require_positive_integer "STRESS_USER_COUNT" "${STRESS_USER_COUNT}"
  require_positive_integer "STRESS_LOAD_CONCURRENCY" "${STRESS_LOAD_CONCURRENCY}"
  require_positive_integer "STRESS_MATCHING_KAFKA_CONCURRENCY" "${STRESS_MATCHING_KAFKA_CONCURRENCY}"
  require_positive_integer "STRESS_MATCHING_MAX_POLL_RECORDS" "${STRESS_MATCHING_MAX_POLL_RECORDS}"
  require_positive_integer "STRESS_MATCHING_ENGINE_SHARDS" "${STRESS_MATCHING_ENGINE_SHARDS}"
  require_positive_integer "STRESS_MATCHING_RISK_SHARDS" "${STRESS_MATCHING_RISK_SHARDS}"
  require_positive_integer "STRESS_LIQUIDATION_KAFKA_CONCURRENCY" "${STRESS_LIQUIDATION_KAFKA_CONCURRENCY}"
  require_positive_integer "STRESS_LIQUIDATION_WAIT_SECONDS" "${STRESS_LIQUIDATION_WAIT_SECONDS}"
  require_positive_integer "STRESS_LIQUIDATION_MARK_FACTOR_PPM" "${STRESS_LIQUIDATION_MARK_FACTOR_PPM}"
  require_positive_integer "STRESS_LIQUIDATION_WALLET_RATE_PPM" "${STRESS_LIQUIDATION_WALLET_RATE_PPM}"
  require_positive_integer "STRESS_KAFKA_LAG_SAMPLE_SECONDS" "${STRESS_KAFKA_LAG_SAMPLE_SECONDS}"
  require_non_negative_integer "STRESS_TARGET_TPS" "${STRESS_TARGET_TPS}"
  require_non_negative_integer "STRESS_HOT_SYMBOL_COUNT" "${STRESS_HOT_SYMBOL_COUNT}"
  require_positive_integer "STRESS_HOT_TRAFFIC_PERCENT" "${STRESS_HOT_TRAFFIC_PERCENT}"
  if ((STRESS_SYMBOL_COUNT < 20)); then
    echo "STRESS_SYMBOL_COUNT must be at least 20 for multi-symbol stress" >&2
    exit 1
  fi
  if ((STRESS_HOT_SYMBOL_COUNT >= STRESS_SYMBOL_COUNT)); then
    echo "STRESS_HOT_SYMBOL_COUNT must be less than STRESS_SYMBOL_COUNT; use 0 for uniform traffic" >&2
    exit 1
  fi
  if ((STRESS_HOT_TRAFFIC_PERCENT < 1 || STRESS_HOT_TRAFFIC_PERCENT > 100)); then
    echo "STRESS_HOT_TRAFFIC_PERCENT must be in [1, 100]" >&2
    exit 1
  fi
  if ! is_power_of_two "${STRESS_MATCHING_ENGINE_SHARDS}"; then
    echo "STRESS_MATCHING_ENGINE_SHARDS must be a power of two" >&2
    exit 1
  fi
  if ! is_power_of_two "${STRESS_MATCHING_RISK_SHARDS}"; then
    echo "STRESS_MATCHING_RISK_SHARDS must be a power of two" >&2
    exit 1
  fi
  case "${STRESS_INTERNAL_MARKET_MAKER_WHITELIST}" in
    true|false) ;;
    *)
      echo "STRESS_INTERNAL_MARKET_MAKER_WHITELIST must be true or false" >&2
      exit 1
      ;;
  esac
  case "${STRESS_RESET_PG_STAT_STATEMENTS}" in
    true|false) ;;
    *)
      echo "STRESS_RESET_PG_STAT_STATEMENTS must be true or false" >&2
      exit 1
      ;;
  esac
  case "${STRESS_SCENARIO}" in
    trade|liquidation) ;;
    *)
      echo "STRESS_SCENARIO must be trade or liquidation, actual=${STRESS_SCENARIO}" >&2
      exit 1
      ;;
  esac
  if [[ "${STRESS_SCENARIO}" == "liquidation" && "${PRODUCT_LINES}" != "LINEAR_PERPETUAL" ]]; then
    echo "STRESS_SCENARIO=liquidation currently supports only LINEAR_PERPETUAL" >&2
    exit 1
  fi
  if [[ "${STRESS_SCENARIO}" == "liquidation" && "${STRESS_HOT_SYMBOL_COUNT}" != "0" ]]; then
    echo "STRESS_SCENARIO=liquidation requires STRESS_HOT_SYMBOL_COUNT=0 so pre-funding matches uniform symbol assignment" >&2
    exit 1
  fi
  if [[ "${STRESS_SCENARIO}" == "liquidation" && "${STRESS_SYMBOL_COUNT}" != "20" ]]; then
    echo "STRESS_SCENARIO=liquidation currently requires STRESS_SYMBOL_COUNT=20" >&2
    exit 1
  fi
  if ((STRESS_LIQUIDATION_MARK_FACTOR_PPM >= 1000000)); then
    echo "STRESS_LIQUIDATION_MARK_FACTOR_PPM must be below 1000000" >&2
    exit 1
  fi
}

validate_topic_reset_config() {
  case "${RESET_KAFKA}" in
    true|false) ;;
    *)
      echo "RESET_KAFKA 必须是 true 或 false" >&2
      exit 1
      ;;
  esac
  case "${CREATE_KAFKA_TOPICS}" in
    true|false) ;;
    *)
      echo "CREATE_KAFKA_TOPICS 必须是 true 或 false" >&2
      exit 1
      ;;
  esac
  case "${KAFKA_RESET_SHARED_TOPICS}" in
    true|false) ;;
    *)
      echo "KAFKA_RESET_SHARED_TOPICS 必须是 true 或 false" >&2
      exit 1
      ;;
  esac
  case "${KAFKA_TRUNCATE_TOPICS}" in
    true|false) ;;
    *)
      echo "KAFKA_TRUNCATE_TOPICS 必须是 true 或 false" >&2
      exit 1
      ;;
  esac
  if [[ "${RESET_KAFKA}" == "true" && "${KAFKA_TRUNCATE_TOPICS}" == "true" ]]; then
    echo "RESET_KAFKA 与 KAFKA_TRUNCATE_TOPICS 不能同时启用" >&2
    exit 1
  fi
  if [[ "${RESET_KAFKA}" == "true" && "${KAFKA_RESET_SHARED_TOPICS}" != "true" ]]; then
    echo "重建测试数据库时必须同时设置 KAFKA_RESET_SHARED_TOPICS=true，避免 earliest 重放旧共享命令" >&2
    exit 1
  fi
  if [[ "${KAFKA_RESET_SHARED_TOPICS}" == "true" && "${RESET_KAFKA}" != "true" && "${KAFKA_TRUNCATE_TOPICS}" != "true" ]]; then
    echo "KAFKA_RESET_SHARED_TOPICS=true 必须同时启用 RESET_KAFKA 或 KAFKA_TRUNCATE_TOPICS" >&2
    exit 1
  fi
  if [[ "${KAFKA_TRUNCATE_TOPICS}" == "true" && "${KAFKA_RESET_SHARED_TOPICS}" != "true" ]]; then
    echo "KAFKA_TRUNCATE_TOPICS=true 时必须同时清理共享 Topic，避免 earliest 重放旧共享命令" >&2
    exit 1
  fi
  if [[ "${KAFKA_TRUNCATE_TOPICS}" == "true" && "${CREATE_KAFKA_TOPICS}" != "true" ]]; then
    echo "KAFKA_TRUNCATE_TOPICS=true 时必须同时设置 CREATE_KAFKA_TOPICS=true，以便重建 compact Topic" >&2
    exit 1
  fi
  if [[ "${RESET_KAFKA}" == "true" && "${CREATE_KAFKA_TOPICS}" != "true" ]]; then
    echo "RESET_KAFKA=true 时必须同时设置 CREATE_KAFKA_TOPICS=true，否则价格种子会写入不存在的 Topic" >&2
    exit 1
  fi
}

run_line() {
  local product_line="$1"
  echo "========== Product line ${product_line} =========="
  reset_database "${product_line}"
  reset_kafka_topics "${product_line}"
  rm -rf "${ROOT_DIR}/data/kafka-streams/$(product_slug "${product_line}")"
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    seed_stress_prices "${product_line}"
  else
    seed_prices_for_line "${product_line}"
  fi
  # 价格生产者先于业务 provider 启动，给常驻 Kafka 客户端预留连接和元数据
  # 初始化时间；否则本机同时拉起多个 JVM 时，做市商已经分配分区，生产者仍
  # 可能没有打开 FIFO，启动补发会被阻塞。
  start_price_refresher "${product_line}"
  start_providers_for_line "${product_line}"
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    seed_stress_prices "${product_line}"
  else
    seed_prices_for_line "${product_line}"
  fi
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    # 先完成账户快照初始化，再启动做市，避免启动竞态把账户命令投递到未初始化分区。
    if [[ "${STRESS_SCENARIO}" == "liquidation" ]]; then
      fund_stress_accounts_for_line "${product_line}"
      start_provider market-maker "${product_line}"
      run_multi_symbol_liquidation_stress_flow "${product_line}"
    else
      fund_stress_accounts_for_line "${product_line}"
      start_provider market-maker "${product_line}"
      wait_stress_market_maker_ready "${product_line}"
      run_multi_symbol_stress_flow "${product_line}"
    fi
    stop_provider_by_name market-maker
    stop_all_providers
    local reconcile_file="${TMP_DIR}/${product_line}-funds-reconcile.txt"
    if [[ "${RECONCILE_FUNDS}" == "true" ]]; then
      PRODUCT_LINES="${product_line}" \
        DB_HOST=localhost \
        DB_USER="${DB_USER}" \
        DB_PASSWORD="${DB_PASSWORD}" \
        DB_NAME="${DB_NAME}" \
        POSTGRES_PORT="${POSTGRES_PORT}" \
        "${ROOT_DIR}/scripts/product-line-funds-reconcile.sh" | tee "${reconcile_file}"
    fi
    cat "${STRESS_LAST_SUMMARY_FILE}" >>"${STRESS_REPORT_FILE}"
    {
      echo "<details><summary>${product_line} funds-reconcile raw output</summary>"
      echo
      echo '```'
      cat "${reconcile_file}" 2>/dev/null || true
      echo '```'
      echo
      echo "</details>"
      echo
    } >>"${STRESS_REPORT_FILE}"
    echo "Product line ${product_line} multi-symbol stress passed"
    return
  fi
  fund_user_for_line "${product_line}" "${MM_USER_A}"
  fund_user_for_line "${product_line}" "${MM_USER_B}"
  fund_user_for_line "${product_line}" "${TAKER_USER}"
  start_provider market-maker "${product_line}"
  if [[ "${RUN_WEBSOCKET_SMOKE}" == "true" ]]; then
    start_live_websocket_smoke "${product_line}" "$(symbol_for "${product_line}")"
  fi
  run_market_maker_taker_flow "${product_line}"
  run_order_api_controls "${product_line}"
  run_manual_open_close_flow "${product_line}"
  run_liquidation_flow "${product_line}"
  run_lifecycle_flow "${product_line}"
  run_spot_asset_flow "${product_line}"
  if [[ "${RUN_WEBSOCKET_SMOKE}" == "true" ]]; then
    finish_live_websocket_smoke
  fi
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
  validate_topic_reset_config
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    require_command "$(kafka_consumer_groups_cmd)"
    validate_multi_symbol_stress_config
  fi
  wait_until "PostgreSQL" 120 pg_isready -h localhost -p "${POSTGRES_PORT}" -U "${DB_USER}"
  wait_until "Kafka" 120 kafka_ready
  ensure_database
  package_services
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    local product_line_count report_scope
    product_line_count="$(wc -w <<<"${PRODUCT_LINES}" | tr -d '[:space:]')"
    if ((product_line_count == 4)); then
      report_scope="四产品线"
    else
      report_scope="${PRODUCT_LINES}"
    fi
    mkdir -p "$(dirname "${STRESS_REPORT_FILE}")"
    {
      echo "# ${report_scope} ${STRESS_SYMBOL_COUNT} Symbol / ${STRESS_USER_COUNT} 用户真实链路压测报告"
      echo
      echo "时间：$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
      echo
      echo "## 压测口径"
      echo
      echo "- 每次只启动一个产品线，wallet 服务未启动。"
      echo "- 每个产品线 active symbols：${STRESS_SYMBOL_COUNT}。"
      echo "- Maker accounts：${STRESS_SYMBOL_COUNT}，每个 symbol 一个 maker account；market-maker provider 同时配置 ${STRESS_SYMBOL_COUNT} 个 strategy。"
      echo "- Maker 高频：初始 ${STRESS_MAKER_DEPTH_LEVELS} 档，压测阶段每个 phase 刷新 ${STRESS_MAKER_REFRESH_CYCLES} 轮，每轮 ${STRESS_MAKER_REFRESH_LEVELS} 档。"
      if [[ "${STRESS_SCENARIO}" == "liquidation" ]]; then
        echo "- 用户：${STRESS_USER_COUNT} 个 taker 用户经 gateway 单笔真实开仓；全部持仓安全后统一施加标记价冲击并等待强平结算，并发 ${STRESS_LOAD_CONCURRENCY}，目标速率 ${STRESS_TARGET_TPS} TPS（0 表示不限速）。"
      else
        echo "- 用户：每个产品线 ${STRESS_USER_COUNT} 个 taker 用户，经 gateway 单笔真实下单；open 与 close/sell 两阶段均按并发 ${STRESS_LOAD_CONCURRENCY} 提交，目标速率为 ${STRESS_TARGET_TPS} TPS（0 表示不限速）。"
      fi
      echo "- 流量分布：hotSymbolCount=${STRESS_HOT_SYMBOL_COUNT}，hotTrafficPercent=${STRESS_HOT_TRAFFIC_PERCENT}；hotSymbolCount=0 表示均匀分布。"
      echo "- 撮合：Kafka concurrency=${STRESS_MATCHING_KAFKA_CONCURRENCY}，maxPollRecords=${STRESS_MATCHING_MAX_POLL_RECORDS}，matching engines=${STRESS_MATCHING_ENGINE_SHARDS}，risk engines=${STRESS_MATCHING_RISK_SHARDS}。"
      echo "- 内部做市账号白名单：${STRESS_INTERNAL_MARKET_MAKER_WHITELIST}；真实用户与白名单做市成交仍执行完整资金结算。"
      echo "- PostgreSQL 慢 SQL：检测到 pg_stat_statements 时 reset=${STRESS_RESET_PG_STAT_STATEMENTS}，报告按 total execution time 输出前 20 条。"
      echo "- Kafka lag：每 ${STRESS_KAFKA_LAG_SAMPLE_SECONDS}s 采样 matching、account、order result、position maintenance、risk 和 liquidation consumer group。"
      echo "- Trading Outbox：按 phase、provider owner、aggregate type、topic、event type 精确还原 pending 峰值、最大未发布年龄和最终积压，不执行轮询 SQL。"
      echo "- Account outbox：batchSize=${STRESS_ACCOUNT_OUTBOX_BATCH_SIZE}，publishDelayMs=${STRESS_ACCOUNT_OUTBOX_PUBLISH_DELAY_MS}，maxInFlight=${STRESS_ACCOUNT_OUTBOX_MAX_IN_FLIGHT}，maxRowsPerKey=${STRESS_ACCOUNT_OUTBOX_MAX_ROWS_PER_KEY}。"
      echo "- Trading outbox：batchSize=${STRESS_ORDER_OUTBOX_BATCH_SIZE}，publishDelayMs=${STRESS_ORDER_OUTBOX_PUBLISH_DELAY_MS}，maxInFlight=${STRESS_ORDER_OUTBOX_MAX_IN_FLIGHT}，maxRowsPerKey=${STRESS_ORDER_OUTBOX_MAX_ROWS_PER_KEY}；财务指令优先 claim。"
      echo "- Risk outbox：batchSize=${STRESS_RISK_OUTBOX_BATCH_SIZE}，publishDelayMs=${STRESS_RISK_OUTBOX_PUBLISH_DELAY_MS}，maxRowsPerKey=${STRESS_RISK_OUTBOX_MAX_ROWS_PER_KEY}。"
      echo "- 资金准备：批量 fixture 写入 balance、ledger 和 admin adjustment；下单、撮合、Kafka、account 结算全部走真实 provider 链路。"
      echo "- 临时日志目录：\`${TMP_DIR}\`"
      echo
    } >"${STRESS_REPORT_FILE}"
  fi
  local product_line
  for product_line in ${PRODUCT_LINES}; do
    run_line "${product_line}"
  done
  if [[ "${MULTI_SYMBOL_STRESS}" == "true" ]]; then
    {
      echo "## 总结"
      echo
      echo "所有已执行产品线均完成 active symbol、maker account、用户订单、撮合成交、account 结算、余额/仓位/预占/outbox 和资金守恒核对。"
    } >>"${STRESS_REPORT_FILE}"
    echo "multi_symbol_stress_report=${STRESS_REPORT_FILE}"
  fi
}

main "$@"
