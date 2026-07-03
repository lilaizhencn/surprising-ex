#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="${DB_NAME:-surprising_exchange}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/${DB_NAME}}"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
BTC_SYMBOL="BTC-USDT"
ETH_SYMBOL="ETH-USDT"
COIN_SYMBOL="BTC-USD"
SPOT_SYMBOL="BTC-USDT-SPOT"
BTC_TICK_UNITS=10000000
ETH_TICK_UNITS=1000000
BTC_PRICE_TICKS=600000
ETH_PRICE_TICKS=300000
COIN_PRICE_TICKS=600000
SPOT_PRICE_TICKS=600000
MM_PROVIDER_STRATEGY_ID="btc-usdt-mm-a"
MM_PROVIDER_USER_A=900001
MM_PROVIDER_USER_B=900002
MARK_SEQUENCE_BASE=$((100000 + RUN_SEQ * 100))
NEXT_MARK_SEQUENCE="${MARK_SEQUENCE_BASE}"
QUANTITY_STEPS="${QUANTITY_STEPS:-10}"
PAIR_COUNT="${PAIR_COUNT:-50}"
LOAD_CONCURRENCY="${LOAD_CONCURRENCY:-16}"
BOOK_DEPTH_LEVELS="${BOOK_DEPTH_LEVELS:-60}"
START_INFRA="${START_INFRA:-true}"
STOP_INFRA="${STOP_INFRA:-false}"
BUILD_SERVICES="${BUILD_SERVICES:-auto}"
RESET_STATE="${RESET_STATE:-true}"
START_PROVIDERS="${START_PROVIDERS:-true}"
STOP_PROVIDERS="${STOP_PROVIDERS:-true}"
RUN_FAILURE_SCENARIOS="${RUN_FAILURE_SCENARIOS:-true}"
KEEP_TMP="${KEEP_TMP:-false}"
WS_TIMEOUT="${WS_TIMEOUT:-900}"
FULL_STACK_EXTERNAL_INDEX_WS_ENABLED="${FULL_STACK_EXTERNAL_INDEX_WS_ENABLED:-false}"
FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP="${FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP:-false}"
MM_REFERENCE_MARKET_ENABLED="${MM_REFERENCE_MARKET_ENABLED:-false}"
MM_REFERENCE_MARKET_WEBSOCKET_ENABLED="${MM_REFERENCE_MARKET_WEBSOCKET_ENABLED:-false}"
TMP_DIR="$(mktemp -d /tmp/surprising-full-stack-real-config.XXXXXX)"
WS_STOP_FILE="${TMP_DIR}/ws.stop"
COMPOSE_BIN=""
COMPOSE_SUBCOMMAND=""
INFRA_MODE=""
DOCKER_NETWORK="${DOCKER_NETWORK:-surprising-ex-net}"
KAFKA_IMAGE="${KAFKA_IMAGE:-apache/kafka:3.7.0}"

FULL_MAKER_USER=$((7000000000 + RUN_SEQ))
FULL_TAKER_USER=$((7100000000 + RUN_SEQ))
PARTIAL_MAKER_USER=$((7200000000 + RUN_SEQ))
PARTIAL_TAKER_USER=$((7300000000 + RUN_SEQ))
CANCEL_USER=$((7400000000 + RUN_SEQ))
CLOSE_MAKER_USER=$((7450000000 + RUN_SEQ))
TRIGGER_MAKER_USER=$((7460000000 + RUN_SEQ))
ISOLATED_USER=$((7470000000 + RUN_SEQ))
ISOLATED_MAKER_USER=$((7471000000 + RUN_SEQ))
ALL_CANCEL_USER_START=$((7500000000 + RUN_SEQ * 10))
LOAD_MAKER_START=$((9000000000 + RUN_SEQ * 1000))
LOAD_TAKER_START=$((9500000000 + RUN_SEQ * 1000))
DEPTH_USER=$((9600000000 + RUN_SEQ))
FAULT_MAKER_USER=$((9700000000 + RUN_SEQ))
FAULT_TAKER_USER=$((9701000000 + RUN_SEQ))
ACCOUNT_FAULT_MAKER_USER=$((9702000000 + RUN_SEQ))
ACCOUNT_FAULT_TAKER_USER=$((9703000000 + RUN_SEQ))
SELF_TRADE_USER=$((9704000000 + RUN_SEQ))
POST_ONLY_MAKER_USER=$((9705000000 + RUN_SEQ))
POST_ONLY_TAKER_USER=$((9706000000 + RUN_SEQ))
NO_FUNDS_USER=$((9707000000 + RUN_SEQ))
TOPUP_USER=$((9800000000 + RUN_SEQ))
TOPUP_MAKER_USER=$((9801000000 + RUN_SEQ))
LIQ_USER=$((9802000000 + RUN_SEQ))
LIQ_OPEN_MAKER_USER=$((9803000000 + RUN_SEQ))
LIQ_CLOSE_MAKER_USER=$((9804000000 + RUN_SEQ))
INSURANCE_DEFICIT_USER=$((9805000000 + RUN_SEQ))
ADL_DEFICIT_USER=$((9806000000 + RUN_SEQ))
ADL_TARGET_USER=$((9807000000 + RUN_SEQ))
ADL_OPEN_MAKER_USER=$((9808000000 + RUN_SEQ))
COIN_MAKER_USER=$((9810000000 + RUN_SEQ))
COIN_TAKER_USER=$((9811000000 + RUN_SEQ))
SPOT_SELLER_USER=$((9820000000 + RUN_SEQ))
SPOT_BUYER_USER=$((9821000000 + RUN_SEQ))
POSITION_MODE_USER=$((9830000000 + RUN_SEQ))
POSITION_MODE_LONG_MAKER_USER=$((9831000000 + RUN_SEQ))
POSITION_MODE_SHORT_MAKER_USER=$((9832000000 + RUN_SEQ))
POSITION_MODE_BLOCK_ORDER_USER=$((9833000000 + RUN_SEQ))
POSITION_MODE_BLOCK_TRIGGER_USER=$((9834000000 + RUN_SEQ))
POSITION_MODE_SYMBOL="${BTC_SYMBOL}"
POSITION_MODE_TICK_UNITS="${BTC_TICK_UNITS}"

FULL_PRICE_TICKS="${BTC_PRICE_TICKS}"
PARTIAL_PRICE_TICKS=$((BTC_PRICE_TICKS + 100))
CANCEL_PRICE_TICKS=$((BTC_PRICE_TICKS + 10000))
ALL_CANCEL_BID_PRICE=$((BTC_PRICE_TICKS - 10000))
ALL_CANCEL_ASK_PRICE=$((BTC_PRICE_TICKS + 11000))
LOAD_PRICE_TICKS=$((BTC_PRICE_TICKS + 20000))
FAULT_PRICE_TICKS=$((BTC_PRICE_TICKS + 21000))
ACCOUNT_FAULT_PRICE_TICKS=$((BTC_PRICE_TICKS + 22000))
SELF_TRADE_PRICE_TICKS=$((BTC_PRICE_TICKS + 23000))
POST_ONLY_PRICE_TICKS=$((BTC_PRICE_TICKS + 24000))
DEPTH_START_PRICE_TICKS=$((BTC_PRICE_TICKS + 25000))
PARTIAL_MAKER_QTY=$((QUANTITY_STEPS * 2))
PARTIAL_TAKER_QTY=$((QUANTITY_STEPS - 3))
LOAD_QTY="${QUANTITY_STEPS}"
LOAD_TOTAL_QTY=$((PAIR_COUNT * LOAD_QTY))
CLOSE_QTY=4
TRIGGER_CLOSE_QTY=$((QUANTITY_STEPS - CLOSE_QTY))
ISOLATED_QTY=10
TOPUP_QTY=10
LIQ_QTY=10
LIQ_MARK_PRICE_TICKS=160000
DEFAULT_WARNING_MARGIN_RATIO_PPM=800000
DEFAULT_LIQUIDATION_MARGIN_RATIO_PPM=1000000
DEFAULT_FULL_CLOSE_MARGIN_RATIO_PPM=3000000
COIN_QTY=10
COIN_CLOSE_PRICE_TICKS=$((COIN_PRICE_TICKS + 10000))
SPOT_QTY=2
POSITION_MODE_QTY=3
POSITION_MODE_LONG_PRICE_TICKS="${BTC_PRICE_TICKS}"
POSITION_MODE_SHORT_PRICE_TICKS="${BTC_PRICE_TICKS}"
POSITION_MODE_BLOCK_PRICE_TICKS=$((BTC_PRICE_TICKS + 32000))

PROVIDER_NAMES=()
PROVIDER_PIDS=()
PIDS=()
SMOKE_PROVIDERS=(
  instrument candlestick index-price mark-price matching account risk liquidation
  funding insurance adl websocket order trigger gateway market-maker
)

cleanup() {
  touch "${WS_STOP_FILE}" >/dev/null 2>&1 || true
  if [[ "${STOP_PROVIDERS}" == "true" ]] && ((${#PROVIDER_PIDS[@]})); then
    for pid in "${PROVIDER_PIDS[@]}"; do
      if kill -0 "${pid}" >/dev/null 2>&1; then
        kill "${pid}" >/dev/null 2>&1 || true
      fi
    done
    for pid in "${PROVIDER_PIDS[@]}"; do
      wait "${pid}" >/dev/null 2>&1 || true
    done
  fi
  if ((${#PIDS[@]})); then
    for pid in "${PIDS[@]}"; do
      wait "${pid}" >/dev/null 2>&1 || true
    done
  fi
  if [[ "${STOP_INFRA}" == "true" ]]; then
    stop_infra >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_TMP}" == "true" ]]; then
    echo "Keeping full-stack logs in ${TMP_DIR}" >&2
  else
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE_BIN="docker"
    COMPOSE_SUBCOMMAND="compose"
    INFRA_MODE="compose"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_BIN="docker-compose"
    COMPOSE_SUBCOMMAND=""
    INFRA_MODE="compose"
    return
  fi
  if command -v docker >/dev/null 2>&1 && docker version >/dev/null 2>&1; then
    INFRA_MODE="docker"
    return
  fi
  echo "Missing running Docker environment: need docker compose, docker-compose, or a working docker daemon" >&2
  exit 1
}

compose() {
  if [[ "${INFRA_MODE}" == "docker" ]]; then
    echo "compose command is unavailable in direct docker mode" >&2
    exit 1
  fi
  if [[ -n "${COMPOSE_SUBCOMMAND}" ]]; then
    "${COMPOSE_BIN}" "${COMPOSE_SUBCOMMAND}" -f "${COMPOSE_FILE}" "$@"
  else
    "${COMPOSE_BIN}" -f "${COMPOSE_FILE}" "$@"
  fi
}

compose_exec() {
  if [[ "${INFRA_MODE}" == "docker" ]]; then
    local service="$1"
    shift
    if [[ "${service}" == "kafka" && "$#" -gt 0 && "$1" == kafka-*.sh ]]; then
      set -- "/opt/kafka/bin/$1" "${@:2}"
    fi
    docker exec -i "surprising-ex-${service}" "$@"
    return
  fi
  compose exec -T "$@"
}

start_infra() {
  if [[ "${INFRA_MODE}" == "compose" ]]; then
    compose up -d postgres kafka >/dev/null
    return
  fi
  docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1 || docker network create "${DOCKER_NETWORK}" >/dev/null
  if docker container inspect surprising-ex-postgres >/dev/null 2>&1; then
    local mapped_postgres_port
    mapped_postgres_port="$(docker inspect -f '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' \
      surprising-ex-postgres 2>/dev/null || true)"
    if [[ "${mapped_postgres_port}" != "${POSTGRES_PORT}" ]]; then
      docker rm -f surprising-ex-postgres >/dev/null
    fi
  fi
  if docker container inspect surprising-ex-postgres >/dev/null 2>&1; then
    docker start surprising-ex-postgres >/dev/null
  else
    docker run -d --name surprising-ex-postgres \
      --network "${DOCKER_NETWORK}" \
      -e POSTGRES_DB="${DB_NAME}" \
      -e POSTGRES_USER="${DB_USER}" \
      -e POSTGRES_PASSWORD="${DB_PASSWORD}" \
      -p "${POSTGRES_PORT}:5432" \
      -v surprising-ex-postgres:/var/lib/postgresql/data \
      -v "${ROOT_DIR}/init.sql:/docker-entrypoint-initdb.d/init.sql:ro" \
      postgres:16 >/dev/null
  fi
  if docker container inspect surprising-ex-kafka >/dev/null 2>&1; then
    docker start surprising-ex-kafka >/dev/null
  else
    start_direct_kafka
  fi
}

start_direct_kafka() {
  docker run -d --name surprising-ex-kafka \
    --network "${DOCKER_NETWORK}" \
    --network-alias kafka \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
    -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    -p 9092:9092 \
    -v surprising-ex-kafka:/tmp/kraft-combined-logs \
    "${KAFKA_IMAGE}" >/dev/null
}

stop_infra() {
  if [[ "${INFRA_MODE}" == "compose" ]]; then
    compose down
    return
  fi
  docker rm -f surprising-ex-kafka surprising-ex-postgres >/dev/null 2>&1 || true
}

wait_until() {
  local description="$1"
  local timeout_seconds="$2"
  shift 2
  local deadline=$((SECONDS + timeout_seconds))
  until "$@"; do
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${description}" >&2
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
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${name} health on port ${port}" >&2
      tail -n 120 "${log_file}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

postgres_exec() {
  compose_exec postgres env PGPASSWORD="${DB_PASSWORD}" "$@"
}

psql_exec() {
  compose_exec postgres env PGPASSWORD="${DB_PASSWORD}" \
    psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 "$@"
}

ensure_database() {
  if [[ ! "${DB_NAME}" =~ ^[A-Za-z0-9_]+$ ]]; then
    echo "DB_NAME must contain only letters, numbers, and underscore: ${DB_NAME}" >&2
    exit 1
  fi
  local exists
  exists="$(postgres_exec psql -U "${DB_USER}" -d postgres -At \
    -c "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'")"
  if [[ "${exists}" != "1" ]]; then
    postgres_exec createdb -U "${DB_USER}" "${DB_NAME}"
  fi
}

query_value() {
  local sql="$1"
  psql_exec -At -c "${sql}"
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

reset_database() {
  psql_exec <<'SQL' >/dev/null
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
SQL
  psql_exec -f - < "${ROOT_DIR}/init.sql" >/dev/null
}

seed_full_stack_products() {
  psql_exec <<SQL >/dev/null
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
    impact_notional_units, min_valid_index_sources, status, effective_time, created_at, updated_at
) VALUES
('${COIN_SYMBOL}', 1, 'PERPETUAL', 'INVERSE_PERPETUAL', 'BTC', 'USD', 'BTC',
 1000000, 'USD', ${BTC_TICK_UNITS}, 1, 1, 100000, 1, 1000000000000, 10000000000, 1, 0,
 'LIMIT,MARKET', 'GTC,IOC,FOK,GTX', TRUE, TRUE, TRUE,
 100000000, 10000, 5000, 200, 500, 1000000000000, 300000, 100000000000, 8, 100, 3000, -3000,
 1000000000000, 1, 'TRADING', now(), now(), now()),
('${SPOT_SYMBOL}', 1, 'SPOT', 'SPOT', 'BTC', 'USDT', 'USDT',
 1000000, 'USDT', ${BTC_TICK_UNITS}, 100000, 1, 100000, 500000000, 1000000000000000,
 10000, 1, 3, 'LIMIT', 'GTC,IOC,FOK,GTX', TRUE, FALSE, FALSE,
 1000000, 1000000, 1000000, 200, 500, 1000000000000000, 0, 1, 8, 0, 0, 0,
 1000000000000, 1, 'TRADING', now(), now(), now())
ON CONFLICT (symbol, version) DO UPDATE SET
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
    price_precision = EXCLUDED.price_precision,
    quantity_precision = EXCLUDED.quantity_precision,
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
    status = EXCLUDED.status,
    effective_time = EXCLUDED.effective_time,
    updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_current_versions (symbol, version, updated_at)
VALUES ('${COIN_SYMBOL}', 1, now()), ('${SPOT_SYMBOL}', 1, now())
ON CONFLICT (symbol) DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_symbol_sequences (symbol, version, updated_at)
VALUES ('${COIN_SYMBOL}', 1, now()), ('${SPOT_SYMBOL}', 1, now())
ON CONFLICT (symbol) DO UPDATE SET
    version = GREATEST(instrument_symbol_sequences.version, EXCLUDED.version),
    updated_at = EXCLUDED.updated_at;

INSERT INTO instrument_risk_brackets (
    symbol, version, bracket_no, notional_floor_units, notional_cap_units,
    max_leverage_ppm, initial_margin_rate_ppm, maintenance_margin_rate_ppm
) VALUES
('${COIN_SYMBOL}', 1, 1, 0, 1000000000000, 100000000, 10000, 5000)
ON CONFLICT (symbol, version, bracket_no) DO UPDATE SET
    notional_floor_units = EXCLUDED.notional_floor_units,
    notional_cap_units = EXCLUDED.notional_cap_units,
    max_leverage_ppm = EXCLUDED.max_leverage_ppm,
    initial_margin_rate_ppm = EXCLUDED.initial_margin_rate_ppm,
    maintenance_margin_rate_ppm = EXCLUDED.maintenance_margin_rate_ppm;
SQL
}

delete_surprising_topics() {
  if [[ "${INFRA_MODE}" == "docker" ]]; then
    docker rm -f surprising-ex-kafka >/dev/null 2>&1 || true
    docker volume rm surprising-ex-kafka >/dev/null 2>&1 || true
    start_direct_kafka
    wait_until "Kafka broker" 90 compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null
    return
  fi
  local topics
  topics="$(compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list | awk '/^surprising[.-]/ {print}')"
  if [[ -z "${topics}" ]]; then
    return
  fi
  while IFS= read -r topic; do
    [[ -z "${topic}" ]] && continue
    compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --delete --if-exists --topic "${topic}" >/dev/null 2>&1 || true
  done <<<"${topics}"
  local deadline=$((SECONDS + 60))
  while true; do
    topics="$(compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list | awk '/^surprising[.-]/ {print}')"
    if [[ -z "${topics}" ]]; then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for Kafka topics to be deleted:" >&2
      echo "${topics}" >&2
      exit 1
    fi
    sleep 1
  done
}

create_topic_wrapper() {
  mkdir -p "${TMP_DIR}/bin"
  cat > "${TMP_DIR}/bin/kafka-topics.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${INFRA_MODE:-}" == "docker" ]]; then
  exec docker exec -i surprising-ex-kafka /opt/kafka/bin/kafka-topics.sh "$@"
fi
if [[ -n "${COMPOSE_SUBCOMMAND:-}" ]]; then
  exec "${COMPOSE_BIN}" "${COMPOSE_SUBCOMMAND}" -f "${COMPOSE_FILE}" exec -T kafka kafka-topics.sh "$@"
fi
exec "${COMPOSE_BIN}" -f "${COMPOSE_FILE}" exec -T kafka kafka-topics.sh "$@"
EOF
  chmod +x "${TMP_DIR}/bin/kafka-topics.sh"
}

create_topics() {
  export COMPOSE_FILE COMPOSE_BIN COMPOSE_SUBCOMMAND INFRA_MODE
  create_topic_wrapper
  PATH="${TMP_DIR}/bin:${PATH}" "${ROOT_DIR}/scripts/create-topics.sh" >/dev/null
}

clean_local_state() {
  rm -rf "${ROOT_DIR}/data/kafka-streams"
}

package_services() {
  local module_selectors
  case "${BUILD_SERVICES}" in
    true)
      module_selectors="$(all_provider_maven_selectors)"
      ;;
    false)
      echo "Skipping provider package build (BUILD_SERVICES=false)"
      return
      ;;
    auto)
      module_selectors="$(stale_provider_maven_selectors)"
      if [[ -z "${module_selectors}" ]]; then
        echo "Provider jars are current; skipping Maven package (BUILD_SERVICES=auto)"
        return
      fi
      echo "Packaging stale provider modules: ${module_selectors}"
      ;;
    *)
      echo "Unsupported BUILD_SERVICES=${BUILD_SERVICES}; expected auto, true, or false" >&2
      exit 1
      ;;
  esac

  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
    mvn -q -pl "${module_selectors}" -am -DskipTests package
}

boot_jar_or_empty() {
  local module_path="$1"
  local artifact="$2"
  find "${ROOT_DIR}/${module_path}/target" -name "${artifact}-*-exec.jar" -type f | sort | tail -n 1
}

join_by_comma() {
  local IFS=,
  echo "$*"
}

all_provider_maven_selectors() {
  local selectors=()
  local provider
  for provider in "${SMOKE_PROVIDERS[@]}"; do
    selectors+=(":$(provider_artifact "${provider}")")
  done
  join_by_comma "${selectors[@]}"
}

provider_own_build_input_newer_than() {
  local jar="$1"
  local module_path="$2"
  local parent_path="${module_path%/*}"
  if [[ "${ROOT_DIR}/${parent_path}/pom.xml" -nt "${jar}" ]]; then
    echo "${ROOT_DIR}/${parent_path}/pom.xml"
    return
  fi
  find "${ROOT_DIR}/${module_path}" \
    \( -path "*/target" -o -path "*/node_modules" \) -prune -o \
    \( -path "${ROOT_DIR}/${module_path}/src/main/*" -o -path "${ROOT_DIR}/${module_path}/pom.xml" \) \
    -type f -newer "${jar}" -print -quit
}

shared_build_input_newer_than() {
  local jar="$1"
  local shared_pom
  for shared_pom in \
    "${ROOT_DIR}/pom.xml" \
    "${ROOT_DIR}/surprising-parent/pom.xml" \
    "${ROOT_DIR}/surprising-dependencies/pom.xml"; do
    if [[ "${shared_pom}" -nt "${jar}" ]]; then
      echo "${shared_pom}"
      return 0
    fi
  done
  find "${ROOT_DIR}" \
    \( -path "${ROOT_DIR}/.git" -o -path "${ROOT_DIR}/data" -o -path "*/target" -o -path "*/node_modules" \) -prune -o \
    \( -path "*/surprising-*-api/src/main/*" -o -path "*/surprising-*-api/pom.xml" \) \
    -type f -newer "${jar}" -print -quit
}

stale_provider_maven_selectors() {
  local selectors=()
  local provider
  for provider in "${SMOKE_PROVIDERS[@]}"; do
    local module_path
    local artifact
    local jar
    module_path="$(provider_module "${provider}")"
    artifact="$(provider_artifact "${provider}")"
    jar="$(boot_jar_or_empty "${module_path}" "${artifact}")"
    if [[ -z "${jar}" ]]; then
      echo "Provider jar missing for ${artifact}; packaging ${artifact}" >&2
      selectors+=(":${artifact}")
      continue
    fi

    local shared_input
    shared_input="$(shared_build_input_newer_than "${jar}")"
    if [[ -n "${shared_input}" ]]; then
      echo "Shared build input ${shared_input#${ROOT_DIR}/} is newer than ${artifact}; packaging all providers" >&2
      all_provider_maven_selectors
      return
    fi

    local provider_input
    provider_input="$(provider_own_build_input_newer_than "${jar}" "${module_path}")"
    if [[ -n "${provider_input}" ]]; then
      echo "Build input ${provider_input#${ROOT_DIR}/} is newer than ${artifact}; packaging ${artifact}" >&2
      selectors+=(":${artifact}")
    fi
  done
  if ((${#selectors[@]})); then
    join_by_comma "${selectors[@]}"
  fi
}

boot_jar() {
  local module_path="$1"
  local artifact="$2"
  local jar
  jar="$(boot_jar_or_empty "${module_path}" "${artifact}")"
  if [[ -z "${jar}" ]]; then
    echo "Boot jar not found for ${artifact}; run with BUILD_SERVICES=true" >&2
    exit 1
  fi
  echo "${jar}"
}

provider_port() {
  local name="$1"
  case "${name}" in
    instrument) echo 9080 ;;
    candlestick) echo 9081 ;;
    index-price) echo 9082 ;;
    mark-price) echo 9083 ;;
    order) echo 9084 ;;
    matching) echo 9085 ;;
    account) echo 9086 ;;
    risk) echo 9087 ;;
    liquidation) echo 9088 ;;
    funding) echo 9089 ;;
    insurance) echo 9090 ;;
    adl) echo 9091 ;;
    websocket) echo 9093 ;;
    gateway) echo 9094 ;;
    trigger) echo 9095 ;;
    market-maker) echo 9096 ;;
    *) echo "unknown provider: ${name}" >&2; exit 1 ;;
  esac
}

provider_module() {
  local name="$1"
  case "${name}" in
    instrument) echo "surprising-instrument/surprising-instrument-provider" ;;
    candlestick) echo "surprising-candlestick/surprising-candlestick-provider" ;;
    index-price) echo "surprising-price/surprising-index-price-provider" ;;
    mark-price) echo "surprising-price/surprising-mark-price-provider" ;;
    order) echo "surprising-trading/surprising-order-provider" ;;
    matching) echo "surprising-trading/surprising-matching-provider" ;;
    trigger) echo "surprising-trading/surprising-trigger-provider" ;;
    account) echo "surprising-account/surprising-account-provider" ;;
    risk) echo "surprising-risk/surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation/surprising-liquidation-provider" ;;
    funding) echo "surprising-funding/surprising-funding-provider" ;;
    insurance) echo "surprising-insurance/surprising-insurance-provider" ;;
    adl) echo "surprising-adl/surprising-adl-provider" ;;
    websocket) echo "surprising-websocket/surprising-websocket-provider" ;;
    gateway) echo "surprising-gateway/surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker/surprising-market-maker-provider" ;;
    *) echo "unknown provider: ${name}" >&2; exit 1 ;;
  esac
}

provider_artifact() {
  local name="$1"
  case "${name}" in
    instrument) echo "surprising-instrument-provider" ;;
    candlestick) echo "surprising-candlestick-provider" ;;
    index-price) echo "surprising-index-price-provider" ;;
    mark-price) echo "surprising-mark-price-provider" ;;
    order) echo "surprising-order-provider" ;;
    matching) echo "surprising-matching-provider" ;;
    trigger) echo "surprising-trigger-provider" ;;
    account) echo "surprising-account-provider" ;;
    risk) echo "surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation-provider" ;;
    funding) echo "surprising-funding-provider" ;;
    insurance) echo "surprising-insurance-provider" ;;
    adl) echo "surprising-adl-provider" ;;
    websocket) echo "surprising-websocket-provider" ;;
    gateway) echo "surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker-provider" ;;
    *) echo "unknown provider: ${name}" >&2; exit 1 ;;
  esac
}

set_provider_pid() {
  local name="$1"
  local pid="$2"
  local index
  for index in "${!PROVIDER_NAMES[@]}"; do
    if [[ "${PROVIDER_NAMES[$index]}" == "${name}" ]]; then
      PROVIDER_PIDS[$index]="${pid}"
      return
    fi
  done
  PROVIDER_NAMES+=("${name}")
  PROVIDER_PIDS+=("${pid}")
}

get_provider_pid() {
  local name="$1"
  local index
  for index in "${!PROVIDER_NAMES[@]}"; do
    if [[ "${PROVIDER_NAMES[$index]}" == "${name}" ]]; then
      echo "${PROVIDER_PIDS[$index]}"
      return
    fi
  done
}

unset_provider_pid() {
  local name="$1"
  local index
  local new_names=()
  local new_pids=()
  for index in "${!PROVIDER_NAMES[@]}"; do
    if [[ "${PROVIDER_NAMES[$index]}" != "${name}" ]]; then
      new_names+=("${PROVIDER_NAMES[$index]}")
      new_pids+=("${PROVIDER_PIDS[$index]}")
    fi
  done
  PROVIDER_NAMES=("${new_names[@]}")
  PROVIDER_PIDS=("${new_pids[@]}")
}

register_provider() {
  :
}

start_provider() {
  local name="$1"
  local port
  local module_path
  local artifact
  port="$(provider_port "${name}")"
  module_path="$(provider_module "${name}")"
  artifact="$(provider_artifact "${name}")"
  local jar
  jar="$(boot_jar "${module_path}" "${artifact}")"
  local log_file="${TMP_DIR}/${name}.log"
  local java_args=()
  local app_args=()
  if [[ "${name}" == "matching" ]]; then
    java_args+=(
      "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
      "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED"
      "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
      "--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED"
      "--add-opens=java.base/java.lang=ALL-UNNAMED"
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
      "--add-opens=java.base/java.io=ALL-UNNAMED"
      "--add-opens=java.base/java.util=ALL-UNNAMED"
      "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    )
  fi
  if [[ "${name}" == "index-price" && "${FULL_STACK_EXTERNAL_INDEX_WS_ENABLED}" != "true" ]]; then
    app_args+=("--surprising.price.index.web-socket.enabled=false")
  fi
  if [[ "${name}" == "mark-price" && "${FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP}" != "true" ]]; then
    app_args+=("--spring.kafka.listener.auto-startup=false")
  fi
  if [[ "${name}" == "market-maker" ]]; then
    app_args+=("--surprising.market-maker.engine.enabled=false")
    app_args+=("--surprising.market-maker.reference-market.enabled=${MM_REFERENCE_MARKET_ENABLED}")
    app_args+=("--surprising.market-maker.reference-market.websocket-enabled=${MM_REFERENCE_MARKET_WEBSOCKET_ENABLED}")
  fi
  echo "Starting ${name} provider on port ${port}"
  (
    cd "${ROOT_DIR}"
    local command=(java)
    if (( ${#java_args[@]} > 0 )); then
      command+=("${java_args[@]}")
    fi
    command+=("-jar" "${jar}")
    if (( ${#app_args[@]} > 0 )); then
      command+=("${app_args[@]}")
    fi
    exec env \
      "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}" \
      "SPRING_DATASOURCE_USERNAME=${DB_USER}" \
      "SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}" \
      "${command[@]}"
  ) >"${log_file}" 2>&1 &
  local pid="$!"
  set_provider_pid "${name}" "${pid}"
  wait_http "${name}" "${port}"
}

stop_provider() {
  local name="$1"
  local pid
  local port
  pid="$(get_provider_pid "${name}")"
  if [[ -z "${pid}" ]]; then
    return
  fi
  port="$(provider_port "${name}")"
  echo "Stopping ${name} provider"
  kill "${pid}" >/dev/null 2>&1 || true
  local deadline=$((SECONDS + 30))
  while kill -0 "${pid}" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      kill -9 "${pid}" >/dev/null 2>&1 || true
      break
    fi
    sleep 1
  done
  wait "${pid}" >/dev/null 2>&1 || true
  deadline=$((SECONDS + 30))
  while lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${name} port ${port} to close" >&2
      exit 1
    fi
    sleep 1
  done
  unset_provider_pid "${name}"
}

json_field() {
  local field="$1"
  python3 -c "import json,sys; print(json.load(sys.stdin)['${field}'])"
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
  local price
  local bid
  local ask
  local units
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
) ON CONFLICT (symbol, sequence) DO NOTHING;

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
) ON CONFLICT (symbol, sequence) DO NOTHING;
SQL
}

seed_prices() {
  insert_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" 1001 "${BTC_PRICE_TICKS}"
  insert_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 1001 "${ETH_PRICE_TICKS}"
  insert_mark_price "${COIN_SYMBOL}" "${BTC_TICK_UNITS}" 1001 "${COIN_PRICE_TICKS}"
  insert_mark_price "${SPOT_SYMBOL}" "${BTC_TICK_UNITS}" 1001 "${SPOT_PRICE_TICKS}"
}

refresh_mark_price() {
  local symbol="$1"
  local tick_units="$2"
  local price_ticks="$3"
  NEXT_MARK_SEQUENCE=$((NEXT_MARK_SEQUENCE + 1))
  insert_mark_price "${symbol}" "${tick_units}" "${NEXT_MARK_SEQUENCE}" "${price_ticks}"
  wait_sql_nonzero "fresh mark price ${symbol}" \
    "SELECT count(*) FROM price_mark_ticks WHERE symbol = '${symbol}' AND event_time >= now() - interval '5 seconds'"
}

wait_latest_mark_price_between() {
  local symbol="$1"
  local tick_units="$2"
  local min_ticks="$3"
  local max_ticks="$4"
  wait_sql_equals "latest mark price ${symbol} between ${min_ticks} and ${max_ticks}" \
    "SELECT CASE WHEN COALESCE((SELECT ((mark_price_units + ${tick_units} / 2) / ${tick_units}) FROM price_mark_ticks WHERE symbol = '${symbol}' ORDER BY event_time DESC, sequence DESC LIMIT 1), 0) BETWEEN ${min_ticks} AND ${max_ticks} THEN 1 ELSE 0 END" \
    "1"
}

latest_mark_price_ticks() {
  local symbol="$1"
  local tick_units="$2"
  query_value "SELECT COALESCE((SELECT ((mark_price_units + ${tick_units} / 2) / ${tick_units}) FROM price_mark_ticks WHERE symbol = '${symbol}' ORDER BY event_time DESC, sequence DESC LIMIT 1), 0)"
}

publish_mark_price_event() {
  local symbol="$1"
  local tick_units="$2"
  local price_ticks="$3"
  local price
  local bid
  local ask
  local event_time
  local next_funding_time
  NEXT_MARK_SEQUENCE=$((NEXT_MARK_SEQUENCE + 1))
  insert_mark_price "${symbol}" "${tick_units}" "${NEXT_MARK_SEQUENCE}" "${price_ticks}"
  price="$(decimal_price "${price_ticks}" "${tick_units}")"
  bid="$(decimal_price "$((price_ticks - 1))" "${tick_units}")"
  ask="$(decimal_price "$((price_ticks + 1))" "${tick_units}")"
  event_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  next_funding_time="$(python3 - <<'PY'
from datetime import datetime, timedelta, timezone

print((datetime.now(timezone.utc) + timedelta(hours=8)).strftime("%Y-%m-%dT%H:%M:%SZ"))
PY
)"
  produce_json "surprising.perp.mark.price.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"markPrice\":${price},\"indexPrice\":${price},\"price1\":${price},\"price2\":${price},\"lastTradePrice\":${price},\"bestBidPrice\":${bid},\"bestAskPrice\":${ask},\"fundingRate\":0.000000000000000000,\"nextFundingTime\":\"${next_funding_time}\",\"timeUntilFundingSeconds\":28800,\"basisAverage\":0.000000000000000000,\"basisWindowSeconds\":60,\"clampLow\":${price},\"clampHigh\":${price},\"sequence\":${NEXT_MARK_SEQUENCE},\"status\":\"HEALTHY\",\"eventTime\":\"${event_time}\"}"
  wait_sql_nonzero "persisted mark price event ${symbol}" \
    "SELECT count(*) FROM price_mark_ticks WHERE symbol = '${symbol}' AND sequence = ${NEXT_MARK_SEQUENCE}"
}

expire_mark_price() {
  local symbol="$1"
  psql_exec <<SQL >/dev/null
UPDATE price_mark_ticks
   SET event_time = now() - interval '10 seconds'
 WHERE symbol = '${symbol}';
SQL
}

produce_json() {
  local topic="$1"
  local key="$2"
  local payload="$3"
  printf '%s:%s\n' "${key}" "${payload}" | compose_exec kafka kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic "${topic}" \
    --property parse.key=true \
    --property key.separator=: >/dev/null
}

publish_price_inputs() {
  local symbol="$1"
  local tick_units="$2"
  local sequence="$3"
  local price_ticks="$4"
  local price
  local bid
  local ask
  local event_time
  price="$(decimal_price "${price_ticks}" "${tick_units}")"
  bid="$(decimal_price "$((price_ticks - 1))" "${tick_units}")"
  ask="$(decimal_price "$((price_ticks + 1))" "${tick_units}")"
  event_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  local pids=()
  produce_json "surprising.perp.index.price.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"indexPrice\":${price},\"sequence\":${sequence},\"status\":\"HEALTHY\",\"componentCount\":5,\"validComponentCount\":5,\"totalConfiguredWeight\":5.0,\"eventTime\":\"${event_time}\",\"components\":[]}" &
  pids+=("$!")
  produce_json "surprising.perp.book.ticker.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"bestBidPrice\":${bid},\"bestAskPrice\":${ask},\"sequence\":${sequence},\"eventTime\":\"${event_time}\"}" &
  pids+=("$!")
  produce_json "surprising.perp.trade.events.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"tradeId\":\"price-input-${RUN_ID}-${sequence}\",\"sequence\":${sequence},\"tradeTime\":\"${event_time}\",\"price\":${price},\"quantity\":1.0,\"side\":\"BUY\"}" &
  pids+=("$!")
  local pid
  for pid in "${pids[@]}"; do
    wait "${pid}"
  done
}

publish_insufficient_index_inputs() {
  local symbol="$1"
  local tick_units="$2"
  local sequence="$3"
  local price_ticks="$4"
  local price
  local bid
  local ask
  local event_time
  price="$(decimal_price "${price_ticks}" "${tick_units}")"
  bid="$(decimal_price "$((price_ticks - 1))" "${tick_units}")"
  ask="$(decimal_price "$((price_ticks + 1))" "${tick_units}")"
  event_time="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  local pids=()
  produce_json "surprising.perp.index.price.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"indexPrice\":null,\"sequence\":${sequence},\"status\":\"INSUFFICIENT_SOURCES\",\"componentCount\":5,\"validComponentCount\":1,\"totalConfiguredWeight\":5.0,\"eventTime\":\"${event_time}\",\"components\":[]}" &
  pids+=("$!")
  produce_json "surprising.perp.book.ticker.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"bestBidPrice\":${bid},\"bestAskPrice\":${ask},\"sequence\":${sequence},\"eventTime\":\"${event_time}\"}" &
  pids+=("$!")
  produce_json "surprising.perp.trade.events.v1" "${symbol}" \
    "{\"symbol\":\"${symbol}\",\"tradeId\":\"insufficient-index-${RUN_ID}-${sequence}\",\"sequence\":${sequence},\"tradeTime\":\"${event_time}\",\"price\":${price},\"quantity\":1.0,\"side\":\"BUY\"}" &
  pids+=("$!")
  local pid
  for pid in "${pids[@]}"; do
    wait "${pid}"
  done
}

gateway_post() {
  local service_path="$1"
  local user_id="$2"
  local trace_id="$3"
  local payload="$4"
  curl -fsS -X POST "http://localhost:9094/api/v1/gateway/${service_path}" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: ${trace_id}" \
    -d "${payload}"
}

gateway_get() {
  local service_path="$1"
  local user_id="$2"
  local trace_id="$3"
  curl -fsS "http://localhost:9094/api/v1/gateway/${service_path}" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: ${trace_id}"
}

update_risk_runtime_config() {
  local warning_margin_ratio_ppm="$1"
  local liquidation_margin_ratio_ppm="$2"
  curl -fsS -X POST "http://localhost:9087/api/v1/risk/admin/runtime-config" \
    -H "Content-Type: application/json" \
    -H "X-Admin-User-Id: full-stack-smoke" \
    -d "{
      \"warningMarginRatioPpm\": ${warning_margin_ratio_ppm},
      \"liquidationMarginRatioPpm\": ${liquidation_margin_ratio_ppm}
    }" >/dev/null
}

update_liquidation_runtime_config() {
  local full_close_margin_ratio_ppm="$1"
  curl -fsS -X POST "http://localhost:9088/api/v1/liquidations/admin/runtime-config" \
    -H "Content-Type: application/json" \
    -H "X-Admin-User-Id: full-stack-smoke" \
    -d "{
      \"fullCloseMarginRatioPpm\": ${full_close_margin_ratio_ppm}
    }" >/dev/null
}

place_order_symbol() {
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
  local position_side="${12:-NET}"
  local response
  response="$(gateway_post "trading" "${user_id}" "real-config-${RUN_ID}-${client_order_id}" "{
      \"userId\": ${user_id},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${symbol}\",
      \"side\": \"${side}\",
      \"orderType\": \"${order_type}\",
      \"timeInForce\": \"${tif}\",
      \"priceTicks\": ${price_ticks},
      \"quantitySteps\": ${quantity_steps},
      \"marginMode\": \"${margin_mode}\",
      \"positionSide\": \"${position_side}\",
      \"reduceOnly\": ${reduce_only},
      \"postOnly\": ${post_only}
    }")"
  printf '%s\n' "${response}" | json_field orderId
}

place_order() {
  place_order_symbol "${BTC_SYMBOL}" "$@"
}

cancel_order() {
  local user_id="$1"
  local order_id="$2"
  gateway_post "trading/cancel" "${user_id}" "real-config-${RUN_ID}-cancel-${order_id}" \
    "{\"userId\": ${user_id}, \"orderId\": ${order_id}}" >/dev/null
}

place_trigger_order() {
  local user_id="$1"
  local client_trigger_order_id="$2"
  local side="$3"
  local trigger_type="$4"
  local trigger_price_ticks="$5"
  local order_type="$6"
  local tif="$7"
  local price_ticks="$8"
  local quantity_steps="$9"
  local oco_group_id="${10:-}"
  local position_side="${11:-NET}"
  local response
  response="$(gateway_post "trading-trigger" "${user_id}" "real-config-${RUN_ID}-${client_trigger_order_id}" "{
      \"userId\": ${user_id},
      \"clientTriggerOrderId\": \"${client_trigger_order_id}\",
      \"ocoGroupId\": \"${oco_group_id}\",
      \"symbol\": \"${BTC_SYMBOL}\",
      \"side\": \"${side}\",
      \"triggerType\": \"${trigger_type}\",
      \"triggerPriceType\": \"MARK_PRICE\",
      \"triggerPriceTicks\": ${trigger_price_ticks},
      \"orderType\": \"${order_type}\",
      \"timeInForce\": \"${tif}\",
      \"priceTicks\": ${price_ticks},
      \"quantitySteps\": ${quantity_steps},
      \"marginMode\": \"CROSS\",
      \"positionSide\": \"${position_side}\"
    }")"
  printf '%s\n' "${response}" | json_field triggerOrderId
}

cancel_trigger_order() {
  local user_id="$1"
  local trigger_order_id="$2"
  gateway_post "trading-trigger/cancel" "${user_id}" "real-config-${RUN_ID}-cancel-trigger-${trigger_order_id}" \
    "{\"userId\": ${user_id}, \"triggerOrderId\": ${trigger_order_id}}" >/dev/null
}

position_mode() {
  local user_id="$1"
  gateway_get "account/position-mode?userId=${user_id}" "${user_id}" "real-config-${RUN_ID}-position-mode-${user_id}" \
    | json_field positionMode
}

expect_position_mode() {
  local user_id="$1"
  local expected="$2"
  local actual
  actual="$(position_mode "${user_id}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "Expected position mode ${expected} for user ${user_id}, got ${actual}" >&2
    exit 1
  fi
}

update_position_mode() {
  local user_id="$1"
  local position_mode="$2"
  local response
  response="$(gateway_post "account/position-mode" "${user_id}" "real-config-${RUN_ID}-position-mode-${user_id}-${position_mode}" "{
      \"userId\": ${user_id},
      \"positionMode\": \"${position_mode}\"
    }")"
  printf '%s\n' "${response}" | json_field positionMode
}

expect_position_mode_update_status() {
  local user_id="$1"
  local position_mode="$2"
  local expected_status="$3"
  local label="$4"
  local response_file="${TMP_DIR}/position-mode-${label}-${user_id}.json"
  local code
  code="$(curl -sS -o "${response_file}" -w '%{http_code}' \
    -X POST "http://localhost:9094/api/v1/gateway/account/position-mode" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: real-config-${RUN_ID}-position-mode-${label}" \
    -d "{
      \"userId\": ${user_id},
      \"positionMode\": \"${position_mode}\"
    }")"
  if [[ "${code}" != "${expected_status}" ]]; then
    echo "Expected position mode update status ${expected_status}, got ${code} for ${label}" >&2
    cat "${response_file}" >&2 || true
    exit 1
  fi
}

expect_order_position_mode_rejected() {
  local user_id="$1"
  local client_order_id="$2"
  local side="$3"
  local position_side="$4"
  local expected_message="$5"
  local response_file="${TMP_DIR}/order-position-mode-${client_order_id}.json"
  local code
  code="$(curl -sS -o "${response_file}" -w '%{http_code}' \
    -X POST "http://localhost:9094/api/v1/gateway/trading" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: real-config-${RUN_ID}-${client_order_id}" \
    -d "{
      \"userId\": ${user_id},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${BTC_SYMBOL}\",
      \"side\": \"${side}\",
      \"orderType\": \"LIMIT\",
      \"timeInForce\": \"GTC\",
      \"priceTicks\": ${POSITION_MODE_BLOCK_PRICE_TICKS},
      \"quantitySteps\": 1,
      \"marginMode\": \"CROSS\",
      \"positionSide\": \"${position_side}\",
      \"reduceOnly\": false,
      \"postOnly\": false
    }")"
  if [[ "${code}" != "400" ]]; then
    echo "Expected order rejection 400 for ${client_order_id}, got ${code}" >&2
    cat "${response_file}" >&2 || true
    exit 1
  fi
  wait_sql_equals "${expected_message} did not persist order ${client_order_id}" \
    "SELECT count(*) FROM trading_orders WHERE user_id = ${user_id} AND client_order_id = '${client_order_id}'" \
    "0"
}

adjust_balance() {
  local user_id="$1"
  local amount_units="$2"
  local reference_id="$3"
  gateway_post "account/admin/balance-adjustments" 1 "real-config-${RUN_ID}-${reference_id}" "{
      \"userId\": ${user_id},
      \"asset\": \"USDT\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"FULL_STACK_REAL_CONFIG_TEST\"
    }" >/dev/null
}

adjust_product_balance() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  local amount_units="$4"
  local reference_id="$5"
  gateway_post "account/admin/product-balance-adjustments" 1 "real-config-${RUN_ID}-${reference_id}" "{
      \"userId\": ${user_id},
      \"accountType\": \"${account_type}\",
      \"asset\": \"${asset}\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"FULL_STACK_REAL_CONFIG_TEST\"
    }" >/dev/null
}

adjust_position_margin() {
  local user_id="$1"
  local symbol="$2"
  local amount_units="$3"
  local reference_id="$4"
  gateway_post "account/position-margin-adjustments" "${user_id}" "real-config-${RUN_ID}-${reference_id}" "{
      \"userId\": ${user_id},
      \"symbol\": \"${symbol}\",
      \"marginMode\": \"ISOLATED\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"FULL_STACK_REAL_CONFIG_TEST\"
    }"
}

expect_position_margin_adjustment_rejected() {
  local user_id="$1"
  local symbol="$2"
  local amount_units="$3"
  local reference_id="$4"
  local expected_code="$5"
  local response_file="${TMP_DIR}/position-margin-reject-${reference_id}.json"
  local code
  code="$(curl -sS -o "${response_file}" -w '%{http_code}' \
    -X POST "http://localhost:9094/api/v1/gateway/account/position-margin-adjustments" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: real-config-${RUN_ID}-${reference_id}" \
    -d "{
      \"userId\": ${user_id},
      \"symbol\": \"${symbol}\",
      \"marginMode\": \"ISOLATED\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"FULL_STACK_REAL_CONFIG_TEST\"
    }")"
  if [[ "${code}" != "${expected_code}" ]]; then
    echo "Expected position-margin adjustment ${reference_id} to return HTTP ${expected_code}, got ${code}" >&2
    cat "${response_file}" >&2 || true
    exit 1
  fi
}

adjust_insurance_fund() {
  local amount_units="$1"
  local reference_id="$2"
  curl -fsS -X POST "http://localhost:9090/api/v1/insurance/admin/fund-adjustments" \
    -H "Content-Type: application/json" \
    -H "X-Admin-User-Id: full-stack-smoke" \
    -H "X-Trace-Id: real-config-${RUN_ID}-${reference_id}" \
    -d "{
      \"asset\": \"USDT\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"FULL_STACK_REAL_CONFIG_TEST\"
    }" >/dev/null
}

run_index_source_fail_closed_flow() {
  if [[ "${FULL_STACK_MARK_PRICE_LISTENER_AUTO_STARTUP}" != "true" ]]; then
    echo "Skipping index-source fail-closed scenario because mark-price Kafka listener auto-startup is disabled"
    return
  fi
  if [[ "${START_PROVIDERS}" != "true" ]]; then
    echo "Skipping index-source fail-closed scenario because reused providers cannot isolate index-price publishing"
    return
  fi
  echo "Scenario: insufficient index source stops mark refresh and order entry fails closed"
  echo "Pausing index-price provider to isolate the insufficient-source input"
  stop_provider "index-price"
  local insufficient_sequence=$((NEXT_MARK_SEQUENCE + 500000))
  publish_insufficient_index_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${insufficient_sequence}" "${BTC_PRICE_TICKS}"
  sleep 5
  expire_mark_price "${BTC_SYMBOL}"
  sleep 3
  local fresh_marks
  fresh_marks="$(query_value "SELECT count(*) FROM price_mark_ticks WHERE symbol = '${BTC_SYMBOL}' AND event_time >= now() - interval '2 seconds'")"
  if [[ "${fresh_marks}" != "0" ]]; then
    echo "Expected insufficient index source to stop fresh mark publication, got ${fresh_marks} fresh marks" >&2
    query_value "SELECT sequence || '|' || status || '|' || event_time FROM price_mark_ticks WHERE symbol = '${BTC_SYMBOL}' ORDER BY event_time DESC, sequence DESC LIMIT 5" >&2 || true
    exit 1
  fi
  local index_unavailable_order
  index_unavailable_order="$(place_order "${FULL_TAKER_USER}" "real-index-unavailable-market-${RUN_ID}" "BUY" "MARKET" "IOC" 0 1 false false)"
  wait_order_state "${index_unavailable_order}" "REJECTED" "0" "0"
  wait_sql_equals "index unavailable market reject reason" \
    "SELECT reject_reason FROM trading_orders WHERE order_id = ${index_unavailable_order}" \
    "mark price unavailable"

  local recover_sequence=$((insufficient_sequence + 1))
  local recover_started_at
  recover_started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  publish_price_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${recover_sequence}" "${BTC_PRICE_TICKS}"
  wait_sql_nonzero "mark price recovered after healthy index source" \
    "SELECT count(*) FROM price_mark_ticks WHERE symbol = '${BTC_SYMBOL}' AND status IN ('HEALTHY', 'DEGRADED', 'CLAMPED') AND event_time >= '${recover_started_at}'::timestamptz"
  start_provider "index-price"
}

run_market_maker_provider_smoke() {
  local trace_id="real-config-${RUN_ID}-mm-provider"
  if [[ "${RESET_STATE}" != "true" ]]; then
    echo "Skipping market-maker provider run-once smoke because RESET_STATE=false"
    return
  fi
  echo "Scenario: market-maker provider run-once places post-only quotes"
  refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
  wait_latest_mark_price_between "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "$((BTC_PRICE_TICKS * 95 / 100))" "$((BTC_PRICE_TICKS * 105 / 100))"
  gateway_post "market-maker/run-once" 1 "${trace_id}" "{
      \"strategyId\": \"${MM_PROVIDER_STRATEGY_ID}\",
      \"symbol\": \"${BTC_SYMBOL}\"
    }" >"${TMP_DIR}/market-maker-run-once.json"
  local mm_order_count
  mm_order_count="$(query_value "SELECT count(DISTINCT o.order_id) FROM trading_orders o JOIN trading_order_events e ON e.order_id = o.order_id WHERE e.trace_id = '${trace_id}' AND o.user_id IN (${MM_PROVIDER_USER_A}, ${MM_PROVIDER_USER_B}) AND o.symbol = '${BTC_SYMBOL}' AND o.client_order_id LIKE 'mm-%' AND o.time_in_force = 'GTX' AND o.post_only = true")"
  if [[ ! "${mm_order_count}" =~ ^[0-9]+$ ]] || ((mm_order_count <= 0)); then
    echo "Expected market-maker provider to submit post-only GTX quotes, got ${mm_order_count}" >&2
    cat "${TMP_DIR}/market-maker-run-once.json" >&2 || true
    exit 1
  fi
  wait_sql_equals "market-maker provider trace propagated to order events" \
    "SELECT count(DISTINCT order_id) FROM trading_order_events WHERE trace_id = '${trace_id}' AND order_id IN (SELECT order_id FROM trading_orders WHERE user_id IN (${MM_PROVIDER_USER_A}, ${MM_PROVIDER_USER_B}) AND symbol = '${BTC_SYMBOL}' AND client_order_id LIKE 'mm-%' AND time_in_force = 'GTX' AND post_only = true)" \
    "${mm_order_count}"
  wait_sql_equals "market-maker provider matching accepted trace" \
    "SELECT count(DISTINCT order_id) FROM trading_match_results WHERE trace_id = '${trace_id}' AND result_code = 'SUCCESS' AND order_id IN (SELECT order_id FROM trading_orders WHERE user_id IN (${MM_PROVIDER_USER_A}, ${MM_PROVIDER_USER_B}) AND symbol = '${BTC_SYMBOL}' AND client_order_id LIKE 'mm-%' AND time_in_force = 'GTX' AND post_only = true)" \
    "${mm_order_count}"
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

wait_order_result() {
  local order_id="$1"
  local result_code="$2"
  wait_sql_equals "matching result ${result_code} for order ${order_id}" \
    "SELECT COALESCE((SELECT result_code FROM trading_match_results WHERE order_id = ${order_id} AND command_type = 'PLACE' ORDER BY event_time DESC LIMIT 1), '')" \
    "${result_code}"
}

wait_trigger_state() {
  local trigger_order_id="$1"
  local status="$2"
  wait_sql_equals "trigger order ${trigger_order_id} ${status}" \
    "SELECT status FROM trading_trigger_orders WHERE trigger_order_id = ${trigger_order_id}" \
    "${status}"
}

wait_position_symbol() {
  local symbol="$1"
  local user_id="$2"
  local signed_quantity="$3"
  local entry_price="$4"
  wait_sql_equals "position ${symbol} user=${user_id} quantity=${signed_quantity}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE user_id = ${user_id} AND symbol = '${symbol}' AND position_side = 'NET'), '0:0')" \
    "${signed_quantity}:${entry_price}"
}

wait_position() {
  wait_position_symbol "${BTC_SYMBOL}" "$@"
}

wait_position_symbol_side() {
  local symbol="$1"
  local user_id="$2"
  local position_side="$3"
  local signed_quantity="$4"
  local entry_price="$5"
  wait_sql_equals "position ${symbol} user=${user_id} side=${position_side} quantity=${signed_quantity}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE user_id = ${user_id} AND symbol = '${symbol}' AND position_side = '${position_side}'), '0:0')" \
    "${signed_quantity}:${entry_price}"
}

wait_position_quantity_side() {
  local symbol="$1"
  local user_id="$2"
  local position_side="$3"
  local signed_quantity="$4"
  wait_sql_equals "position quantity ${symbol} user=${user_id} side=${position_side} quantity=${signed_quantity}" \
    "SELECT COALESCE((SELECT signed_quantity_steps FROM account_positions WHERE user_id = ${user_id} AND symbol = '${symbol}' AND position_side = '${position_side}'), 0)" \
    "${signed_quantity}"
}

wait_product_balance() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  local available="$4"
  local locked="$5"
  wait_sql_equals "product balance user=${user_id} type=${account_type} asset=${asset}" \
    "SELECT COALESCE((SELECT available_units || ':' || locked_units FROM account_product_balances WHERE account_type = '${account_type}' AND user_id = ${user_id} AND asset = '${asset}'), '0:0')" \
    "${available}:${locked}"
}

wait_product_locked_zero() {
  local user_id="$1"
  local account_type="$2"
  local asset="$3"
  wait_sql_equals "product locked balance zero user=${user_id} type=${account_type} asset=${asset}" \
    "SELECT COALESCE((SELECT locked_units FROM account_product_balances WHERE account_type = '${account_type}' AND user_id = ${user_id} AND asset = '${asset}'), 0)" \
    "0"
}

wait_position_symbol_margin() {
  local symbol="$1"
  local user_id="$2"
  local margin_mode="$3"
  local signed_quantity="$4"
  local entry_price="$5"
  wait_sql_equals "position ${symbol} user=${user_id} mode=${margin_mode} quantity=${signed_quantity}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE user_id = ${user_id} AND symbol = '${symbol}' AND margin_mode = '${margin_mode}'), '0:0')" \
    "${signed_quantity}:${entry_price}"
}

wait_position_margin_units() {
  local symbol="$1"
  local user_id="$2"
  local expected="$3"
  wait_sql_equals "position margin ${symbol} user=${user_id} expected=${expected}" \
    "SELECT COALESCE((SELECT margin_units FROM account_position_margins WHERE user_id = ${user_id} AND symbol = '${symbol}' AND margin_mode = 'ISOLATED'), 0)" \
    "${expected}"
}

wait_latest_isolated_risk_position_margin() {
  local symbol="$1"
  local user_id="$2"
  local expected="$3"
  wait_sql_equals "latest isolated risk position margin ${symbol} user=${user_id}" \
    "SELECT COALESCE((SELECT position_margin_units FROM risk_position_snapshots WHERE user_id = ${user_id} AND symbol = '${symbol}' AND margin_mode = 'ISOLATED' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1), -1)" \
    "${expected}"
}

wait_consumer_group_lag_zero() {
  local group="$1"
  local topic="$2"
  local output
  local deadline=$((SECONDS + 180))
  while true; do
    output="$(compose_exec kafka kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 \
      --group "${group}" \
      --describe 2>/dev/null || true)"
    if awk -v topic="${topic}" '$2 == topic && $6 ~ /^[0-9]+$/ { lag += $6; found = 1 } END { exit !(found && lag == 0) }' <<<"${output}"; then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for ${group} lag on ${topic}" >&2
      echo "${output}" >&2
      exit 1
    fi
    sleep 1
  done
}

publish_duplicate_match_trade() {
  local payload
  payload="$(query_value "SELECT payload::text FROM trading_outbox_events WHERE aggregate_type = 'MATCH_TRADE' AND event_key = '${BTC_SYMBOL}' ORDER BY created_at DESC LIMIT 1")"
  if [[ -z "${payload}" ]]; then
    echo "Could not find MATCH_TRADE outbox payload for duplicate replay" >&2
    exit 1
  fi
  produce_json "surprising.perp.match.trades.v1" "${BTC_SYMBOL}" "${payload}"
}

assert_order_book_ask() {
  local description="$1"
  local price_ticks="$2"
  local quantity_steps="$3"
  local response
  response="$(curl -fsS "http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=${BTC_SYMBOL}&depth=100")"
  SNAPSHOT_JSON="${response}" python3 - "${description}" "${price_ticks}" "${quantity_steps}" <<'PY'
import json
import os
import sys
description = sys.argv[1]
price_ticks = int(sys.argv[2])
quantity_steps = int(sys.argv[3])
snapshot = json.loads(os.environ["SNAPSHOT_JSON"])
for level in snapshot.get("asks") or []:
    if level.get("priceTicks") == price_ticks and level.get("quantitySteps") == quantity_steps:
        print(f"{description}: snapshot sequence={snapshot.get('sequence')}")
        break
else:
    raise SystemExit(f"{description}: missing ask price={price_ticks} quantity={quantity_steps}")
PY
}

assert_order_book_depth_levels() {
  local expected="$1"
  local response
  response="$(curl -fsS "http://localhost:9094/api/v1/gateway/trading-market/orderbook?symbol=${BTC_SYMBOL}&depth=200")"
  SNAPSHOT_JSON="${response}" python3 - "${expected}" <<'PY'
import json
import os
import sys
expected = int(sys.argv[1])
snapshot = json.loads(os.environ["SNAPSHOT_JSON"])
asks = snapshot.get("asks") or []
if len(asks) < expected:
    raise SystemExit(f"expected at least {expected} ask levels, got {len(asks)}")
print(f"orderbook asks={len(asks)} sequence={snapshot.get('sequence')}")
PY
}

start_ws_capture() {
  local output="$1"
  shift
  local args=(python3 "${ROOT_DIR}/scripts/ws_capture.py" --url "$1" --output "${output}" --stop-file "${WS_STOP_FILE}" --timeout "${WS_TIMEOUT}")
  shift
  for subscription in "$@"; do
    args+=(--subscribe "${subscription}")
  done
  "${args[@]}" &
  PIDS+=("$!")
}

wait_ws_subscribed() {
  local file="$1"
  local channel="$2"
  local expected="$3"
  local deadline=$((SECONDS + 60))
  local count
  while true; do
    count="$(python3 - "${file}" "${channel}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
channel = sys.argv[2]
count = 0
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("op") == "subscribed" and message.get("channel") == channel:
            count += 1
print(count)
PY
)"
    if (( count >= expected )); then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for websocket subscription channel=${channel} in ${file}" >&2
      cat "${file}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

assert_ws_depth() {
  local file="$1"
  local partial_remaining=$((PARTIAL_MAKER_QTY - PARTIAL_TAKER_QTY))
  python3 - "${file}" "${BTC_SYMBOL}" "${FULL_PRICE_TICKS}" "${PARTIAL_PRICE_TICKS}" "${partial_remaining}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
symbol = sys.argv[2]
full_price = int(sys.argv[3])
partial_price = int(sys.argv[4])
partial_remaining = int(sys.argv[5])
messages = []
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            messages.append(json.loads(line))
        except json.JSONDecodeError:
            pass
events = [m for m in messages if m.get("op") == "event" and m.get("channel") == "depth" and m.get("symbol") == symbol]
if not events:
    raise SystemExit("no websocket depth events received")
if events[0].get("data", {}).get("updateType") != "SNAPSHOT":
    raise SystemExit("first websocket depth event is not a SNAPSHOT")
last_sequence = None
for event in events:
    data = event.get("data") or {}
    sequence = data.get("sequence")
    previous = data.get("previousSequence")
    if not isinstance(sequence, int):
        raise SystemExit(f"depth event missing numeric sequence: {data}")
    if last_sequence is not None and sequence <= last_sequence:
        raise SystemExit(f"depth sequence did not increase: sequence={sequence} previous_event_sequence={last_sequence}")
    if data.get("updateType") == "DELTA" and isinstance(previous, int) and previous >= sequence:
        raise SystemExit(f"depth delta previousSequence must be lower than sequence: previous={previous} sequence={sequence}")
    last_sequence = sequence
def has_ask(update_type, price, quantity):
    for event in events:
        data = event.get("data") or {}
        if data.get("updateType") != update_type:
            continue
        for level in data.get("asks") or []:
            if level.get("priceTicks") == price and level.get("quantitySteps") == quantity:
                return True
    return False
if not has_ask("DELTA", full_price, 0):
    raise SystemExit("missing full-fill ask deletion delta")
if not has_ask("DELTA", partial_price, partial_remaining):
    raise SystemExit("missing partial-fill remaining ask delta")
if not has_ask("DELTA", partial_price, 0):
    raise SystemExit("missing cancel ask deletion delta")
print(f"depth events={len(events)}")
PY
}

assert_ws_private() {
  local file="$1"
  local user_id="$2"
  local expected_position="$3"
  local expected_risk_position="${4:-skip}"
  python3 - "${file}" "${user_id}" "${expected_position}" "${expected_risk_position}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
user_id = int(sys.argv[2])
expected_position = int(sys.argv[3])
expected_risk_position = sys.argv[4]
messages = []
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            messages.append(json.loads(line))
        except json.JSONDecodeError:
            pass
orders = [m for m in messages if m.get("op") == "event" and m.get("channel") == "orders"]
matches = [m for m in messages if m.get("op") == "event" and m.get("channel") == "matches"]
positions = [
    m for m in messages
    if m.get("op") == "event"
    and m.get("channel") == "positions"
    and (m.get("data") or {}).get("userId") == user_id
]
position_risks = [
    m for m in messages
    if m.get("op") == "event"
    and m.get("channel") == "positionRisk"
    and (m.get("data") or {}).get("userId") == user_id
]
account_risks = [
    m for m in messages
    if m.get("op") == "event"
    and m.get("channel") == "accountRisk"
    and (m.get("data") or {}).get("userId") == user_id
]
if not orders:
    raise SystemExit(f"no websocket order events for user {user_id}")
if expected_position != 0 and not matches:
    raise SystemExit(f"no websocket match events for user {user_id}")
if expected_position != 0 and not any((m.get("data") or {}).get("signedQuantitySteps") == expected_position for m in positions):
    raise SystemExit(f"missing websocket position {expected_position} for user {user_id}")
if expected_risk_position != "skip":
    risk_position = int(expected_risk_position)
    if not account_risks:
        raise SystemExit(f"no websocket accountRisk events for user {user_id}")
    if not any((m.get("data") or {}).get("signedQuantitySteps") == risk_position for m in position_risks):
        raise SystemExit(f"missing websocket positionRisk {risk_position} for user {user_id}")
print(
    f"user={user_id} orders={len(orders)} matches={len(matches)} positions={len(positions)} "
    f"positionRisk={len(position_risks)} accountRisk={len(account_risks)}"
)
PY
}

assert_ws_position_side() {
  local file="$1"
  local user_id="$2"
  local position_side="$3"
  local expected_position="$4"
  python3 - "${file}" "${user_id}" "${position_side}" "${expected_position}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
user_id = int(sys.argv[2])
position_side = sys.argv[3]
expected_position = int(sys.argv[4])
messages = []
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            messages.append(json.loads(line))
        except json.JSONDecodeError:
            pass
positions = [
    m for m in messages
    if m.get("op") == "event"
    and m.get("channel") == "positions"
    and (m.get("data") or {}).get("userId") == user_id
    and (m.get("data") or {}).get("positionSide") == position_side
]
position_risks = [
    m for m in messages
    if m.get("op") == "event"
    and m.get("channel") == "positionRisk"
    and (m.get("data") or {}).get("userId") == user_id
    and (m.get("data") or {}).get("positionSide") == position_side
]
if not any((m.get("data") or {}).get("signedQuantitySteps") == expected_position for m in positions):
    raise SystemExit(f"missing websocket {position_side} position {expected_position} for user {user_id}")
if not any((m.get("data") or {}).get("signedQuantitySteps") == expected_position for m in position_risks):
    raise SystemExit(f"missing websocket {position_side} positionRisk {expected_position} for user {user_id}")
print(f"user={user_id} side={position_side} positions={len(positions)} positionRisk={len(position_risks)}")
PY
}

assert_ws_public_channel() {
  local file="$1"
  local channel="$2"
  local symbol="$3"
  python3 - "${file}" "${channel}" "${symbol}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
channel = sys.argv[2]
symbol = sys.argv[3]
count = 0
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("op") == "event" and message.get("channel") == channel and message.get("symbol") == symbol:
            count += 1
if count == 0:
    raise SystemExit(f"no websocket {channel} events for {symbol}")
print(f"{channel} events={count}")
PY
}

ws_public_channel_count() {
  local file="$1"
  local channel="$2"
  local symbol="$3"
  python3 - "${file}" "${channel}" "${symbol}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
channel = sys.argv[2]
symbol = sys.argv[3]
count = 0
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("op") == "event" and message.get("channel") == channel and message.get("symbol") == symbol:
            count += 1
print(count)
PY
}

publish_price_inputs_until_mark_event() {
  local deadline=$((SECONDS + 60))
  local sequence=900001
  while true; do
    publish_price_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${sequence}" "${BTC_PRICE_TICKS}"
    publish_price_inputs "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" "$((sequence + 1))" "${ETH_PRICE_TICKS}"
    publish_mark_price_event "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
    if (( $(ws_public_channel_count "${WS_MARK_LOG}" "mark" "${BTC_SYMBOL}") > 0 )); then
      return
    fi
    if (( SECONDS >= deadline )); then
      echo "Timed out waiting for websocket mark event for ${BTC_SYMBOL}" >&2
      cat "${WS_MARK_LOG}" >&2 || true
      exit 1
    fi
    sequence=$((sequence + 2))
    sleep 1
  done
}

fund_users() {
  local default_deposit=100000000000
  for user in "${FULL_MAKER_USER}" "${FULL_TAKER_USER}" "${PARTIAL_MAKER_USER}" "${PARTIAL_TAKER_USER}" \
    "${CANCEL_USER}" "${CLOSE_MAKER_USER}" "${TRIGGER_MAKER_USER}" "${FAULT_MAKER_USER}" "${FAULT_TAKER_USER}" \
    "${ACCOUNT_FAULT_MAKER_USER}" "${ACCOUNT_FAULT_TAKER_USER}" "${SELF_TRADE_USER}" \
    "${POST_ONLY_MAKER_USER}" "${POST_ONLY_TAKER_USER}" "${DEPTH_USER}" \
    "${ISOLATED_USER}" "${ISOLATED_MAKER_USER}" \
    "${TOPUP_MAKER_USER}" "${LIQ_OPEN_MAKER_USER}" "${LIQ_CLOSE_MAKER_USER}" "${ADL_OPEN_MAKER_USER}" \
    "${ADL_TARGET_USER}" "${MM_PROVIDER_USER_A}" "${MM_PROVIDER_USER_B}" \
    "${POSITION_MODE_USER}" "${POSITION_MODE_LONG_MAKER_USER}" "${POSITION_MODE_SHORT_MAKER_USER}" \
    "${POSITION_MODE_BLOCK_ORDER_USER}" "${POSITION_MODE_BLOCK_TRIGGER_USER}"; do
    adjust_balance "${user}" "${default_deposit}" "real-config-deposit-${RUN_ID}-${user}"
  done
  adjust_balance "${TOPUP_USER}" 1350000000 "real-config-topup-initial-${RUN_ID}"
  adjust_balance "${LIQ_USER}" 1000000000 "real-config-liq-initial-${RUN_ID}"
  for i in 0 1 2 3; do
    adjust_balance "$((ALL_CANCEL_USER_START + i))" "${default_deposit}" "real-config-all-cancel-${RUN_ID}-${i}"
  done
  for ((i = 0; i < PAIR_COUNT; i++)); do
    adjust_balance "$((LOAD_MAKER_START + i))" "${default_deposit}" "real-config-maker-${RUN_ID}-${i}"
    adjust_balance "$((LOAD_TAKER_START + i))" "${default_deposit}" "real-config-taker-${RUN_ID}-${i}"
  done
  adjust_product_balance "${COIN_MAKER_USER}" "COIN_PERPETUAL" "BTC" 100000000 "real-config-coin-maker-btc-${RUN_ID}"
  adjust_product_balance "${COIN_TAKER_USER}" "COIN_PERPETUAL" "BTC" 100000000 "real-config-coin-taker-btc-${RUN_ID}"
  adjust_product_balance "${SPOT_SELLER_USER}" "SPOT" "BTC" 1000000 "real-config-spot-seller-btc-${RUN_ID}"
  adjust_product_balance "${SPOT_BUYER_USER}" "SPOT" "USDT" 100000000000 "real-config-spot-buyer-usdt-${RUN_ID}"
}

run_with_concurrency() {
  local max_jobs="$1"
  shift
  local active_pids=()
  for command in "$@"; do
    bash -c "${command}" &
    active_pids+=("$!")
    if (( ${#active_pids[@]} >= max_jobs )); then
      wait "${active_pids[0]}"
      active_pids=("${active_pids[@]:1}")
    fi
  done
  local pid
  if ((${#active_pids[@]})); then
    for pid in "${active_pids[@]}"; do
      wait "${pid}"
    done
  fi
}

run_position_mode_flow() {
  echo "Scenario: switchable one-way and hedge position modes"
  expect_position_mode "${POSITION_MODE_USER}" "ONE_WAY"
  expect_order_position_mode_rejected "${POSITION_MODE_USER}" "real-pm-oneway-long-${RUN_ID}" \
    "BUY" "LONG" "one-way mode rejects hedge positionSide"

  local block_order
  block_order="$(place_order "${POSITION_MODE_BLOCK_ORDER_USER}" "real-pm-block-order-${RUN_ID}" \
    "SELL" "LIMIT" "GTC" "${POSITION_MODE_BLOCK_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false)"
  wait_order_result "${block_order}" "SUCCESS"
  expect_position_mode_update_status "${POSITION_MODE_BLOCK_ORDER_USER}" "HEDGE" "409" "active-order"
  cancel_order "${POSITION_MODE_BLOCK_ORDER_USER}" "${block_order}"
  wait_order_state "${block_order}" "CANCELED" "0" "0"
  wait_sql_equals "position mode blocker order reservation released" \
    "SELECT count(*) FROM account_margin_reservations WHERE order_id = ${block_order} AND status NOT IN ('RELEASED', 'CONSUMED')" \
    "0"
  update_position_mode "${POSITION_MODE_BLOCK_ORDER_USER}" "HEDGE" >/dev/null
  expect_position_mode "${POSITION_MODE_BLOCK_ORDER_USER}" "HEDGE"
  update_position_mode "${POSITION_MODE_BLOCK_ORDER_USER}" "ONE_WAY" >/dev/null
  expect_position_mode "${POSITION_MODE_BLOCK_ORDER_USER}" "ONE_WAY"

  refresh_mark_price "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_TICK_UNITS}" "${BTC_PRICE_TICKS}"
  local block_trigger_open_maker
  local block_trigger_open_taker
  block_trigger_open_maker="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "real-pm-block-trigger-open-maker-${RUN_ID}" \
    "SELL" "LIMIT" "GTC" "${BTC_PRICE_TICKS}" 1 false false)"
  wait_order_result "${block_trigger_open_maker}" "SUCCESS"
  block_trigger_open_taker="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_BLOCK_TRIGGER_USER}" "real-pm-block-trigger-open-${RUN_ID}" \
    "BUY" "LIMIT" "IOC" "${BTC_PRICE_TICKS}" 1 false false)"
  wait_order_state "${block_trigger_open_maker}" "FILLED" "1" "0"
  wait_order_state "${block_trigger_open_taker}" "FILLED" "1" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_BLOCK_TRIGGER_USER}" "1" "${BTC_PRICE_TICKS}"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "-1" "${BTC_PRICE_TICKS}"

  local block_trigger
  block_trigger="$(place_trigger_order "${POSITION_MODE_BLOCK_TRIGGER_USER}" "real-pm-block-trigger-${RUN_ID}" \
    "SELL" "TAKE_PROFIT" "$((BTC_PRICE_TICKS + 500000))" "MARKET" "IOC" 0 1 "")"
  wait_trigger_state "${block_trigger}" "PENDING"
  expect_position_mode_update_status "${POSITION_MODE_BLOCK_TRIGGER_USER}" "HEDGE" "409" "pending-trigger"
  cancel_trigger_order "${POSITION_MODE_BLOCK_TRIGGER_USER}" "${block_trigger}"
  wait_trigger_state "${block_trigger}" "CANCELED"

  local block_trigger_close_maker
  local block_trigger_close_taker
  block_trigger_close_maker="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "real-pm-block-trigger-close-maker-${RUN_ID}" \
    "BUY" "LIMIT" "GTC" "${BTC_PRICE_TICKS}" 1 true false)"
  wait_order_result "${block_trigger_close_maker}" "SUCCESS"
  block_trigger_close_taker="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_BLOCK_TRIGGER_USER}" "real-pm-block-trigger-close-${RUN_ID}" \
    "SELL" "LIMIT" "IOC" "${BTC_PRICE_TICKS}" 1 true false)"
  wait_order_state "${block_trigger_close_maker}" "FILLED" "1" "0"
  wait_order_state "${block_trigger_close_taker}" "FILLED" "1" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_BLOCK_TRIGGER_USER}" "0" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "0" "0"

  update_position_mode "${POSITION_MODE_BLOCK_TRIGGER_USER}" "HEDGE" >/dev/null
  expect_position_mode "${POSITION_MODE_BLOCK_TRIGGER_USER}" "HEDGE"
  update_position_mode "${POSITION_MODE_BLOCK_TRIGGER_USER}" "ONE_WAY" >/dev/null
  expect_position_mode "${POSITION_MODE_BLOCK_TRIGGER_USER}" "ONE_WAY"

  update_position_mode "${POSITION_MODE_USER}" "HEDGE" >/dev/null
  expect_position_mode "${POSITION_MODE_USER}" "HEDGE"
  expect_order_position_mode_rejected "${POSITION_MODE_USER}" "real-pm-hedge-net-${RUN_ID}" \
    "BUY" "NET" "hedge mode rejects NET positionSide"

  refresh_mark_price "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_TICK_UNITS}" "${BTC_PRICE_TICKS}"
  POSITION_MODE_LONG_PRICE_TICKS="$(latest_mark_price_ticks "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_TICK_UNITS}")"
  if [[ "${POSITION_MODE_LONG_PRICE_TICKS}" -le 0 ]]; then
    POSITION_MODE_LONG_PRICE_TICKS="${BTC_PRICE_TICKS}"
  fi
  POSITION_MODE_SHORT_PRICE_TICKS="${POSITION_MODE_LONG_PRICE_TICKS}"
  echo "Using ${POSITION_MODE_SYMBOL} mark price ticks ${POSITION_MODE_LONG_PRICE_TICKS} for position-mode trades"

  local long_maker_order
  local long_order
  long_maker_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "real-pm-long-maker-${RUN_ID}" \
    "SELL" "LIMIT" "GTC" "${POSITION_MODE_LONG_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false)"
  wait_order_result "${long_maker_order}" "SUCCESS"
  long_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "real-pm-long-${RUN_ID}" \
    "BUY" "LIMIT" "IOC" "${POSITION_MODE_LONG_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false "CROSS" "LONG")"
  wait_order_state "${long_maker_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_order_state "${long_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "-${POSITION_MODE_QTY}" "${POSITION_MODE_LONG_PRICE_TICKS}"
  wait_position_symbol_side "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "LONG" "${POSITION_MODE_QTY}" "${POSITION_MODE_LONG_PRICE_TICKS}"

  local short_maker_order
  local short_order
  short_maker_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_SHORT_MAKER_USER}" "real-pm-short-maker-${RUN_ID}" \
    "BUY" "LIMIT" "GTC" "${POSITION_MODE_SHORT_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false)"
  wait_order_result "${short_maker_order}" "SUCCESS"
  short_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "real-pm-short-${RUN_ID}" \
    "SELL" "LIMIT" "IOC" "${POSITION_MODE_SHORT_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false "CROSS" "SHORT")"
  wait_order_state "${short_maker_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_order_state "${short_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_SHORT_MAKER_USER}" "${POSITION_MODE_QTY}" "${POSITION_MODE_SHORT_PRICE_TICKS}"
  wait_position_symbol_side "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "SHORT" "-${POSITION_MODE_QTY}" "${POSITION_MODE_SHORT_PRICE_TICKS}"
  wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
  wait_sql_equals "hedge user has long and short rows" \
    "SELECT count(*) FROM account_positions WHERE user_id = ${POSITION_MODE_USER} AND symbol = '${POSITION_MODE_SYMBOL}' AND position_side IN ('LONG', 'SHORT') AND signed_quantity_steps <> 0" \
    "2"
  expect_position_mode_update_status "${POSITION_MODE_USER}" "ONE_WAY" "409" "open-positions"
  expect_position_mode "${POSITION_MODE_USER}" "HEDGE"

  local long_close_maker_order
  local long_close_order
  long_close_maker_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "real-pm-long-close-maker-${RUN_ID}" \
    "BUY" "LIMIT" "GTC" "${POSITION_MODE_LONG_PRICE_TICKS}" "${POSITION_MODE_QTY}" true false)"
  wait_order_result "${long_close_maker_order}" "SUCCESS"
  long_close_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "real-pm-long-close-${RUN_ID}" \
    "SELL" "LIMIT" "IOC" "${POSITION_MODE_LONG_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false "CROSS" "LONG")"
  wait_order_state "${long_close_maker_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_order_state "${long_close_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_position_quantity_side "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "LONG" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_LONG_MAKER_USER}" "0" "0"

  local short_close_maker_order
  local short_close_order
  short_close_maker_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_SHORT_MAKER_USER}" "real-pm-short-close-maker-${RUN_ID}" \
    "SELL" "LIMIT" "GTC" "${POSITION_MODE_SHORT_PRICE_TICKS}" "${POSITION_MODE_QTY}" true false)"
  wait_order_result "${short_close_maker_order}" "SUCCESS"
  short_close_order="$(place_order_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "real-pm-short-close-${RUN_ID}" \
    "BUY" "LIMIT" "IOC" "${POSITION_MODE_SHORT_PRICE_TICKS}" "${POSITION_MODE_QTY}" false false "CROSS" "SHORT")"
  wait_order_state "${short_close_maker_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_order_state "${short_close_order}" "FILLED" "${POSITION_MODE_QTY}" "0"
  wait_position_quantity_side "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_USER}" "SHORT" "0"
  wait_position_symbol "${POSITION_MODE_SYMBOL}" "${POSITION_MODE_SHORT_MAKER_USER}" "0" "0"
  wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
  wait_sql_equals "hedge user has no signed positions" \
    "SELECT COALESCE(SUM(ABS(signed_quantity_steps)), 0) FROM account_positions WHERE user_id = ${POSITION_MODE_USER} AND symbol = '${POSITION_MODE_SYMBOL}'" \
    "0"
  update_position_mode "${POSITION_MODE_USER}" "ONE_WAY" >/dev/null
  expect_position_mode "${POSITION_MODE_USER}" "ONE_WAY"
}

assert_no_negative_balances() {
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

run_coin_perpetual_user_flow() {
  echo "Scenario: coin-margined perpetual open, reduce-only close, and BTC settlement"
  refresh_mark_price "${COIN_SYMBOL}" "${BTC_TICK_UNITS}" "${COIN_PRICE_TICKS}"
  wait_product_balance "${COIN_MAKER_USER}" "COIN_PERPETUAL" "BTC" 100000000 0
  wait_product_balance "${COIN_TAKER_USER}" "COIN_PERPETUAL" "BTC" 100000000 0

  coin_maker_order="$(place_order_symbol "${COIN_SYMBOL}" "${COIN_MAKER_USER}" "real-coin-maker-open-${RUN_ID}" "SELL" "LIMIT" "GTC" "${COIN_PRICE_TICKS}" "${COIN_QTY}" false false)"
  wait_order_result "${coin_maker_order}" "SUCCESS"
  wait_sql_equals "coin maker margin reserved from product account" \
    "SELECT account_type || ':' || asset FROM account_margin_reservations WHERE order_id = ${coin_maker_order}" \
    "COIN_PERPETUAL:BTC"
  coin_taker_order="$(place_order_symbol "${COIN_SYMBOL}" "${COIN_TAKER_USER}" "real-coin-taker-open-${RUN_ID}" "BUY" "LIMIT" "IOC" "${COIN_PRICE_TICKS}" "${COIN_QTY}" false false)"
  wait_order_state "${coin_maker_order}" "FILLED" "${COIN_QTY}" "0"
  wait_order_state "${coin_taker_order}" "FILLED" "${COIN_QTY}" "0"
  wait_position_symbol "${COIN_SYMBOL}" "${COIN_MAKER_USER}" "-${COIN_QTY}" "${COIN_PRICE_TICKS}"
  wait_position_symbol "${COIN_SYMBOL}" "${COIN_TAKER_USER}" "${COIN_QTY}" "${COIN_PRICE_TICKS}"
  wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
  wait_sql_equals "coin perpetual risk snapshots settle in BTC" \
    "SELECT count(DISTINCT user_id) FROM risk_account_snapshots WHERE user_id IN (${COIN_MAKER_USER}, ${COIN_TAKER_USER}) AND settle_asset = 'BTC'" \
    "2"

  refresh_mark_price "${COIN_SYMBOL}" "${BTC_TICK_UNITS}" "${COIN_CLOSE_PRICE_TICKS}"
  coin_close_maker_order="$(place_order_symbol "${COIN_SYMBOL}" "${COIN_MAKER_USER}" "real-coin-maker-close-${RUN_ID}" "BUY" "LIMIT" "GTC" "${COIN_CLOSE_PRICE_TICKS}" "${COIN_QTY}" true false)"
  wait_order_result "${coin_close_maker_order}" "SUCCESS"
  coin_close_taker_order="$(place_order_symbol "${COIN_SYMBOL}" "${COIN_TAKER_USER}" "real-coin-taker-close-${RUN_ID}" "SELL" "LIMIT" "IOC" "${COIN_CLOSE_PRICE_TICKS}" "${COIN_QTY}" true false)"
  wait_order_state "${coin_close_maker_order}" "FILLED" "${COIN_QTY}" "0"
  wait_order_state "${coin_close_taker_order}" "FILLED" "${COIN_QTY}" "0"
  wait_position_symbol "${COIN_SYMBOL}" "${COIN_MAKER_USER}" "0" "0"
  wait_position_symbol "${COIN_SYMBOL}" "${COIN_TAKER_USER}" "0" "0"
  wait_product_locked_zero "${COIN_MAKER_USER}" "COIN_PERPETUAL" "BTC"
  wait_product_locked_zero "${COIN_TAKER_USER}" "COIN_PERPETUAL" "BTC"
  wait_sql_equals "coin trades processed by account settlement" \
    "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.symbol = '${COIN_SYMBOL}' AND t.maker_user_id IN (${COIN_MAKER_USER}, ${COIN_TAKER_USER}) AND t.taker_user_id IN (${COIN_MAKER_USER}, ${COIN_TAKER_USER})" \
    "2"
  wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
  wait_sql_equals "coin taker latest risk position is flat" \
    "SELECT signed_quantity_steps || ':' || notional_units || ':' || maintenance_margin_units FROM risk_position_snapshots WHERE user_id = ${COIN_TAKER_USER} AND symbol = '${COIN_SYMBOL}' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1" \
    "0:0:0"
}

run_spot_user_flow() {
  echo "Scenario: spot sell and buy settle product balances without perpetual positions"
  refresh_mark_price "${SPOT_SYMBOL}" "${BTC_TICK_UNITS}" "${SPOT_PRICE_TICKS}"
  wait_product_balance "${SPOT_SELLER_USER}" "SPOT" "BTC" 1000000 0
  wait_product_balance "${SPOT_BUYER_USER}" "SPOT" "USDT" 100000000000 0

  spot_sell_order="$(place_order_symbol "${SPOT_SYMBOL}" "${SPOT_SELLER_USER}" "real-spot-seller-${RUN_ID}" "SELL" "LIMIT" "GTC" "${SPOT_PRICE_TICKS}" "${SPOT_QTY}" false false)"
  wait_order_result "${spot_sell_order}" "SUCCESS"
  wait_sql_equals "spot seller reserved base asset" \
    "SELECT asset FROM account_spot_order_reservations WHERE order_id = ${spot_sell_order}" \
    "BTC"
  spot_buy_order="$(place_order_symbol "${SPOT_SYMBOL}" "${SPOT_BUYER_USER}" "real-spot-buyer-${RUN_ID}" "BUY" "LIMIT" "IOC" "${SPOT_PRICE_TICKS}" "${SPOT_QTY}" false false)"
  wait_order_state "${spot_sell_order}" "FILLED" "${SPOT_QTY}" "0"
  wait_order_state "${spot_buy_order}" "FILLED" "${SPOT_QTY}" "0"
  wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
  wait_product_locked_zero "${SPOT_SELLER_USER}" "SPOT" "BTC"
  wait_product_locked_zero "${SPOT_BUYER_USER}" "SPOT" "USDT"
  wait_sql_equals "spot buyer received BTC" \
    "SELECT CASE WHEN COALESCE((SELECT available_units FROM account_product_balances WHERE account_type = 'SPOT' AND user_id = ${SPOT_BUYER_USER} AND asset = 'BTC'), 0) > 0 THEN 1 ELSE 0 END" \
    "1"
  wait_sql_equals "spot seller received USDT" \
    "SELECT CASE WHEN COALESCE((SELECT available_units FROM account_product_balances WHERE account_type = 'SPOT' AND user_id = ${SPOT_SELLER_USER} AND asset = 'USDT'), 0) > 0 THEN 1 ELSE 0 END" \
    "1"
  wait_sql_equals "spot orders did not create perpetual positions" \
    "SELECT count(*) FROM account_positions WHERE symbol = '${SPOT_SYMBOL}' AND user_id IN (${SPOT_SELLER_USER}, ${SPOT_BUYER_USER})" \
    "0"
  wait_sql_equals "spot reservations settled" \
    "SELECT count(*) FROM account_spot_order_reservations WHERE symbol = '${SPOT_SYMBOL}' AND status = 'SETTLED'" \
    "2"
  wait_sql_equals "spot trade processed by account settlement" \
    "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.symbol = '${SPOT_SYMBOL}' AND t.maker_user_id = ${SPOT_SELLER_USER} AND t.taker_user_id = ${SPOT_BUYER_USER}" \
    "1"
}

register_provider "instrument" 9080 "surprising-instrument/surprising-instrument-provider" "surprising-instrument-provider"
register_provider "candlestick" 9081 "surprising-candlestick/surprising-candlestick-provider" "surprising-candlestick-provider"
register_provider "index-price" 9082 "surprising-price/surprising-index-price-provider" "surprising-index-price-provider"
register_provider "mark-price" 9083 "surprising-price/surprising-mark-price-provider" "surprising-mark-price-provider"
register_provider "order" 9084 "surprising-trading/surprising-order-provider" "surprising-order-provider"
register_provider "matching" 9085 "surprising-trading/surprising-matching-provider" "surprising-matching-provider"
register_provider "account" 9086 "surprising-account/surprising-account-provider" "surprising-account-provider"
register_provider "risk" 9087 "surprising-risk/surprising-risk-provider" "surprising-risk-provider"
register_provider "liquidation" 9088 "surprising-liquidation/surprising-liquidation-provider" "surprising-liquidation-provider"
register_provider "funding" 9089 "surprising-funding/surprising-funding-provider" "surprising-funding-provider"
register_provider "insurance" 9090 "surprising-insurance/surprising-insurance-provider" "surprising-insurance-provider"
register_provider "adl" 9091 "surprising-adl/surprising-adl-provider" "surprising-adl-provider"
register_provider "websocket" 9093 "surprising-websocket/surprising-websocket-provider" "surprising-websocket-provider"
register_provider "gateway" 9094 "surprising-gateway/surprising-gateway-provider" "surprising-gateway-provider"
register_provider "trigger" 9095 "surprising-trading/surprising-trigger-provider" "surprising-trigger-provider"
register_provider "market-maker" 9096 "surprising-market-maker/surprising-market-maker-provider" "surprising-market-maker-provider"

require_command curl
require_command python3
require_command docker
detect_compose

if [[ "${START_PROVIDERS}" != "true" && "${RESET_STATE}" == "true" ]]; then
  echo "START_PROVIDERS=false requires RESET_STATE=false; do not reset DB/Kafka while reusing live services" >&2
  exit 1
fi

if [[ "${START_PROVIDERS}" != "true" && "${RUN_FAILURE_SCENARIOS}" == "true" ]]; then
  echo "START_PROVIDERS=false requires RUN_FAILURE_SCENARIOS=false; fault scenarios need managed provider PIDs" >&2
  exit 1
fi

if [[ "${START_INFRA}" == "true" ]]; then
  echo "Starting Docker infrastructure"
  start_infra
fi

wait_until "PostgreSQL" 120 compose_exec postgres pg_isready -U "${DB_USER}"
wait_until "Kafka" 120 compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list
ensure_database

if [[ "${RESET_STATE}" == "true" ]]; then
  echo "Resetting database ${DB_NAME}"
  reset_database
  seed_full_stack_products
  seed_prices

  echo "Resetting Kafka topics and local RocksDB state"
  delete_surprising_topics
  create_topics
  clean_local_state
else
  echo "Reusing existing DB, Kafka topics, and local state"
fi

if [[ "${START_PROVIDERS}" == "true" ]]; then
  package_services

  for provider in instrument candlestick index-price mark-price matching account risk liquidation funding insurance adl websocket order trigger gateway market-maker; do
    start_provider "${provider}"
  done
else
  echo "Reusing already running providers"
  for provider in instrument candlestick index-price mark-price matching account risk liquidation funding insurance adl websocket order trigger gateway market-maker; do
    wait_http "${provider}" "$(provider_port "${provider}")"
  done
fi

echo "Starting WebSocket captures"
WS_DEPTH_LOG="${TMP_DIR}/ws-depth.jsonl"
WS_FULL_TAKER_LOG="${TMP_DIR}/ws-full-taker.jsonl"
WS_PARTIAL_MAKER_LOG="${TMP_DIR}/ws-partial-maker.jsonl"
WS_CANCEL_LOG="${TMP_DIR}/ws-cancel.jsonl"
WS_ISOLATED_LOG="${TMP_DIR}/ws-isolated.jsonl"
WS_POSITION_MODE_LOG="${TMP_DIR}/ws-position-mode.jsonl"
WS_MARK_LOG="${TMP_DIR}/ws-mark.jsonl"
WS_FUNDING_LOG="${TMP_DIR}/ws-funding.jsonl"
start_ws_capture "${WS_DEPTH_LOG}" "ws://localhost:9093/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"depth\",\"channel\":\"depth\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_FULL_TAKER_LOG}" "ws://localhost:9093/ws/v1?userId=${FULL_TAKER_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-position-risk\",\"channel\":\"positionRisk\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-account-risk\",\"channel\":\"accountRisk\"}"
start_ws_capture "${WS_PARTIAL_MAKER_LOG}" "ws://localhost:9093/ws/v1?userId=${PARTIAL_MAKER_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-position-risk\",\"channel\":\"positionRisk\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-account-risk\",\"channel\":\"accountRisk\"}"
start_ws_capture "${WS_CANCEL_LOG}" "ws://localhost:9093/ws/v1?userId=${CANCEL_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_ISOLATED_LOG}" "ws://localhost:9093/ws/v1?userId=${ISOLATED_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"iso-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"iso-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"iso-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"iso-position-risk\",\"channel\":\"positionRisk\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"iso-account-risk\",\"channel\":\"accountRisk\"}"
start_ws_capture "${WS_POSITION_MODE_LOG}" "ws://localhost:9093/ws/v1?userId=${POSITION_MODE_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"pmode-orders\",\"channel\":\"orders\",\"symbol\":\"${POSITION_MODE_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pmode-matches\",\"channel\":\"matches\",\"symbol\":\"${POSITION_MODE_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pmode-positions\",\"channel\":\"positions\",\"symbol\":\"${POSITION_MODE_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pmode-position-risk\",\"channel\":\"positionRisk\",\"symbol\":\"${POSITION_MODE_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pmode-account-risk\",\"channel\":\"accountRisk\"}"
start_ws_capture "${WS_MARK_LOG}" "ws://localhost:9093/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"mark\",\"channel\":\"mark\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_FUNDING_LOG}" "ws://localhost:9093/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"funding\",\"channel\":\"funding\",\"symbol\":\"${BTC_SYMBOL}\"}"

wait_ws_subscribed "${WS_DEPTH_LOG}" "depth" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "orders" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "matches" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "positions" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "positionRisk" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "accountRisk" 1
wait_ws_subscribed "${WS_PARTIAL_MAKER_LOG}" "orders" 1
wait_ws_subscribed "${WS_PARTIAL_MAKER_LOG}" "positionRisk" 1
wait_ws_subscribed "${WS_PARTIAL_MAKER_LOG}" "accountRisk" 1
wait_ws_subscribed "${WS_CANCEL_LOG}" "orders" 1
wait_ws_subscribed "${WS_ISOLATED_LOG}" "orders" 1
wait_ws_subscribed "${WS_ISOLATED_LOG}" "matches" 1
wait_ws_subscribed "${WS_ISOLATED_LOG}" "positions" 1
wait_ws_subscribed "${WS_ISOLATED_LOG}" "positionRisk" 1
wait_ws_subscribed "${WS_ISOLATED_LOG}" "accountRisk" 1
wait_ws_subscribed "${WS_POSITION_MODE_LOG}" "orders" 1
wait_ws_subscribed "${WS_POSITION_MODE_LOG}" "matches" 1
wait_ws_subscribed "${WS_POSITION_MODE_LOG}" "positions" 1
wait_ws_subscribed "${WS_POSITION_MODE_LOG}" "positionRisk" 1
wait_ws_subscribed "${WS_POSITION_MODE_LOG}" "accountRisk" 1
wait_ws_subscribed "${WS_MARK_LOG}" "mark" 1
wait_ws_subscribed "${WS_FUNDING_LOG}" "funding" 1

echo "Publishing price inputs through Kafka"
publish_price_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" 2001 "${BTC_PRICE_TICKS}"
publish_price_inputs "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 2001 "${ETH_PRICE_TICKS}"
publish_price_inputs "${COIN_SYMBOL}" "${BTC_TICK_UNITS}" 2001 "${COIN_PRICE_TICKS}"
publish_price_inputs_until_mark_event

echo "Funding users through gateway"
fund_users

run_position_mode_flow
run_coin_perpetual_user_flow
run_spot_user_flow

echo "Scenario: full fill"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
full_maker_order="$(place_order "${FULL_MAKER_USER}" "real-full-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_result "${full_maker_order}" "SUCCESS"
assert_order_book_ask "full maker REST book" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
full_taker_order="$(place_order "${FULL_TAKER_USER}" "real-full-taker-${RUN_ID}" "BUY" "LIMIT" "IOC" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_state "${full_maker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
wait_order_state "${full_taker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
wait_position "${FULL_MAKER_USER}" "-${QUANTITY_STEPS}" "${FULL_PRICE_TICKS}"
wait_position "${FULL_TAKER_USER}" "${QUANTITY_STEPS}" "${FULL_PRICE_TICKS}"
wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
wait_sql_equals "risk snapshots after account position events" \
  "SELECT count(DISTINCT user_id) FROM risk_account_snapshots WHERE user_id IN (${FULL_MAKER_USER}, ${FULL_TAKER_USER}) AND settle_asset = 'USDT'" \
  "2"

before_positions="$(query_value "SELECT COALESCE(string_agg(user_id || ':' || signed_quantity_steps || ':' || entry_price_ticks, ',' ORDER BY user_id), '') FROM account_positions WHERE symbol = '${BTC_SYMBOL}' AND user_id IN (${FULL_MAKER_USER}, ${FULL_TAKER_USER})")"
before_processed="$(query_value "SELECT count(*) FROM account_processed_trades WHERE symbol = '${BTC_SYMBOL}'")"
publish_duplicate_match_trade
wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
after_positions="$(query_value "SELECT COALESCE(string_agg(user_id || ':' || signed_quantity_steps || ':' || entry_price_ticks, ',' ORDER BY user_id), '') FROM account_positions WHERE symbol = '${BTC_SYMBOL}' AND user_id IN (${FULL_MAKER_USER}, ${FULL_TAKER_USER})")"
after_processed="$(query_value "SELECT count(*) FROM account_processed_trades WHERE symbol = '${BTC_SYMBOL}'")"
if [[ "${before_positions}" != "${after_positions}" || "${before_processed}" != "${after_processed}" ]]; then
  echo "Duplicate match-trade replay changed account state" >&2
  exit 1
fi

echo "Scenario: isolated position margin add, safe remove, and unsafe remove rejection"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
isolated_maker_order="$(place_order "${ISOLATED_MAKER_USER}" "real-isolated-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${BTC_PRICE_TICKS}" "${ISOLATED_QTY}" false false)"
wait_order_result "${isolated_maker_order}" "SUCCESS"
isolated_order="$(place_order_symbol "${BTC_SYMBOL}" "${ISOLATED_USER}" "real-isolated-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${BTC_PRICE_TICKS}" "${ISOLATED_QTY}" false false "ISOLATED")"
wait_order_state "${isolated_order}" "FILLED" "${ISOLATED_QTY}" "0"
wait_position_symbol_margin "${BTC_SYMBOL}" "${ISOLATED_USER}" "ISOLATED" "${ISOLATED_QTY}" "${BTC_PRICE_TICKS}"
wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
initial_isolated_margin="$(query_value "SELECT margin_units FROM account_position_margins WHERE user_id = ${ISOLATED_USER} AND symbol = '${BTC_SYMBOL}' AND margin_mode = 'ISOLATED'")"
if [[ -z "${initial_isolated_margin}" || "${initial_isolated_margin}" == "0" ]]; then
  echo "isolated position did not migrate order margin into account_position_margins" >&2
  exit 1
fi
wait_latest_isolated_risk_position_margin "${BTC_SYMBOL}" "${ISOLATED_USER}" "${initial_isolated_margin}"
initial_available="$(query_value "SELECT available_units FROM account_balances WHERE user_id = ${ISOLATED_USER} AND asset = 'USDT'")"
initial_locked="$(query_value "SELECT locked_units FROM account_balances WHERE user_id = ${ISOLATED_USER} AND asset = 'USDT'")"
isolated_add_units=$((initial_isolated_margin / 2))
if (( isolated_add_units <= 0 )); then
  isolated_add_units=1
fi
adjust_position_margin "${ISOLATED_USER}" "${BTC_SYMBOL}" "${isolated_add_units}" "real-isolated-add-${RUN_ID}" >/dev/null
after_add_margin=$((initial_isolated_margin + isolated_add_units))
wait_position_margin_units "${BTC_SYMBOL}" "${ISOLATED_USER}" "${after_add_margin}"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
wait_sql_equals "isolated add moved available to locked" \
  "SELECT available_units || ':' || locked_units FROM account_balances WHERE user_id = ${ISOLATED_USER} AND asset = 'USDT'" \
  "$((initial_available - isolated_add_units)):$((initial_locked + isolated_add_units))"
wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
wait_latest_isolated_risk_position_margin "${BTC_SYMBOL}" "${ISOLATED_USER}" "${after_add_margin}"
isolated_remove_units=$((isolated_add_units / 2))
if (( isolated_remove_units <= 0 )); then
  isolated_remove_units=1
fi
adjust_position_margin "${ISOLATED_USER}" "${BTC_SYMBOL}" "-${isolated_remove_units}" "real-isolated-remove-${RUN_ID}" >/dev/null
after_remove_margin=$((after_add_margin - isolated_remove_units))
wait_position_margin_units "${BTC_SYMBOL}" "${ISOLATED_USER}" "${after_remove_margin}"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
wait_sql_equals "isolated safe remove moved locked to available" \
  "SELECT available_units || ':' || locked_units FROM account_balances WHERE user_id = ${ISOLATED_USER} AND asset = 'USDT'" \
  "$((initial_available - isolated_add_units + isolated_remove_units)):$((initial_locked + isolated_add_units - isolated_remove_units))"
wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
wait_latest_isolated_risk_position_margin "${BTC_SYMBOL}" "${ISOLATED_USER}" "${after_remove_margin}"
unsafe_remove_units=$((after_remove_margin + 1))
expect_position_margin_adjustment_rejected "${ISOLATED_USER}" "${BTC_SYMBOL}" "-${unsafe_remove_units}" "real-isolated-unsafe-remove-${RUN_ID}" "400"
wait_position_margin_units "${BTC_SYMBOL}" "${ISOLATED_USER}" "${after_remove_margin}"
wait_sql_equals "unsafe isolated remove did not write ledger" \
  "SELECT count(*) FROM account_ledger_entries WHERE user_id = ${ISOLATED_USER} AND reference_type = 'POSITION_MARGIN_ADJUSTMENT' AND reference_id = 'real-isolated-unsafe-remove-${RUN_ID}'" \
  "0"

echo "Scenario: partial fill then cancel"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
partial_maker_order="$(place_order "${PARTIAL_MAKER_USER}" "real-partial-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_MAKER_QTY}" false false)"
wait_order_result "${partial_maker_order}" "SUCCESS"
assert_order_book_ask "partial maker REST book" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_MAKER_QTY}"
partial_taker_order="$(place_order "${PARTIAL_TAKER_USER}" "real-partial-taker-${RUN_ID}" "BUY" "LIMIT" "IOC" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_TAKER_QTY}" false false)"
wait_order_state "${partial_maker_order}" "PARTIALLY_FILLED" "${PARTIAL_TAKER_QTY}" "$((PARTIAL_MAKER_QTY - PARTIAL_TAKER_QTY))"
wait_order_state "${partial_taker_order}" "FILLED" "${PARTIAL_TAKER_QTY}" "0"
wait_position "${PARTIAL_MAKER_USER}" "-${PARTIAL_TAKER_QTY}" "${PARTIAL_PRICE_TICKS}"
wait_position "${PARTIAL_TAKER_USER}" "${PARTIAL_TAKER_QTY}" "${PARTIAL_PRICE_TICKS}"
cancel_order "${PARTIAL_MAKER_USER}" "${partial_maker_order}"
wait_order_state "${partial_maker_order}" "CANCELED" "${PARTIAL_TAKER_QTY}" "0"

echo "Scenario: cancel-only and cancel-all"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
cancel_order_id="$(place_order "${CANCEL_USER}" "real-cancel-only-${RUN_ID}" "SELL" "LIMIT" "GTC" "${CANCEL_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_result "${cancel_order_id}" "SUCCESS"
cancel_order "${CANCEL_USER}" "${cancel_order_id}"
wait_order_state "${cancel_order_id}" "CANCELED" "0" "0"
wait_position "${CANCEL_USER}" "0" "0"

all_cancel_orders=()
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 0))" "real-all-cancel-0-${RUN_ID}" "BUY" "LIMIT" "GTC" "${ALL_CANCEL_BID_PRICE}" "${QUANTITY_STEPS}" false false)")
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 1))" "real-all-cancel-1-${RUN_ID}" "BUY" "LIMIT" "GTC" "$((ALL_CANCEL_BID_PRICE + 1))" "${QUANTITY_STEPS}" false false)")
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 2))" "real-all-cancel-2-${RUN_ID}" "SELL" "LIMIT" "GTC" "${ALL_CANCEL_ASK_PRICE}" "${QUANTITY_STEPS}" false false)")
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 3))" "real-all-cancel-3-${RUN_ID}" "SELL" "LIMIT" "GTC" "$((ALL_CANCEL_ASK_PRICE + 1))" "${QUANTITY_STEPS}" false false)")
for order_id in "${all_cancel_orders[@]}"; do
  wait_order_result "${order_id}" "SUCCESS"
done
for i in "${!all_cancel_orders[@]}"; do
  cancel_order "$((ALL_CANCEL_USER_START + i))" "${all_cancel_orders[$i]}"
done
for order_id in "${all_cancel_orders[@]}"; do
  wait_order_state "${order_id}" "CANCELED" "0" "0"
done

run_index_source_fail_closed_flow

echo "Scenario: active reduce-only close"
expire_mark_price "${BTC_SYMBOL}"
stale_market_order="$(place_order "${FULL_TAKER_USER}" "real-stale-mark-market-${RUN_ID}" "BUY" "MARKET" "IOC" 0 1 false false)"
wait_order_state "${stale_market_order}" "REJECTED" "0" "0"
wait_sql_equals "stale mark market reject reason" \
  "SELECT reject_reason FROM trading_orders WHERE order_id = ${stale_market_order}" \
  "mark price unavailable"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
close_maker_order="$(place_order "${CLOSE_MAKER_USER}" "real-close-maker-${RUN_ID}" "BUY" "LIMIT" "GTC" "${FULL_PRICE_TICKS}" "${CLOSE_QTY}" false false)"
wait_order_result "${close_maker_order}" "SUCCESS"
close_order="$(place_order "${FULL_TAKER_USER}" "real-active-close-${RUN_ID}" "SELL" "LIMIT" "IOC" "${FULL_PRICE_TICKS}" "${CLOSE_QTY}" true false)"
wait_order_state "${close_order}" "FILLED" "${CLOSE_QTY}" "0"
wait_position "${FULL_TAKER_USER}" "$((QUANTITY_STEPS - CLOSE_QTY))" "${FULL_PRICE_TICKS}"
wait_position "${CLOSE_MAKER_USER}" "${CLOSE_QTY}" "${FULL_PRICE_TICKS}"

echo "Scenario: take-profit trigger order"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
if (( TRIGGER_CLOSE_QTY <= 0 )); then
  echo "TRIGGER_CLOSE_QTY must be positive; increase QUANTITY_STEPS or lower CLOSE_QTY" >&2
  exit 1
fi
trigger_maker_order="$(place_order "${TRIGGER_MAKER_USER}" "real-trigger-maker-${RUN_ID}" "BUY" "LIMIT" "GTC" "${FULL_PRICE_TICKS}" "${TRIGGER_CLOSE_QTY}" false false)"
wait_order_result "${trigger_maker_order}" "SUCCESS"
trigger_order_id="$(place_trigger_order "${FULL_TAKER_USER}" "real-tp-${RUN_ID}" "SELL" "TAKE_PROFIT" \
  "$((FULL_PRICE_TICKS + 500))" "LIMIT" "IOC" "${FULL_PRICE_TICKS}" "${TRIGGER_CLOSE_QTY}")"
publish_mark_price_event "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "$((FULL_PRICE_TICKS + 1000))"
wait_trigger_state "${trigger_order_id}" "TRIGGERED"
trigger_placed_order_id="$(query_value "SELECT placed_order_id FROM trading_trigger_orders WHERE trigger_order_id = ${trigger_order_id}")"
wait_order_state "${trigger_placed_order_id}" "FILLED" "${TRIGGER_CLOSE_QTY}" "0"
wait_position "${FULL_TAKER_USER}" "0" "0"
wait_position "${TRIGGER_MAKER_USER}" "${TRIGGER_CLOSE_QTY}" "${FULL_PRICE_TICKS}"

echo "Scenario: risk controls"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
no_funds_order="$(place_order "${NO_FUNDS_USER}" "real-no-funds-${RUN_ID}" "BUY" "LIMIT" "GTC" "${BTC_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_state "${no_funds_order}" "REJECTED" "0" "0"
wait_sql_equals "insufficient margin reject reason" \
  "SELECT reject_reason FROM trading_orders WHERE order_id = ${no_funds_order}" \
  "insufficient available margin"
self_maker_order="$(place_order "${SELF_TRADE_USER}" "real-self-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${SELF_TRADE_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_result "${self_maker_order}" "SUCCESS"
self_taker_order="$(place_order "${SELF_TRADE_USER}" "real-self-taker-${RUN_ID}" "BUY" "LIMIT" "IOC" "${SELF_TRADE_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_result "${self_taker_order}" "SELF_TRADE_PREVENTED"
post_only_maker_order="$(place_order "${POST_ONLY_MAKER_USER}" "real-postonly-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${POST_ONLY_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_result "${post_only_maker_order}" "SUCCESS"
post_only_taker_order="$(place_order "${POST_ONLY_TAKER_USER}" "real-postonly-taker-${RUN_ID}" "BUY" "LIMIT" "GTX" "${POST_ONLY_PRICE_TICKS}" "${QUANTITY_STEPS}" false true)"
wait_order_result "${post_only_taker_order}" "POST_ONLY_WOULD_TAKE"

if [[ "${RUN_FAILURE_SCENARIOS}" == "true" ]]; then
  echo "Scenario: matching restart restores open order book"
  refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
  fault_maker_order="$(place_order "${FAULT_MAKER_USER}" "real-fault-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${FAULT_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
  wait_order_result "${fault_maker_order}" "SUCCESS"
  stop_provider "matching"
  fault_taker_order="$(place_order "${FAULT_TAKER_USER}" "real-fault-taker-${RUN_ID}" "BUY" "LIMIT" "IOC" "${FAULT_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
  sleep 2
  wait_sql_equals "fault taker has no match while matching is down" \
    "SELECT count(*) FROM trading_match_results WHERE order_id = ${fault_taker_order}" \
    "0"
  start_provider "matching"
  wait_order_state "${fault_maker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
  wait_order_state "${fault_taker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
  wait_position "${FAULT_MAKER_USER}" "-${QUANTITY_STEPS}" "${FAULT_PRICE_TICKS}"
  wait_position "${FAULT_TAKER_USER}" "${QUANTITY_STEPS}" "${FAULT_PRICE_TICKS}"

  echo "Scenario: account restart replays unsettled match trades safely"
  refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
  stop_provider "account"
  account_fault_maker_order="$(place_order "${ACCOUNT_FAULT_MAKER_USER}" "real-account-fault-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${ACCOUNT_FAULT_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
  wait_order_result "${account_fault_maker_order}" "SUCCESS"
  account_fault_taker_order="$(place_order "${ACCOUNT_FAULT_TAKER_USER}" "real-account-fault-taker-${RUN_ID}" "BUY" "LIMIT" "IOC" "${ACCOUNT_FAULT_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
  wait_order_state "${account_fault_taker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
  sleep 2
  wait_sql_equals "account fault positions not settled while account is down" \
    "SELECT count(*) FROM account_positions WHERE user_id IN (${ACCOUNT_FAULT_MAKER_USER}, ${ACCOUNT_FAULT_TAKER_USER}) AND symbol = '${BTC_SYMBOL}'" \
    "0"
  start_provider "account"
  wait_position "${ACCOUNT_FAULT_MAKER_USER}" "-${QUANTITY_STEPS}" "${ACCOUNT_FAULT_PRICE_TICKS}"
  wait_position "${ACCOUNT_FAULT_TAKER_USER}" "${QUANTITY_STEPS}" "${ACCOUNT_FAULT_PRICE_TICKS}"
else
  echo "Skipping provider failure scenarios"
fi

echo "Scenario: concurrent high-frequency load PAIR_COUNT=${PAIR_COUNT} LOAD_CONCURRENCY=${LOAD_CONCURRENCY}"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
maker_commands=()
taker_commands=()
for ((i = 0; i < PAIR_COUNT; i++)); do
  maker_commands+=("curl -fsS -X POST 'http://localhost:9094/api/v1/gateway/trading' -H 'Content-Type: application/json' -H 'X-User-Id: $((LOAD_MAKER_START + i))' -H 'X-Trace-Id: real-config-${RUN_ID}-maker-${i}' -d '{\"userId\": $((LOAD_MAKER_START + i)), \"clientOrderId\": \"real-load-maker-${RUN_ID}-${i}\", \"symbol\": \"${BTC_SYMBOL}\", \"side\": \"SELL\", \"orderType\": \"LIMIT\", \"timeInForce\": \"GTC\", \"priceTicks\": ${LOAD_PRICE_TICKS}, \"quantitySteps\": ${LOAD_QTY}, \"reduceOnly\": false, \"postOnly\": false}' >/dev/null")
done
run_with_concurrency "${LOAD_CONCURRENCY}" "${maker_commands[@]}"
wait_sql_equals "all load maker PLACE commands matched" \
  "SELECT count(*) FROM trading_match_results r JOIN trading_orders o ON o.order_id = r.order_id WHERE o.client_order_id LIKE 'real-load-maker-${RUN_ID}-%' AND r.command_type = 'PLACE' AND r.result_code = 'SUCCESS'" \
  "${PAIR_COUNT}"
for ((i = 0; i < PAIR_COUNT; i++)); do
  taker_commands+=("curl -fsS -X POST 'http://localhost:9094/api/v1/gateway/trading' -H 'Content-Type: application/json' -H 'X-User-Id: $((LOAD_TAKER_START + i))' -H 'X-Trace-Id: real-config-${RUN_ID}-taker-${i}' -d '{\"userId\": $((LOAD_TAKER_START + i)), \"clientOrderId\": \"real-load-taker-${RUN_ID}-${i}\", \"symbol\": \"${BTC_SYMBOL}\", \"side\": \"BUY\", \"orderType\": \"LIMIT\", \"timeInForce\": \"IOC\", \"priceTicks\": ${LOAD_PRICE_TICKS}, \"quantitySteps\": ${LOAD_QTY}, \"reduceOnly\": false, \"postOnly\": false}' >/dev/null")
done
run_with_concurrency "${LOAD_CONCURRENCY}" "${taker_commands[@]}"
wait_sql_equals "load maker orders filled" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'real-load-maker-${RUN_ID}-%' AND status = 'FILLED' AND executed_quantity_steps = ${LOAD_QTY} AND remaining_quantity_steps = 0" \
  "${PAIR_COUNT}"
wait_sql_equals "load taker orders filled" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'real-load-taker-${RUN_ID}-%' AND status = 'FILLED' AND executed_quantity_steps = ${LOAD_QTY} AND remaining_quantity_steps = 0" \
  "${PAIR_COUNT}"
wait_sql_equals "load trade count and quantity" \
  "SELECT count(*) || ':' || COALESCE(sum(quantity_steps), 0) FROM trading_match_trades WHERE symbol = '${BTC_SYMBOL}' AND maker_user_id BETWEEN ${LOAD_MAKER_START} AND $((LOAD_MAKER_START + PAIR_COUNT - 1)) AND taker_user_id BETWEEN ${LOAD_TAKER_START} AND $((LOAD_TAKER_START + PAIR_COUNT - 1))" \
  "${PAIR_COUNT}:${LOAD_TOTAL_QTY}"
wait_sql_equals "load account processed trades" \
  "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.symbol = '${BTC_SYMBOL}' AND t.maker_user_id BETWEEN ${LOAD_MAKER_START} AND $((LOAD_MAKER_START + PAIR_COUNT - 1)) AND t.taker_user_id BETWEEN ${LOAD_TAKER_START} AND $((LOAD_TAKER_START + PAIR_COUNT - 1))" \
  "${PAIR_COUNT}"
wait_sql_equals "no open load orders remain" \
  "SELECT count(*) FROM trading_orders WHERE (client_order_id LIKE 'real-load-maker-${RUN_ID}-%' OR client_order_id LIKE 'real-load-taker-${RUN_ID}-%') AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')" \
  "0"

echo "Scenario: deep order book snapshot"
refresh_mark_price "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" "${BTC_PRICE_TICKS}"
for ((i = 0; i < BOOK_DEPTH_LEVELS; i++)); do
  place_order "${DEPTH_USER}" "real-depth-${RUN_ID}-${i}" "SELL" "LIMIT" "GTC" "$((DEPTH_START_PRICE_TICKS + i))" 1 false false >/dev/null
done
wait_sql_equals "deep book open orders" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'real-depth-${RUN_ID}-%' AND status = 'ACCEPTED'" \
  "${BOOK_DEPTH_LEVELS}"
assert_order_book_depth_levels "${BOOK_DEPTH_LEVELS}"

echo "Scenario: funding rate publish and settlement"
publish_price_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" 920001 "${BTC_PRICE_TICKS}"
wait_sql_nonzero "funding predicted rate" \
  "SELECT count(*) FROM funding_rate_ticks WHERE symbol = '${BTC_SYMBOL}'"
psql_exec <<SQL >/dev/null
INSERT INTO funding_rate_ticks (
    symbol, sequence, funding_time, funding_interval_hours, premium_rate_ppm,
    interest_rate_ppm, funding_rate_ppm, status, event_time
) VALUES (
    '${BTC_SYMBOL}', 9000001, now() - interval '1 second', 8, 1000, 0, 1000, 'PREDICTED', now()
) ON CONFLICT (symbol, sequence) DO NOTHING;
SQL
wait_sql_nonzero "completed funding settlement" \
  "SELECT count(*) FROM funding_settlements WHERE symbol = '${BTC_SYMBOL}' AND funding_rate_ppm = 1000 AND status = 'COMPLETED' AND position_count > 0"
wait_sql_nonzero "funding payments written" \
  "SELECT count(*) FROM funding_payments p JOIN funding_settlements s ON s.settlement_id = p.settlement_id WHERE s.symbol = '${BTC_SYMBOL}' AND s.funding_rate_ppm = 1000"
wait_sql_equals "funding settlement totals match payments" \
  "SELECT count(*) FROM funding_settlements s WHERE s.symbol = '${BTC_SYMBOL}' AND s.funding_rate_ppm = 1000 AND s.total_long_payment_units = (SELECT COALESCE(sum(amount_units), 0) FROM funding_payments p WHERE p.settlement_id = s.settlement_id AND p.signed_quantity_steps > 0) AND s.total_short_payment_units = (SELECT COALESCE(sum(amount_units), 0) FROM funding_payments p WHERE p.settlement_id = s.settlement_id AND p.signed_quantity_steps < 0)" \
  "1"

echo "Scenario: margin top-up recovers ETH account risk"
topup_entry_price_ticks="$(latest_mark_price_ticks "${ETH_SYMBOL}" "${ETH_TICK_UNITS}")"
if ((topup_entry_price_ticks <= 0)); then
  topup_entry_price_ticks="${ETH_PRICE_TICKS}"
fi
refresh_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" "${topup_entry_price_ticks}"
topup_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_MAKER_USER}" "real-topup-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${topup_entry_price_ticks}" "${TOPUP_QTY}" false false)"
wait_order_result "${topup_maker_order}" "SUCCESS"
topup_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "real-topup-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${topup_entry_price_ticks}" "${TOPUP_QTY}" false false)"
wait_order_state "${topup_order}" "FILLED" "${TOPUP_QTY}" "0"
wait_position_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "${TOPUP_QTY}" "${topup_entry_price_ticks}"
wait_sql_nonzero "top-up risk snapshot after open" \
  "SELECT count(*) FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' AND maintenance_margin_units > 0 AND margin_ratio_ppm > 0"
topup_pre_margin_ratio_ppm="$(query_value "SELECT margin_ratio_ppm FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1")"
topup_recovered_margin_ratio_ppm="$(query_value "SELECT maintenance_margin_units * 1000000 / NULLIF(equity_units + 1000000000, 0) FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1")"
topup_warning_threshold_ppm=$(((topup_pre_margin_ratio_ppm + topup_recovered_margin_ratio_ppm) / 2))
if ((topup_warning_threshold_ppm <= topup_recovered_margin_ratio_ppm || topup_warning_threshold_ppm >= topup_pre_margin_ratio_ppm)); then
  echo "Unable to derive top-up warning threshold: pre=${topup_pre_margin_ratio_ppm}, recovered=${topup_recovered_margin_ratio_ppm}, threshold=${topup_warning_threshold_ppm}" >&2
  exit 1
fi
update_risk_runtime_config "${topup_warning_threshold_ppm}" "${DEFAULT_LIQUIDATION_MARGIN_RATIO_PPM}"
wait_sql_equals "top-up user reaches warning risk" \
  "SELECT COALESCE((SELECT status FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC LIMIT 1), '')" \
  "WARNING"
adjust_balance "${TOPUP_USER}" 1000000000 "real-config-topup-recovery-${RUN_ID}"
wait_sql_equals "top-up user recovers to normal risk" \
  "SELECT COALESCE((SELECT status FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC LIMIT 1), '')" \
  "NORMAL"
update_risk_runtime_config "${DEFAULT_WARNING_MARGIN_RATIO_PPM}" "${DEFAULT_LIQUIDATION_MARGIN_RATIO_PPM}"
topup_close_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_MAKER_USER}" "real-topup-close-maker-${RUN_ID}" "BUY" "LIMIT" "GTC" "${topup_entry_price_ticks}" "${TOPUP_QTY}" true false)"
wait_order_result "${topup_close_maker_order}" "SUCCESS"
topup_close_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "real-topup-close-${RUN_ID}" "SELL" "LIMIT" "IOC" "${topup_entry_price_ticks}" "${TOPUP_QTY}" true false)"
wait_order_state "${topup_close_order}" "FILLED" "${TOPUP_QTY}" "0"
wait_position_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "0" "0"
wait_position_symbol "${ETH_SYMBOL}" "${TOPUP_MAKER_USER}" "0" "0"
wait_sql_equals "top-up user latest risk position is flat" \
  "SELECT signed_quantity_steps || ':' || notional_units || ':' || maintenance_margin_units FROM risk_position_snapshots WHERE user_id = ${TOPUP_USER} AND symbol = '${ETH_SYMBOL}' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1" \
  "0:0:0"

echo "Scenario: liquidation links risk, liquidation, matching, account, and insurance"
adjust_insurance_fund 100000000000 "real-config-insurance-seed-${RUN_ID}"
liq_entry_price_ticks="$(latest_mark_price_ticks "${ETH_SYMBOL}" "${ETH_TICK_UNITS}")"
if ((liq_entry_price_ticks <= 0)); then
  liq_entry_price_ticks="${ETH_PRICE_TICKS}"
fi
refresh_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" "${liq_entry_price_ticks}"
liq_open_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${LIQ_OPEN_MAKER_USER}" "real-liq-open-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${liq_entry_price_ticks}" "${LIQ_QTY}" false false)"
wait_order_result "${liq_open_maker_order}" "SUCCESS"
liq_open_order="$(place_order_symbol "${ETH_SYMBOL}" "${LIQ_USER}" "real-liq-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${liq_entry_price_ticks}" "${LIQ_QTY}" false false)"
wait_order_state "${liq_open_order}" "FILLED" "${LIQ_QTY}" "0"
wait_position_symbol "${ETH_SYMBOL}" "${LIQ_USER}" "${LIQ_QTY}" "${liq_entry_price_ticks}"
wait_sql_nonzero "liquidation risk snapshot after open" \
  "SELECT count(*) FROM risk_account_snapshots WHERE user_id = ${LIQ_USER} AND settle_asset = 'USDT' AND maintenance_margin_units > 0 AND margin_ratio_ppm > 1"
liq_pre_margin_ratio_ppm="$(query_value "SELECT margin_ratio_ppm FROM risk_account_snapshots WHERE user_id = ${LIQ_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1")"
liq_liquidation_threshold_ppm=$((liq_pre_margin_ratio_ppm * 80 / 100))
if ((liq_liquidation_threshold_ppm <= 1)); then
  echo "Unable to derive liquidation threshold from margin ratio ${liq_pre_margin_ratio_ppm}" >&2
  exit 1
fi
liq_warning_threshold_ppm=$((liq_liquidation_threshold_ppm - 1))
liq_close_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${LIQ_CLOSE_MAKER_USER}" "real-liq-close-maker-${RUN_ID}" "BUY" "LIMIT" "GTC" "${liq_entry_price_ticks}" "${LIQ_QTY}" false false)"
wait_order_result "${liq_close_maker_order}" "SUCCESS"
update_liquidation_runtime_config "${liq_liquidation_threshold_ppm}"
update_risk_runtime_config "${liq_warning_threshold_ppm}" "${liq_liquidation_threshold_ppm}"
wait_sql_nonzero "liquidation candidate created" \
  "SELECT count(*) FROM risk_liquidation_candidates WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}'"
wait_sql_nonzero "liquidation order submitted" \
  "SELECT count(*) FROM liquidation_orders WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}' AND order_id > 0 AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED')"
liq_order_id="$(query_value "SELECT order_id FROM liquidation_orders WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}' AND order_id > 0 AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED') ORDER BY created_at DESC LIMIT 1")"
wait_order_state "${liq_order_id}" "FILLED" "${LIQ_QTY}" "0"
wait_sql_equals "liquidation audit filled" \
  "SELECT status FROM liquidation_orders WHERE order_id = ${liq_order_id}" \
  "FILLED"
wait_position_symbol "${ETH_SYMBOL}" "${LIQ_USER}" "0" "0"
wait_sql_equals "liquidation candidate completed" \
  "SELECT c.status FROM risk_liquidation_candidates c JOIN liquidation_orders l ON l.candidate_id = c.candidate_id WHERE l.order_id = ${liq_order_id}" \
  "COMPLETED"
update_risk_runtime_config "${DEFAULT_WARNING_MARGIN_RATIO_PPM}" "${DEFAULT_LIQUIDATION_MARGIN_RATIO_PPM}"
update_liquidation_runtime_config "${DEFAULT_FULL_CLOSE_MARGIN_RATIO_PPM}"
if [[ "$(query_value "SELECT count(*) FROM insurance_deficit_coverages WHERE asset = 'USDT'")" == "0" ]]; then
  psql_exec <<SQL >/dev/null
INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
VALUES (${LIQ_USER}, 'USDT', 0, 0, now())
ON CONFLICT (user_id, asset) DO UPDATE SET available_units = 0, locked_units = 0, updated_at = now();
INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
VALUES (${LIQ_USER}, 'USDT', 1000000, now() - interval '20 seconds')
ON CONFLICT (user_id, asset) DO UPDATE SET deficit_units = 1000000, updated_at = now() - interval '20 seconds';
SQL
fi
wait_sql_nonzero "insurance covered liquidation or synthetic deficit" \
  "SELECT count(*) FROM insurance_deficit_coverages WHERE asset = 'USDT'"

echo "Scenario: insurance and ADL residual deficit handling"
psql_exec <<SQL >/dev/null
INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
VALUES (${INSURANCE_DEFICIT_USER}, 'USDT', 0, 0, now())
ON CONFLICT (user_id, asset) DO UPDATE SET available_units = 0, locked_units = 0, updated_at = now();
INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
VALUES (${INSURANCE_DEFICIT_USER}, 'USDT', 1000000, now() - interval '20 seconds')
ON CONFLICT (user_id, asset) DO UPDATE SET deficit_units = 1000000, updated_at = now() - interval '20 seconds';
SQL
wait_sql_equals "insurance covers synthetic deficit" \
  "SELECT deficit_units FROM account_deficits WHERE user_id = ${INSURANCE_DEFICIT_USER} AND asset = 'USDT'" \
  "0"
fund_balance="$(query_value "SELECT COALESCE(balance_units, 0) FROM insurance_fund_balances WHERE asset = 'USDT'")"
if [[ "${fund_balance}" != "0" ]]; then
  adjust_insurance_fund "-${fund_balance}" "real-config-insurance-drain-${RUN_ID}"
fi
wait_sql_equals "insurance fund drained before ADL" \
  "SELECT COALESCE((SELECT balance_units FROM insurance_fund_balances WHERE asset = 'USDT'), 0)" \
  "0"
adl_latest_mark_ticks="$(latest_mark_price_ticks "${ETH_SYMBOL}" "${ETH_TICK_UNITS}")"
if ((adl_latest_mark_ticks <= 0)); then
  echo "No latest ETH mark price available for ADL setup" >&2
  exit 1
fi
adl_entry_price_ticks=$((adl_latest_mark_ticks * 98 / 100))
adl_profit_mark_ticks=$((adl_entry_price_ticks * 122 / 100))
adl_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${ADL_OPEN_MAKER_USER}" "real-adl-open-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${adl_entry_price_ticks}" "${LIQ_QTY}" false false)"
wait_order_result "${adl_maker_order}" "SUCCESS"
adl_target_order="$(place_order_symbol "${ETH_SYMBOL}" "${ADL_TARGET_USER}" "real-adl-target-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${adl_entry_price_ticks}" "${LIQ_QTY}" false false)"
wait_order_state "${adl_target_order}" "FILLED" "${LIQ_QTY}" "0"
publish_price_inputs "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 940002 "${adl_profit_mark_ticks}"
publish_mark_price_event "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" "${adl_profit_mark_ticks}"
psql_exec <<SQL >/dev/null
INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
VALUES (${ADL_DEFICIT_USER}, 'USDT', 0, 0, now())
ON CONFLICT (user_id, asset) DO UPDATE SET available_units = 0, locked_units = 0, updated_at = now();
INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
VALUES (${ADL_DEFICIT_USER}, 'USDT', 1000000, now() - interval '20 seconds')
ON CONFLICT (user_id, asset) DO UPDATE SET deficit_units = 1000000, updated_at = now() - interval '20 seconds';
SQL
wait_sql_nonzero "ADL event created" \
  "SELECT count(*) FROM adl_events WHERE deficit_user_id = ${ADL_DEFICIT_USER} AND covered_units > 0 AND closed_quantity_steps > 0"
wait_sql_nonzero "ADL target position reduced" \
  "SELECT count(*) FROM adl_events e JOIN account_positions p ON p.user_id = e.target_user_id AND p.symbol = e.symbol WHERE e.deficit_user_id = ${ADL_DEFICIT_USER} AND abs(p.signed_quantity_steps) < ${LIQ_QTY}"
wait_sql_equals "ADL reduced residual deficit" \
  "SELECT deficit_units FROM account_deficits WHERE user_id = ${ADL_DEFICIT_USER} AND asset = 'USDT'" \
  "0"

run_market_maker_provider_smoke

echo "Asserting websocket and accounting invariants"
wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
wait_consumer_group_lag_zero "surprising-risk-v1" "surprising.account.position.events.v1"
wait_sql_equals "risk outbox drained before websocket risk assertions" \
  "SELECT count(*) FROM risk_outbox_events WHERE published_at IS NULL" \
  "0"
sleep 5
assert_ws_depth "${WS_DEPTH_LOG}"
assert_ws_private "${WS_FULL_TAKER_LOG}" "${FULL_TAKER_USER}" "0" "0"
assert_ws_private "${WS_PARTIAL_MAKER_LOG}" "${PARTIAL_MAKER_USER}" "-${PARTIAL_TAKER_QTY}" "-${PARTIAL_TAKER_QTY}"
assert_ws_private "${WS_CANCEL_LOG}" "${CANCEL_USER}" "0"
assert_ws_private "${WS_ISOLATED_LOG}" "${ISOLATED_USER}" "${ISOLATED_QTY}" "${ISOLATED_QTY}"
assert_ws_position_side "${WS_POSITION_MODE_LOG}" "${POSITION_MODE_USER}" "LONG" "${POSITION_MODE_QTY}"
assert_ws_position_side "${WS_POSITION_MODE_LOG}" "${POSITION_MODE_USER}" "SHORT" "-${POSITION_MODE_QTY}"
assert_ws_public_channel "${WS_MARK_LOG}" "mark" "${BTC_SYMBOL}"
assert_ws_public_channel "${WS_FUNDING_LOG}" "funding" "${BTC_SYMBOL}"
assert_no_negative_balances
wait_sql_equals "unpublished trading outbox drained" \
  "SELECT count(*) FROM trading_outbox_events WHERE published_at IS NULL AND attempts > 0" \
  "0"
wait_sql_equals "unpublished account outbox drained" \
  "SELECT count(*) FROM account_outbox_events WHERE published_at IS NULL AND attempts > 0" \
  "0"
wait_sql_equals "unpublished risk outbox drained" \
  "SELECT count(*) FROM risk_outbox_events WHERE published_at IS NULL AND attempts > 0" \
  "0"

touch "${WS_STOP_FILE}"

echo "Full-stack real-config smoke passed"
echo "logs=${TMP_DIR}"
echo "pairs=${PAIR_COUNT} depthLevels=${BOOK_DEPTH_LEVELS} loadTotalQuantity=${LOAD_TOTAL_QTY}"
echo "marketMakerReferenceMarket=${MM_REFERENCE_MARKET_ENABLED} marketMakerReferenceMarketWebSocket=${MM_REFERENCE_MARKET_WEBSOCKET_ENABLED}"
