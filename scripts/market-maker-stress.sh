#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="surprising_exchange"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
START_INFRA="${START_INFRA:-false}"
RESET_STATE="${RESET_STATE:-true}"
RESET_KAFKA_MODE="${RESET_KAFKA_MODE:-recreate}"
START_PROVIDERS="${START_PROVIDERS:-true}"
STOP_PROVIDERS="${STOP_PROVIDERS:-true}"
BUILD_SERVICES="${BUILD_SERVICES:-true}"
KEEP_TMP="${KEEP_TMP:-true}"
EXTRA_MATCHING_NODES="${EXTRA_MATCHING_NODES:-0}"
EXTRA_ACCOUNT_NODES="${EXTRA_ACCOUNT_NODES:-0}"
EXTRA_WEBSOCKET_NODES="${EXTRA_WEBSOCKET_NODES:-0}"
MATCHING_CONSUMERS_PER_NODE="${MATCHING_CONSUMERS_PER_NODE:-1}"
ACCOUNT_CONSUMERS_PER_NODE="${ACCOUNT_CONSUMERS_PER_NODE:-2}"
ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT="${ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT:-false}"
MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK="${MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK:-false}"
MM_ACCOUNT_COUNT="${MM_ACCOUNT_COUNT:-4}"
MM_DEPTH_LEVELS="${MM_DEPTH_LEVELS:-20}"
MM_LEVEL_QUANTITY_STEPS="${MM_LEVEL_QUANTITY_STEPS:-50}"
MM_REFRESH_LEVELS="${MM_REFRESH_LEVELS:-5}"
MM_REFRESH_QUANTITY_STEPS="${MM_REFRESH_QUANTITY_STEPS:-20}"
MM_REFRESH_CYCLES="${MM_REFRESH_CYCLES:-1}"
MM_REFRESH_INTERVAL_SECONDS="${MM_REFRESH_INTERVAL_SECONDS:-0}"
MAKER_BATCH_SIZE="${MAKER_BATCH_SIZE:-5}"
MAKER_LOAD_CONCURRENCY="${MAKER_LOAD_CONCURRENCY:-8}"
TAKER_ORDER_COUNT="${TAKER_ORDER_COUNT:-1000}"
TAKER_QUANTITY_STEPS="${TAKER_QUANTITY_STEPS:-2}"
LOAD_CONCURRENCY="${LOAD_CONCURRENCY:-64}"
ORDER_HTTP_RETRIES="${ORDER_HTTP_RETRIES:-3}"
ORDER_HTTP_RETRY_DELAY_SECONDS="${ORDER_HTTP_RETRY_DELAY_SECONDS:-1}"
ORDER_HTTP_RETRY_MAX_SECONDS="${ORDER_HTTP_RETRY_MAX_SECONDS:-45}"
REPORT_FILE="${REPORT_FILE:-${ROOT_DIR}/docs/market-maker-stress-report.md}"
TMP_DIR="$(mktemp -d /tmp/surprising-mm-stress.XXXXXX)"
WS_STOP_FILE="${TMP_DIR}/ws.stop"
MARK_STOP_FILE="${TMP_DIR}/mark.stop"
KAFKA_LAG_STOP_FILE="${TMP_DIR}/kafka-lag.stop"
KAFKA_LAG_LOG="${TMP_DIR}/kafka-lag.log"
KAFKA_LAG_INTERVAL_SECONDS="${KAFKA_LAG_INTERVAL_SECONDS:-1}"
WS_CAPTURE_TIMEOUT="${WS_CAPTURE_TIMEOUT:-900}"
WS_FANOUT_USER_COUNT="${WS_FANOUT_USER_COUNT:-1}"
WEBSOCKET_PORT="${WEBSOCKET_PORT:-9097}"
ORDER_HIKARI_MAX_POOL_SIZE="${ORDER_HIKARI_MAX_POOL_SIZE:-30}"
ORDER_HIKARI_CONNECTION_TIMEOUT_MS="${ORDER_HIKARI_CONNECTION_TIMEOUT_MS:-3000}"
MATCHING_HIKARI_MAX_POOL_SIZE="${MATCHING_HIKARI_MAX_POOL_SIZE:-20}"
MATCHING_HIKARI_CONNECTION_TIMEOUT_MS="${MATCHING_HIKARI_CONNECTION_TIMEOUT_MS:-3000}"
ACCOUNT_HIKARI_MAX_POOL_SIZE="${ACCOUNT_HIKARI_MAX_POOL_SIZE:-20}"
ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS="${ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS:-3000}"
GATEWAY_HIKARI_MAX_POOL_SIZE="${GATEWAY_HIKARI_MAX_POOL_SIZE:-10}"
GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS="${GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS:-3000}"
TAKER_FILL_WAIT_SECONDS="${TAKER_FILL_WAIT_SECONDS:-300}"
TAKER_TRADE_WAIT_SECONDS="${TAKER_TRADE_WAIT_SECONDS:-300}"
ACCOUNT_SETTLEMENT_WAIT_SECONDS="${ACCOUNT_SETTLEMENT_WAIT_SECONDS:-300}"
INFRA_MODE="${INFRA_MODE:-local}"

BTC_SYMBOL="BTC-USDT"
ETH_SYMBOL="ETH-USDT"
BTC_TICK_UNITS=10000000
ETH_TICK_UNITS=1000000
BTC_PRICE_TICKS=600000
ETH_PRICE_TICKS=300000

MM_USER_START=$((8200000000 + RUN_SEQ * 1000))
TAKER_USER_START=$((8300000000 + RUN_SEQ * 1000))

PROVIDER_NAMES=()
PROVIDER_PIDS=()
PIDS=()
RUN_FAILURES=0
FAILURE_SCENARIO_SUMMARY="- Node failure scenario：none"

add_failure_summary() {
  local line="$1"
  if [[ "${FAILURE_SCENARIO_SUMMARY}" == "- Node failure scenario：none" ]]; then
    FAILURE_SCENARIO_SUMMARY="${line}"
  else
    FAILURE_SCENARIO_SUMMARY="${FAILURE_SCENARIO_SUMMARY}"$'\n'"${line}"
  fi
}

cleanup() {
  touch "${WS_STOP_FILE}" >/dev/null 2>&1 || true
  touch "${MARK_STOP_FILE}" >/dev/null 2>&1 || true
  touch "${KAFKA_LAG_STOP_FILE}" >/dev/null 2>&1 || true
  if [[ "${STOP_PROVIDERS}" == "true" ]]; then
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
  fi
  if ((${#PIDS[@]})); then
    local pid
    for pid in "${PIDS[@]}"; do
      wait "${pid}" >/dev/null 2>&1 || true
    done
  fi
  if [[ "${KEEP_TMP}" == "true" ]]; then
    echo "Keeping market-maker stress logs in ${TMP_DIR}" >&2
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

compose_exec() {
  local service="$1"
  shift
  if [[ "${INFRA_MODE}" == "local" ]]; then
    if [[ "${service}" == "kafka" && "$#" -gt 0 && "$1" == kafka-*.sh ]]; then
      local kafka_cmd="${1%.sh}"
      shift
      "${kafka_cmd}" "$@"
      return
    fi
    "$@"
    return
  fi
  if [[ "${service}" == "kafka" && "$#" -gt 0 && "$1" == kafka-*.sh ]]; then
    set -- "/opt/kafka/bin/$1" "${@:2}"
  fi
  docker exec -i "surprising-ex-${service}" "$@"
}

start_infra() {
  if [[ "${INFRA_MODE}" == "local" ]]; then
    if command -v brew >/dev/null 2>&1; then
      brew services start postgresql@18 >/dev/null 2>&1 || true
      brew services start kafka >/dev/null 2>&1 || true
      brew services start redis >/dev/null 2>&1 || true
    fi
    return
  fi
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "${ROOT_DIR}/docker-compose.yml" up -d postgres kafka >/dev/null
    return
  fi
  if docker container inspect surprising-ex-postgres >/dev/null 2>&1; then
    docker start surprising-ex-postgres >/dev/null
  else
    docker run -d --name surprising-ex-postgres \
      -e POSTGRES_DB="${DB_NAME}" \
      -e POSTGRES_USER="${DB_USER}" \
      -e POSTGRES_PASSWORD="${DB_PASSWORD}" \
      -p 5432:5432 \
      -v surprising-ex-postgres:/var/lib/postgresql/data \
      -v "${ROOT_DIR}/init.sql:/docker-entrypoint-initdb.d/init.sql:ro" \
      postgres:16 >/dev/null
  fi
  if docker container inspect surprising-ex-kafka >/dev/null 2>&1; then
    docker start surprising-ex-kafka >/dev/null
  else
    docker run -d --name surprising-ex-kafka \
      -e KAFKA_NODE_ID=1 \
      -e KAFKA_PROCESS_ROLES=broker,controller \
      -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
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
      apache/kafka:3.7.0 >/dev/null
  fi
}

psql_exec() {
  compose_exec postgres env PGPASSWORD="${DB_PASSWORD}" \
    psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 "$@"
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

wait_http() {
  local name="$1"
  local port="$2"
  local log_file="${TMP_DIR}/${name}.log"
  local deadline=$((SECONDS + 180))
  until curl -fsS "http://localhost:${port}/actuator/health" | grep -q "UP"; do
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for ${name} health on port ${port}" >&2
      tail -n 120 "${log_file}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

wait_sql_equals() {
  local description="$1"
  local sql="$2"
  local expected="$3"
  local timeout_seconds="${4:-240}"
  local deadline=$((SECONDS + timeout_seconds))
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

reset_database() {
  psql_exec <<'SQL' >/dev/null
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
SQL
  psql_exec -f - < "${ROOT_DIR}/init.sql" >/dev/null
}

delete_surprising_topics() {
  local topics
  topics="$(compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list | awk '/^surprising[.-]/ {print}')"
  if [[ -z "${topics}" ]]; then
    return
  fi
  while IFS= read -r topic; do
    [[ -z "${topic}" ]] && continue
    compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --delete --if-exists --topic "${topic}" >/dev/null 2>&1 || true
  done <<<"${topics}"
  local deadline=$((SECONDS + 90))
  while true; do
    topics="$(compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list | awk '/^surprising[.-]/ {print}')"
    if [[ -z "${topics}" ]]; then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for Kafka topics to be deleted" >&2
      echo "${topics}" >&2
      if [[ "${INFRA_MODE}" == "local" ]]; then
        exit 1
      fi
      recreate_kafka
      return
    fi
    sleep 1
  done
}

recreate_kafka() {
  if [[ "${INFRA_MODE}" == "local" ]]; then
    delete_surprising_topics
    return
  fi
  docker rm -f surprising-ex-kafka >/dev/null 2>&1 || true
  docker volume rm surprising-ex-kafka >/dev/null 2>&1 || true
  docker run -d --name surprising-ex-kafka \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
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
    apache/kafka:3.7.0 >/dev/null
  wait_until "Kafka broker after recreate" 120 compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null
}

create_topics() {
  mkdir -p "${TMP_DIR}/bin"
cat > "${TMP_DIR}/bin/kafka-topics.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${INFRA_MODE:-}" == "local" ]]; then
  exec kafka-topics "$@"
fi
exec docker exec -i surprising-ex-kafka /opt/kafka/bin/kafka-topics.sh "$@"
EOF
  chmod +x "${TMP_DIR}/bin/kafka-topics.sh"
  PATH="${TMP_DIR}/bin:${PATH}" "${ROOT_DIR}/scripts/create-topics.sh" >/dev/null
}

seed_mark_prices() {
  local sequence
  local btc_price
  local btc_bid
  local btc_ask
  local btc_units
  local eth_price
  local eth_bid
  local eth_ask
  local eth_units
  sequence=$((($(date +%s%N) / 1000000) % 2000000000))
  btc_price="$(decimal_price "${BTC_PRICE_TICKS}" "${BTC_TICK_UNITS}")"
  btc_bid="$(decimal_price "$((BTC_PRICE_TICKS - 1))" "${BTC_TICK_UNITS}")"
  btc_ask="$(decimal_price "$((BTC_PRICE_TICKS + 1))" "${BTC_TICK_UNITS}")"
  btc_units=$((BTC_PRICE_TICKS * BTC_TICK_UNITS))
  eth_price="$(decimal_price "${ETH_PRICE_TICKS}" "${ETH_TICK_UNITS}")"
  eth_bid="$(decimal_price "$((ETH_PRICE_TICKS - 1))" "${ETH_TICK_UNITS}")"
  eth_ask="$(decimal_price "$((ETH_PRICE_TICKS + 1))" "${ETH_TICK_UNITS}")"
  eth_units=$((ETH_PRICE_TICKS * ETH_TICK_UNITS))
  psql_exec <<SQL >/dev/null
INSERT INTO price_index_ticks (
    symbol, sequence, index_price, status, component_count, valid_component_count,
    total_configured_weight, event_time
) VALUES
    ('${BTC_SYMBOL}', ${sequence}, ${btc_price}, 'HEALTHY', 5, 5, 5.000000000000000000, now()),
    ('${ETH_SYMBOL}', ${sequence}, ${eth_price}, 'HEALTHY', 5, 5, 5.000000000000000000, now())
ON CONFLICT (symbol, sequence) DO NOTHING;

INSERT INTO price_mark_ticks (
    symbol, sequence, mark_price, mark_price_units, index_price, price1, price2,
    last_trade_price, best_bid_price, best_ask_price, funding_rate, next_funding_time,
    time_until_funding_seconds, basis_average, basis_window_seconds, clamp_low, clamp_high,
    status, event_time
) VALUES
    (
        '${BTC_SYMBOL}', ${sequence}, ${btc_price}, ${btc_units}, ${btc_price}, ${btc_price}, ${btc_price},
        ${btc_price}, ${btc_bid}, ${btc_ask}, 0.000000000000000000, now() + interval '8 hours',
        28800, 0.000000000000000000, 60,
        (${btc_price} * 0.970000000000000000),
        (${btc_price} * 1.030000000000000000),
        'HEALTHY', now()
    ),
    (
        '${ETH_SYMBOL}', ${sequence}, ${eth_price}, ${eth_units}, ${eth_price}, ${eth_price}, ${eth_price},
        ${eth_price}, ${eth_bid}, ${eth_ask}, 0.000000000000000000, now() + interval '8 hours',
        28800, 0.000000000000000000, 60,
        (${eth_price} * 0.970000000000000000),
        (${eth_price} * 1.030000000000000000),
        'HEALTHY', now()
    )
ON CONFLICT (symbol, sequence) DO NOTHING;
SQL
}

start_mark_refresher() {
  (
    while [[ ! -f "${MARK_STOP_FILE}" ]]; do
      seed_mark_prices >>"${TMP_DIR}/mark-refresher.log" 2>&1 || true
      sleep 0.5
    done
  ) &
  PIDS+=("$!")
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

package_services() {
  if [[ "${BUILD_SERVICES}" != "true" ]]; then
    return
  fi
  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
    mvn -q -pl :surprising-order-provider,:surprising-matching-provider,:surprising-account-provider,:surprising-websocket-provider,:surprising-gateway-provider \
    -am -DskipTests package
}

boot_jar() {
  local module_path="$1"
  local artifact="$2"
  local jar
  jar="$(find "${ROOT_DIR}/${module_path}/target" -name "${artifact}-*-exec.jar" -type f | sort | tail -n 1)"
  if [[ -z "${jar}" ]]; then
    echo "Boot jar not found for ${artifact}; run with BUILD_SERVICES=true" >&2
    exit 1
  fi
  echo "${jar}"
}

register_provider_pid() {
  local name="$1"
  local pid="$2"
  local index
  index="$(provider_index "${name}")"
  if [[ -n "${index}" ]]; then
    PROVIDER_PIDS[$index]="${pid}"
    return
  fi
  PROVIDER_NAMES+=("$1")
  PROVIDER_PIDS+=("$2")
}

provider_index() {
  local name="$1"
  local i
  for ((i = 0; i < ${#PROVIDER_NAMES[@]}; i++)); do
    if [[ "${PROVIDER_NAMES[$i]}" == "${name}" ]]; then
      echo "${i}"
      return
    fi
  done
}

stop_provider() {
  local name="$1"
  local index
  local pid
  index="$(provider_index "${name}")"
  if [[ -z "${index}" ]]; then
    echo "Provider ${name} is not managed by this script" >&2
    exit 1
  fi
  pid="${PROVIDER_PIDS[$index]}"
  if [[ -z "${pid}" ]]; then
    return
  fi
  echo "Stopping ${name} provider"
  kill "${pid}" >/dev/null 2>&1 || true
  local deadline=$((SECONDS + 30))
  while kill -0 "${pid}" >/dev/null 2>&1; do
    if ((SECONDS >= deadline)); then
      kill -9 "${pid}" >/dev/null 2>&1 || true
      break
    fi
    sleep 1
  done
  wait "${pid}" >/dev/null 2>&1 || true
  PROVIDER_PIDS[$index]=""
}

start_provider() {
  local name="$1"
  local port="$2"
  local module_path="$3"
  local artifact="$4"
  shift 4
  local jar
  jar="$(boot_jar "${module_path}" "${artifact}")"
  local log_file="${TMP_DIR}/${name}.log"
  local java_args=()
  local app_args=("--server.port=${port}" "$@")
  if [[ "${name}" == matching* ]]; then
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
  echo "Starting ${name} provider on port ${port}"
  (
    cd "${ROOT_DIR}"
    if ((${#java_args[@]})); then
      exec java "${java_args[@]}" -jar "${jar}" "${app_args[@]}"
    fi
    exec java -jar "${jar}" "${app_args[@]}"
  ) >"${log_file}" 2>&1 &
  register_provider_pid "${name}" "$!"
  wait_http "${name}" "${port}"
}

matching_node_number() {
  local name="$1"
  if [[ "${name}" == "matching" ]]; then
    echo 1
    return
  fi
  if [[ "${name}" =~ ^matching-([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi
  echo "not a matching provider: ${name}" >&2
  exit 1
}

matching_provider_name_for_node() {
  local node="$1"
  if ((node == 1)); then
    echo "matching"
  else
    echo "matching-${node}"
  fi
}

matching_provider_port() {
  local name="$1"
  local node
  node="$(matching_node_number "${name}")"
  echo $((9085 + (node - 1) * 100))
}

start_matching_provider() {
  local name="$1"
  local node
  node="$(matching_node_number "${name}")"
  start_provider "${name}" "$(matching_provider_port "${name}")" \
    "surprising-trading/surprising-matching-provider" "surprising-matching-provider" \
    "--spring.datasource.hikari.maximum-pool-size=${MATCHING_HIKARI_MAX_POOL_SIZE}" \
    "--spring.datasource.hikari.connection-timeout=${MATCHING_HIKARI_CONNECTION_TIMEOUT_MS}" \
    "--surprising.trading.matching.kafka.client-id=mm-stress-${RUN_ID}-matching-${node}" \
    "--surprising.trading.matching.kafka.concurrency=${MATCHING_CONSUMERS_PER_NODE}"
}

matching_provider_for_client_id() {
  local client_id="$1"
  local prefix="mm-stress-${RUN_ID}-matching-"
  if [[ "${client_id}" != "${prefix}"* ]]; then
    return 1
  fi
  local rest="${client_id#${prefix}}"
  local node="${rest%%-*}"
  if [[ ! "${node}" =~ ^[0-9]+$ ]]; then
    return 1
  fi
  matching_provider_name_for_node "${node}"
}

restart_dead_matching_providers() {
  local i
  for ((i = 0; i < ${#PROVIDER_NAMES[@]}; i++)); do
    local name="${PROVIDER_NAMES[$i]}"
    local pid="${PROVIDER_PIDS[$i]}"
    if [[ "${name}" != matching* || -z "${pid}" ]]; then
      continue
    fi
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      wait "${pid}" >/dev/null 2>&1 || true
      PROVIDER_PIDS[$i]=""
      echo "Restarting exited ${name} provider"
      start_matching_provider "${name}"
    fi
  done
}

wait_matching_members_with_restarts() {
  local expected="$1"
  local deadline=$((SECONDS + 180))
  local actual
  while true; do
    restart_dead_matching_providers
    actual="$(consumer_member_count "surprising-matching-v1" "surprising.perp.order.commands.v1" || echo 0)"
    if ((actual >= expected)); then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for matching group recovery: expected >= ${expected}, got ${actual}" >&2
      compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
        --describe --group "surprising-matching-v1" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

start_extra_nodes() {
  local i
  for ((i = 1; i <= EXTRA_MATCHING_NODES; i++)); do
    start_matching_provider "matching-$((i + 1))"
  done
  for ((i = 1; i <= EXTRA_ACCOUNT_NODES; i++)); do
    start_provider "account-$((i + 1))" "$((9086 + i * 100))" \
      "surprising-account/surprising-account-provider" "surprising-account-provider" \
      "--spring.datasource.hikari.maximum-pool-size=${ACCOUNT_HIKARI_MAX_POOL_SIZE}" \
      "--spring.datasource.hikari.connection-timeout=${ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS}" \
      "--surprising.account.kafka.client-id=mm-stress-${RUN_ID}-account-$((i + 1))" \
      "--surprising.account.kafka.concurrency=${ACCOUNT_CONSUMERS_PER_NODE}"
  done
  for ((i = 1; i <= EXTRA_WEBSOCKET_NODES; i++)); do
    start_provider "websocket-$((i + 1))" "$((WEBSOCKET_PORT + i * 100))" \
      "surprising-websocket/surprising-websocket-provider" "surprising-websocket-provider" \
      "--surprising.websocket.kafka.group-id=surprising-websocket-mm-${RUN_ID}-$((i + 1))"
  done
}

adjust_balance() {
  local user_id="$1"
  local reference_id="$2"
  curl -fsS -X POST "http://localhost:9086/api/v1/accounts/admin/balance-adjustments" \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": ${user_id},
      \"asset\": \"USDT\",
      \"amountUnits\": 100000000000000,
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"MARKET_MAKER_STRESS_DEPOSIT\"
    }" >/dev/null
}

fund_stress_accounts() {
  psql_exec <<SQL >/dev/null
WITH requested AS (
    SELECT (${MM_USER_START} + gs)::bigint AS user_id,
           ('mm-stress-${RUN_ID}-mm-' || gs)::text AS reference_id
      FROM generate_series(0, ${MM_ACCOUNT_COUNT} - 1) AS gs
    UNION ALL
    SELECT (${TAKER_USER_START} + gs)::bigint AS user_id,
           ('mm-stress-${RUN_ID}-user-' || gs)::text AS reference_id
      FROM generate_series(0, ${TOTAL_TAKER_ORDER_COUNT} - 1) AS gs
),
counted AS (
    SELECT count(*)::bigint AS row_count FROM requested
),
seq AS (
    INSERT INTO account_sequences (sequence_name, sequence_value, updated_at)
    SELECT 'ledger-entry', row_count, now() FROM counted
    ON CONFLICT (sequence_name) DO UPDATE SET
        sequence_value = account_sequences.sequence_value + EXCLUDED.sequence_value,
        updated_at = now()
    RETURNING sequence_value
),
numbered AS (
    SELECT r.user_id,
           r.reference_id,
           row_number() OVER (ORDER BY r.user_id) AS rn
      FROM requested r
),
ledger_rows AS (
    INSERT INTO account_ledger_entries (
        entry_id, user_id, asset, amount_units, balance_after_units,
        reference_type, reference_id, reason, created_at
    )
    SELECT (seq.sequence_value - counted.row_count + numbered.rn)::bigint,
           numbered.user_id,
           'USDT',
           100000000000000,
           100000000000000,
           'BALANCE_ADJUSTMENT',
           numbered.reference_id,
           'MARKET_MAKER_STRESS_DEPOSIT',
           now()
      FROM numbered
      CROSS JOIN seq
      CROSS JOIN counted
    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
    RETURNING user_id, reference_id
),
balance_rows AS (
    INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
    SELECT user_id, 'USDT', 100000000000000, 0, now()
      FROM ledger_rows
    ON CONFLICT (user_id, asset) DO UPDATE SET
        available_units = account_balances.available_units + EXCLUDED.available_units,
        updated_at = EXCLUDED.updated_at
    RETURNING user_id, asset
)
INSERT INTO account_admin_balance_adjustments (
    reference_key, adjustment_kind, admin_user_id, admin_username, user_id, account_type,
    asset, amount_units, balance_after_units, reference_id, reason, created_at
)
SELECT 'BASIC|' || user_id || '||USDT|' || reference_id,
       'BASIC',
       1,
       'market-maker-stress',
       user_id,
       NULL,
       'USDT',
       100000000000000,
       100000000000000,
       reference_id,
       'MARKET_MAKER_STRESS_DEPOSIT',
       now()
  FROM ledger_rows
ON CONFLICT (reference_key) DO NOTHING;
SQL
}

price_ticks_for() {
  case "$1" in
    "${BTC_SYMBOL}") echo "${BTC_PRICE_TICKS}" ;;
    "${ETH_SYMBOL}") echo "${ETH_PRICE_TICKS}" ;;
    *) echo "unknown symbol $1" >&2; exit 1 ;;
  esac
}

place_order_command() {
  local url="$1"
  local user_id="$2"
  local client_order_id="$3"
  local symbol="$4"
  local side="$5"
  local tif="$6"
  local price_ticks="$7"
  local quantity_steps="$8"
  local trace="$9"
  local extra_header=""
  if [[ "${url}" == *"/gateway/"* ]]; then
    extra_header="-H 'X-User-Id: ${user_id}'"
  fi
  printf "curl --retry %s --retry-delay %s --retry-max-time %s --retry-all-errors -fsS -X POST '%s' -H 'Content-Type: application/json' %s -H 'X-Trace-Id: %s' -d '{\"userId\": %s, \"clientOrderId\": \"%s\", \"symbol\": \"%s\", \"side\": \"%s\", \"orderType\": \"LIMIT\", \"timeInForce\": \"%s\", \"priceTicks\": %s, \"quantitySteps\": %s, \"reduceOnly\": false, \"postOnly\": false}' >/dev/null" \
    "${ORDER_HTTP_RETRIES}" "${ORDER_HTTP_RETRY_DELAY_SECONDS}" "${ORDER_HTTP_RETRY_MAX_SECONDS}" \
    "${url}" "${extra_header}" "${trace}" "${user_id}" "${client_order_id}" "${symbol}" "${side}" "${tif}" "${price_ticks}" "${quantity_steps}"
}

order_payload() {
  local user_id="$1"
  local client_order_id="$2"
  local symbol="$3"
  local side="$4"
  local tif="$5"
  local price_ticks="$6"
  local quantity_steps="$7"
  printf "{\"userId\":%s,\"clientOrderId\":\"%s\",\"symbol\":\"%s\",\"side\":\"%s\",\"orderType\":\"LIMIT\",\"timeInForce\":\"%s\",\"priceTicks\":%s,\"quantitySteps\":%s,\"reduceOnly\":false,\"postOnly\":false}" \
    "${user_id}" "${client_order_id}" "${symbol}" "${side}" "${tif}" "${price_ticks}" "${quantity_steps}"
}

place_order_batch_command() {
  local url="$1"
  local user_id="$2"
  local trace="$3"
  local items="$4"
  local extra_header=""
  if [[ "${url}" == *"/gateway/"* ]]; then
    extra_header="-H 'X-User-Id: ${user_id}'"
  fi
  printf "curl --retry %s --retry-delay %s --retry-max-time %s --retry-all-errors -fsS -X POST '%s/batch' -H 'Content-Type: application/json' %s -H 'X-Trace-Id: %s' -d '{\"orders\":[%s]}' >/dev/null\n" \
    "${ORDER_HTTP_RETRIES}" "${ORDER_HTTP_RETRY_DELAY_SECONDS}" "${ORDER_HTTP_RETRY_MAX_SECONDS}" \
    "${url}" "${extra_header}" "${trace}" "${items}"
}

emit_maker_batch_commands() {
  local client_prefix="$1"
  local trace_prefix="$2"
  local quantity_steps="$3"
  local outer_price_offset="$4"
  local level_count="$5"
  local url="http://localhost:9094/api/v1/gateway/trading"
  local symbols=("${BTC_SYMBOL}" "${ETH_SYMBOL}")
  local symbol
  local base_price
  local account
  local user_id
  local side
  local side_label
  local direction
  local level
  local price_ticks
  local payload
  local batch_items
  local batch_count
  local batch_index

  for symbol in "${symbols[@]}"; do
    base_price="$(price_ticks_for "${symbol}")"
    for ((account = 0; account < MM_ACCOUNT_COUNT; account++)); do
      user_id=$((MM_USER_START + account))
      for side in BUY SELL; do
        if [[ "${side}" == "BUY" ]]; then
          side_label="bid"
          direction=-1
        else
          side_label="ask"
          direction=1
        fi
        batch_items=""
        batch_count=0
        batch_index=1
        for ((level = 1; level <= level_count; level++)); do
          price_ticks=$((base_price + direction * (outer_price_offset + level)))
          payload="$(order_payload "${user_id}" "${client_prefix}-${symbol}-${side_label}-${account}-${level}" \
            "${symbol}" "${side}" "GTC" "${price_ticks}" "${quantity_steps}")"
          if [[ -n "${batch_items}" ]]; then
            batch_items="${batch_items},${payload}"
          else
            batch_items="${payload}"
          fi
          batch_count=$((batch_count + 1))
          if ((batch_count >= MAKER_BATCH_SIZE)); then
            place_order_batch_command "${url}" "${user_id}" \
              "${trace_prefix}-${side_label}-${symbol}-${account}-batch-${batch_index}" "${batch_items}"
            batch_items=""
            batch_count=0
            batch_index=$((batch_index + 1))
          fi
        done
        if [[ -n "${batch_items}" ]]; then
          place_order_batch_command "${url}" "${user_id}" \
            "${trace_prefix}-${side_label}-${symbol}-${account}-batch-${batch_index}" "${batch_items}"
        fi
      done
    done
  done
}

run_with_concurrency() {
  local max_jobs="$1"
  shift
  local active_pids=()
  local failures=0
  local command
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
  if ((${#active_pids[@]} > 0)); then
    local pid
    for pid in "${active_pids[@]}"; do
      if ! wait "${pid}"; then
        failures=$((failures + 1))
      fi
    done
  fi
  RUN_FAILURES="${failures}"
}

start_ws_capture() {
  local output="$1"
  local url="$2"
  shift 2
  local args=(python3 "${ROOT_DIR}/scripts/ws_capture.py" --url "${url}" --output "${output}" --stop-file "${WS_STOP_FILE}" --timeout "${WS_CAPTURE_TIMEOUT}")
  local subscription
  for subscription in "$@"; do
    args+=(--subscribe "${subscription}")
  done
  "${args[@]}" &
  PIDS+=("$!")
}

append_kafka_lag_sample() {
  local sampled_at
  local groups
  sampled_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  groups="$(
    {
      printf '%s\n' "surprising-account-v1" "surprising-matching-v1"
      compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
        | awk '/^surprising/ {print}' || true
    } | awk 'NF' | sort -u
  )"
  while IFS= read -r group; do
    [[ -z "${group}" ]] && continue
    echo "## ${sampled_at} group=${group}"
    compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --describe --group "${group}" 2>/dev/null || true
  done <<<"${groups}"
}

start_kafka_lag_sampler() {
  (
    while [[ ! -f "${KAFKA_LAG_STOP_FILE}" ]]; do
      append_kafka_lag_sample
      sleep "${KAFKA_LAG_INTERVAL_SECONDS}"
    done
  ) >"${KAFKA_LAG_LOG}" 2>&1 &
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
    if ((count >= expected)); then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for websocket subscription ${channel}" >&2
      cat "${file}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

consumer_member_count() {
  local group="$1"
  local topic="$2"
  compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group "${group}" 2>/dev/null \
    | awk -v group="${group}" -v topic="${topic}" '
        $1 == group && (topic == "" || $2 == topic) && $7 != "-" { seen[$7] = 1 }
        END { print length(seen) + 0 }
      '
}

consumer_client_ids() {
  local group="$1"
  local topic="$2"
  compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group "${group}" 2>/dev/null \
    | awk -v group="${group}" -v topic="${topic}" '
        $1 == group && (topic == "" || $2 == topic) && $9 != "-" { seen[$9] = 1 }
        END {
          first = 1
          for (client in seen) {
            if (!first) {
              printf ","
            }
            printf "%s", client
            first = 0
          }
        }
      '
}

topic_partition_count() {
  local topic="$1"
  compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic "${topic}" 2>/dev/null \
    | awk -F'PartitionCount: ' 'NR == 1 { split($2, fields, " "); print fields[1] }'
}

kafka_key_partition() {
  local key="$1"
  local partitions="$2"
  python3 - "${key}" "${partitions}" <<'PY'
import sys

key = sys.argv[1].encode("utf-8")
partitions = int(sys.argv[2])

def murmur2(data: bytes) -> int:
    length = len(data)
    seed = 0x9747B28C
    m = 0x5BD1E995
    r = 24
    h = (seed ^ length) & 0xFFFFFFFF
    length4 = length // 4
    for i in range(length4):
        i4 = i * 4
        k = (
            (data[i4 + 0] & 0xFF)
            | ((data[i4 + 1] & 0xFF) << 8)
            | ((data[i4 + 2] & 0xFF) << 16)
            | ((data[i4 + 3] & 0xFF) << 24)
        )
        k = (k * m) & 0xFFFFFFFF
        k ^= (k & 0xFFFFFFFF) >> r
        k = (k * m) & 0xFFFFFFFF
        h = (h * m) & 0xFFFFFFFF
        h ^= k
    remaining = length % 4
    offset = length4 * 4
    if remaining == 3:
        h ^= (data[offset + 2] & 0xFF) << 16
    if remaining >= 2:
        h ^= (data[offset + 1] & 0xFF) << 8
    if remaining >= 1:
        h ^= data[offset] & 0xFF
        h = (h * m) & 0xFFFFFFFF
    h ^= (h & 0xFFFFFFFF) >> 13
    h = (h * m) & 0xFFFFFFFF
    h ^= (h & 0xFFFFFFFF) >> 15
    return h & 0xFFFFFFFF

print((murmur2(key) & 0x7FFFFFFF) % partitions)
PY
}

consumer_client_for_partition() {
  local group="$1"
  local topic="$2"
  local partition="$3"
  compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group "${group}" 2>/dev/null \
    | awk -v group="${group}" -v topic="${topic}" -v partition="${partition}" '
        $1 == group && $2 == topic && $3 == partition && found == "" { found = $9 }
        END { print found }
      '
}

wait_consumer_members() {
  local group="$1"
  local topic="$2"
  local expected="$3"
  local deadline=$((SECONDS + 120))
  local actual
  while true; do
    actual="$(consumer_member_count "${group}" "${topic}" || echo 0)"
    if ((actual >= expected)); then
      return
    fi
    if ((SECONDS >= deadline)); then
      echo "Timed out waiting for Kafka group ${group} members on ${topic}: expected >= ${expected}, got ${actual}" >&2
      compose_exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
        --describe --group "${group}" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

collect_provider_topology_summary() {
  local matching_nodes=$((1 + EXTRA_MATCHING_NODES))
  local account_nodes=$((1 + EXTRA_ACCOUNT_NODES))
  local websocket_nodes=$((1 + EXTRA_WEBSOCKET_NODES))
  local matching_members
  local account_members
  local matching_clients
  local account_clients
  local command_partitions
  matching_members="$(consumer_member_count "surprising-matching-v1" "surprising.perp.order.commands.v1" || echo 0)"
  account_members="$(consumer_member_count "surprising-account-v1" "surprising.perp.match.trades.v1" || echo 0)"
  matching_clients="$(consumer_client_ids "surprising-matching-v1" "surprising.perp.order.commands.v1" || true)"
  account_clients="$(consumer_client_ids "surprising-account-v1" "surprising.perp.match.trades.v1" || true)"
  command_partitions="$(topic_partition_count "surprising.perp.order.commands.v1" || echo 0)"
cat <<EOF
- Provider nodes：matching=${matching_nodes}, account=${account_nodes}, websocket=${websocket_nodes}, order=1, gateway=1
- WebSocket port：${WEBSOCKET_PORT}
- Kafka group members：surprising-matching-v1/order.commands=${matching_members}, surprising-account-v1/match.trades=${account_members}
- Kafka client ids：matching=${matching_clients:-n/a}, account=${account_clients:-n/a}
EOF
  if [[ "${command_partitions}" =~ ^[0-9]+$ ]] && ((command_partitions > 0)); then
    local symbol
    local active_matching_clients=","
    local active_matching_client_count=0
    local active_symbol_count=0
    for symbol in "${BTC_SYMBOL}" "${ETH_SYMBOL}"; do
      local partition
      local client
      partition="$(kafka_key_partition "${symbol}" "${command_partitions}")"
      client="$(consumer_client_for_partition "surprising-matching-v1" "surprising.perp.order.commands.v1" "${partition}")"
      echo "- Matching owner：symbol=${symbol} order.commands.partition=${partition} clientId=${client:-n/a}"
      active_symbol_count=$((active_symbol_count + 1))
      if [[ -n "${client}" && "${active_matching_clients}" != *",${client},"* ]]; then
        active_matching_clients="${active_matching_clients}${client},"
        active_matching_client_count=$((active_matching_client_count + 1))
      fi
    done
    local active_split="false"
    if ((active_matching_client_count > 1)); then
      active_split="true"
    fi
    echo "- Matching active symbol consumers：uniqueClients=${active_matching_client_count}/${active_symbol_count} split=${active_split}"
  fi
  local i
  for ((i = 1; i <= EXTRA_WEBSOCKET_NODES; i++)); do
    local group="surprising-websocket-mm-${RUN_ID}-$((i + 1))"
    local members
    members="$(consumer_member_count "${group}" "surprising.perp.orderbook.depth.v1" || echo 0)"
    echo "- Extra WebSocket group：${group}/depth members=${members}"
  done
}

assert_ws_events() {
  local depth_btc="$1"
  local depth_eth="$2"
  local private_file="$3"
  python3 - "${depth_btc}" "${depth_eth}" "${private_file}" <<'PY'
import json
import pathlib
import sys

def events(path, channel):
    result = []
    p = pathlib.Path(path)
    if not p.exists():
        return result
    for line in p.read_text(encoding="utf-8").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("op") == "event" and message.get("channel") == channel:
            result.append(message)
    return result

btc_depth = events(sys.argv[1], "depth")
eth_depth = events(sys.argv[2], "depth")
orders = events(sys.argv[3], "orders")
positions = events(sys.argv[3], "positions")
execution_reports = events(sys.argv[3], "executionReports")
if not btc_depth:
    raise SystemExit("no BTC depth websocket events")
if not eth_depth:
    raise SystemExit("no ETH depth websocket events")
if not orders:
    raise SystemExit("no private order websocket events")
if not positions:
    raise SystemExit("no private position websocket events")
if not execution_reports:
    raise SystemExit("no private executionReports websocket events")
if not any((event.get("data") or {}).get("reportType") == "TRADE" for event in execution_reports):
    raise SystemExit("no private TRADE execution report websocket events")
print(f"btc_depth={len(btc_depth)} eth_depth={len(eth_depth)} private_orders={len(orders)} private_positions={len(positions)} private_executionReports={len(execution_reports)}")
PY
}

assert_ws_depth_events() {
  local file="$1"
  local label="$2"
  python3 - "${file}" "${label}" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
label = sys.argv[2]
count = 0
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("op") == "event" and message.get("channel") == "depth":
            count += 1
if count <= 0:
    raise SystemExit(f"no depth websocket events on {label}")
print(f"{label}_depth={count}")
PY
}

assert_ws_private_events() {
  local file="$1"
  local user_id="$2"
  local label="$3"
  python3 - "${file}" "${user_id}" "${label}" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
user_id = int(sys.argv[2])
label = sys.argv[3]
orders = 0
positions = 0
execution_reports = 0
trade_reports = 0
if path.exists():
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("op") != "event":
            continue
        if message.get("userId") != user_id:
            raise SystemExit(f"{label} received event for wrong user {message.get('userId')}, expected {user_id}")
        channel = message.get("channel")
        if channel == "orders":
            orders += 1
        elif channel == "positions":
            positions += 1
        elif channel == "executionReports":
            execution_reports += 1
            if (message.get("data") or {}).get("reportType") == "TRADE":
                trade_reports += 1
if orders <= 0:
    raise SystemExit(f"no private order websocket events on {label}")
if positions <= 0:
    raise SystemExit(f"no private position websocket events on {label}")
if execution_reports <= 0:
    raise SystemExit(f"no private executionReports websocket events on {label}")
if trade_reports <= 0:
    raise SystemExit(f"no private TRADE execution report websocket events on {label}")
print(f"{label}_orders={orders} {label}_positions={positions} {label}_executionReports={execution_reports}")
PY
}

assert_ws_private_fanout_events() {
  local summaries=()
  local index
  for index in "${!WS_PRIVATE_FILES[@]}"; do
    local user_id=$((TAKER_USER_START + index))
    summaries+=("$(assert_ws_private_events "${WS_PRIVATE_FILES[$index]}" "${user_id}" "private_user_${index}")")
  done
  printf '%s' "${summaries[*]}"
}

sql_scalar() {
  local sql="$1"
  query_value "${sql}" | tr '\n' ' ' | xargs
}

run_matching_node_failure_after_open_book() {
  local target_symbol="${BTC_SYMBOL}"
  local partitions
  local partition
  local owner_client
  local owner_provider
  local expected_members
  local new_owner_client
  partitions="$(topic_partition_count "surprising.perp.order.commands.v1")"
  partition="$(kafka_key_partition "${target_symbol}" "${partitions}")"
  owner_client="$(consumer_client_for_partition "surprising-matching-v1" \
    "surprising.perp.order.commands.v1" "${partition}")"
  owner_provider="$(matching_provider_for_client_id "${owner_client}" || true)"
  if [[ -z "${owner_client}" || -z "${owner_provider}" ]]; then
    echo "Unable to resolve matching owner for ${target_symbol} partition ${partition}: client=${owner_client}" >&2
    exit 1
  fi

  echo "Scenario: matching node failure after open book owner=${owner_provider} client=${owner_client}"
  stop_provider "${owner_provider}"
  start_matching_provider "${owner_provider}"
  expected_members=$(((1 + EXTRA_MATCHING_NODES) * 2))
  wait_matching_members_with_restarts "${expected_members}"
  new_owner_client="$(consumer_client_for_partition "surprising-matching-v1" \
    "surprising.perp.order.commands.v1" "${partition}")"
  if [[ -z "${new_owner_client}" ]]; then
    echo "Unable to resolve recovered matching owner for ${target_symbol} partition ${partition}" >&2
    exit 1
  fi

  local failover_user=$((TAKER_USER_START + TOTAL_TAKER_ORDER_COUNT + 50))
  local client_order_id="stress-matching-failover-${RUN_ID}"
  local trace_id="mm-stress-${RUN_ID}-matching-failover"
  adjust_balance "${failover_user}" "mm-stress-${RUN_ID}-matching-failover-user"
  curl -fsS -X POST "http://localhost:9094/api/v1/gateway/trading" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${failover_user}" \
    -H "X-Trace-Id: ${trace_id}" \
    -d "{
      \"userId\": ${failover_user},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${target_symbol}\",
      \"side\": \"BUY\",
      \"orderType\": \"LIMIT\",
      \"timeInForce\": \"IOC\",
      \"priceTicks\": $((BTC_PRICE_TICKS + MM_DEPTH_LEVELS)),
      \"quantitySteps\": ${TAKER_QUANTITY_STEPS},
      \"reduceOnly\": false,
      \"postOnly\": false
    }" >/dev/null

  wait_sql_equals "matching failover taker order filled" \
    "SELECT count(*) FROM trading_orders WHERE client_order_id = '${client_order_id}' AND status = 'FILLED' AND executed_quantity_steps = ${TAKER_QUANTITY_STEPS}" \
    "1" 240
  wait_sql_equals "matching failover trade written" \
    "SELECT count(*) FROM trading_match_trades WHERE trace_id = '${trace_id}'" \
    "1" 240
  wait_sql_equals "matching failover trade settled" \
    "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.trace_id = '${trace_id}'" \
    "1" 240

  add_failure_summary "- Matching node failure after open book：stopped ${owner_provider} owning ${target_symbol} partition ${partition}, restarted it, recovered owner clientId=${new_owner_client}, and filled a taker order against pre-failure maker liquidity."
}

collect_account_metrics() {
  local metrics_files=()
  local failed_ports=()
  local i
  for ((i = 0; i <= EXTRA_ACCOUNT_NODES; i++)); do
    local port=$((9086 + i * 100))
    local metrics_file="${TMP_DIR}/account-prometheus-${port}.txt"
    if curl -fsS "http://localhost:${port}/actuator/prometheus" >"${metrics_file}"; then
      metrics_files+=("${metrics_file}")
    else
      failed_ports+=("${port}")
    fi
  done
  if ((${#failed_ports[@]} > 0)); then
    echo "- Prometheus scrape note：account ports unavailable or intentionally stopped: ${failed_ports[*]}; database settlement count is the authoritative completion check for failover runs."
  fi
  if ((${#metrics_files[@]} == 0)); then
    return
  fi
  python3 - "${metrics_files[@]}" <<'PY'
import re
import sys

samples = {}
line_re = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$')
label_re = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="([^"]*)"')
for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            match = line_re.match(line)
            if not match:
                continue
            name, labels_raw, value_raw = match.groups()
            labels = dict(label_re.findall(labels_raw or ""))
            outcome = labels.get("outcome")
            if not outcome:
                continue
            key = (name, outcome)
            metric_value = float(value_raw)
            if name.endswith("_max"):
                samples[key] = max(samples.get(key, 0.0), metric_value)
            else:
                samples[key] = samples.get(key, 0.0) + metric_value

def value(name, outcome):
    return samples.get((name, outcome), 0.0)

def fmt_count(number):
    return str(int(number)) if number.is_integer() else str(round(number, 3))

def avg_seconds(prefix, outcome):
    count = value(f"{prefix}_seconds_count", outcome)
    total = value(f"{prefix}_seconds_sum", outcome)
    if count <= 0:
        return "n/a"
    return f"{round(total / count, 6)}s"

def max_seconds(prefix, outcome):
    max_value = value(f"{prefix}_seconds_max", outcome)
    return "n/a" if max_value <= 0 else f"{round(max_value, 6)}s"

event_name = "surprising_account_match_trade_events_total"
processing = "surprising_account_match_trade_processing"
event_lag = "surprising_account_match_trade_event_lag"
outcomes = ("processed", "duplicate", "failed")
event_summary = " ".join(f"{outcome}={fmt_count(value(event_name, outcome))}" for outcome in outcomes)
print(f"- match-trade events：{event_summary}")
for outcome in outcomes:
    print(
        f"- {outcome} processing：avg={avg_seconds(processing, outcome)} "
        f"max={max_seconds(processing, outcome)} eventLagAvg={avg_seconds(event_lag, outcome)} "
        f"eventLagMax={max_seconds(event_lag, outcome)}"
    )
PY
}

collect_kafka_lag_summary() {
  if [[ ! -s "${KAFKA_LAG_LOG}" ]]; then
    echo "- Kafka consumer lag：no samples captured"
    return
  fi
  python3 - "${KAFKA_LAG_LOG}" <<'PY'
import collections
import re
import sys

path = sys.argv[1]
sample_id = 0
sample_lag = collections.defaultdict(int)
sample_order = []
marker_re = re.compile(r"^##\s+(\S+)\s+group=(\S+)")

with open(path, encoding="utf-8", errors="replace") as fh:
    for raw in fh:
        line = raw.strip()
        if not line:
            continue
        marker = marker_re.match(line)
        if marker:
            sample_id += 1
            sample_order.append((sample_id, marker.group(1), marker.group(2)))
            continue
        if not line.startswith("surprising"):
            continue
        parts = line.split()
        if len(parts) < 6:
            continue
        group, topic, partition, current, log_end, lag = parts[:6]
        consumer_id = parts[6] if len(parts) > 6 else ""
        if consumer_id == "-":
            continue
        if not lag.isdigit():
            continue
        sample_lag[(sample_id, group, topic)] += int(lag)

if not sample_lag:
    print("- Kafka consumer lag：no committed consumer-group lag rows")
    raise SystemExit

latest_sample_for_group_topic = {}
max_lag = collections.defaultdict(int)
timestamps = {}
for sample, timestamp, group in sample_order:
    timestamps[sample] = timestamp
for (sample, group, topic), lag in sample_lag.items():
    key = (group, topic)
    if lag > max_lag[key]:
        max_lag[key] = lag
    previous = latest_sample_for_group_topic.get(key)
    if previous is None or sample > previous[0]:
        latest_sample_for_group_topic[key] = (sample, lag)

print("- Kafka lag 采样日志：" + path)
for group, topic in sorted(max_lag):
    sample, last_lag = latest_sample_for_group_topic[(group, topic)]
    timestamp = timestamps.get(sample, "unknown")
    print(f"- {group} / {topic}：maxLag={max_lag[(group, topic)]} lastLag={last_lag} lastSample={timestamp}")
PY
}

collect_pg_stat_snapshot() {
  query_value "
SELECT xact_commit || ',' || xact_rollback || ',' || blks_read || ',' || blks_hit || ',' ||
       tup_returned || ',' || tup_fetched || ',' || tup_inserted || ',' || tup_updated || ',' ||
       tup_deleted || ',' || temp_files || ',' || temp_bytes || ',' || deadlocks
  FROM pg_stat_database
 WHERE datname = current_database()"
}

format_pg_stat_delta() {
  local before="$1"
  local after="$2"
  python3 - "${before}" "${after}" <<'PY'
import sys

names = [
    "xact_commit", "xact_rollback", "blks_read", "blks_hit",
    "tup_returned", "tup_fetched", "tup_inserted", "tup_updated",
    "tup_deleted", "temp_files", "temp_bytes", "deadlocks",
]
before = [int(value) for value in sys.argv[1].split(",")]
after = [int(value) for value in sys.argv[2].split(",")]
deltas = {name: after[index] - before[index] for index, name in enumerate(names)}
hit_total = deltas["blks_hit"] + deltas["blks_read"]
hit_ratio = "n/a" if hit_total <= 0 else f"{round(deltas['blks_hit'] * 100 / hit_total, 2)}%"
print(
    "- PostgreSQL delta："
    f"commits={deltas['xact_commit']} rollbacks={deltas['xact_rollback']} "
    f"inserted={deltas['tup_inserted']} updated={deltas['tup_updated']} deleted={deltas['tup_deleted']} "
    f"fetched={deltas['tup_fetched']} returned={deltas['tup_returned']} "
    f"blocksRead={deltas['blks_read']} blocksHit={deltas['blks_hit']} hitRatio={hit_ratio} "
    f"tempFiles={deltas['temp_files']} tempBytes={deltas['temp_bytes']} deadlocks={deltas['deadlocks']}"
)
PY
}

collect_table_counts_summary() {
  psql_exec -At <<SQL
SELECT '- 表行数：orders=' || (SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'stress-%-${RUN_ID}-%')
    || ' orderEvents=' || (SELECT count(*) FROM trading_order_events WHERE trace_id LIKE 'mm-stress-${RUN_ID}-%')
    || ' matchResults=' || (SELECT count(*) FROM trading_match_results WHERE trace_id LIKE 'mm-stress-${RUN_ID}-%')
    || ' matchTrades=' || (SELECT count(*) FROM trading_match_trades WHERE trace_id LIKE 'mm-stress-${RUN_ID}-%')
    || ' processedTrades=' || (
        SELECT count(*)
          FROM account_processed_trades p
          JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id
         WHERE t.trace_id LIKE 'mm-stress-${RUN_ID}-%'
       )
    || ' accountLedgers=' || (SELECT count(*) FROM account_ledger_entries WHERE reference_id LIKE 'mm-stress-${RUN_ID}-%')
    || ' positions=' || (
        SELECT count(*)
          FROM account_positions
         WHERE user_id BETWEEN ${MM_USER_START} AND $((TAKER_USER_START + TOTAL_TAKER_ORDER_COUNT + 100))
       )
    || ' openTradingOutbox=' || (SELECT count(*) FROM trading_outbox_events WHERE published_at IS NULL)
    || ' openAccountOutbox=' || (SELECT count(*) FROM account_outbox_events WHERE published_at IS NULL);
SQL
}

collect_provider_metrics_summary() {
  local targets=()
  local i
  targets+=("order:9084")
  for ((i = 0; i <= EXTRA_MATCHING_NODES; i++)); do
    if ((i == 0)); then
      targets+=("matching:9085")
    else
      targets+=("matching-$((i + 1)):$((9085 + i * 100))")
    fi
  done
  for ((i = 0; i <= EXTRA_ACCOUNT_NODES; i++)); do
    if ((i == 0)); then
      targets+=("account:9086")
    else
      targets+=("account-$((i + 1)):$((9086 + i * 100))")
    fi
  done
  targets+=("websocket:${WEBSOCKET_PORT}")
  for ((i = 1; i <= EXTRA_WEBSOCKET_NODES; i++)); do
    targets+=("websocket-$((i + 1)):$((WEBSOCKET_PORT + i * 100))")
  done
  targets+=("gateway:9094")

  local scraped=()
  local unavailable=()
  local target
  for target in "${targets[@]}"; do
    local name="${target%%:*}"
    local port="${target##*:}"
    local metrics_file="${TMP_DIR}/provider-prometheus-${name}-${port}.txt"
    if curl -fsS "http://localhost:${port}/actuator/prometheus" >"${metrics_file}"; then
      scraped+=("${name}:${metrics_file}")
    else
      unavailable+=("${name}:${port}")
    fi
  done

  if ((${#unavailable[@]} > 0)); then
    echo "- Provider scrape note：unavailable or intentionally stopped: ${unavailable[*]}"
  fi
  if ((${#scraped[@]} == 0)); then
    echo "- Provider metrics：no actuator/prometheus targets scraped"
    return
  fi

  python3 - "${scraped[@]}" <<'PY'
import re
import sys

line_re = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([-+0-9.eE]+)$')
label_re = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="([^"]*)"')

def fmt(value, digits=4):
    if value is None:
        return "n/a"
    if abs(value - round(value)) < 1e-9:
        return str(int(round(value)))
    return str(round(value, digits))

for arg in sys.argv[1:]:
    provider, path = arg.split(":", 1)
    http_count = 0.0
    http_sum = 0.0
    http_max = 0.0
    hikari_active = None
    hikari_pending = None
    hikari_max = None
    process_cpu = None
    system_cpu = None
    heap_used = 0.0
    heap_max = 0.0

    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            match = line_re.match(line)
            if not match:
                continue
            name, labels_raw, value_raw = match.groups()
            labels = dict(label_re.findall(labels_raw or ""))
            try:
                value = float(value_raw)
            except ValueError:
                continue
            uri = labels.get("uri", "")
            if uri.startswith("/actuator"):
                continue
            if name == "http_server_requests_seconds_count":
                http_count += value
            elif name == "http_server_requests_seconds_sum":
                http_sum += value
            elif name == "http_server_requests_seconds_max":
                http_max = max(http_max, value)
            elif name == "hikaricp_connections_active":
                hikari_active = max(hikari_active or 0.0, value)
            elif name == "hikaricp_connections_pending":
                hikari_pending = max(hikari_pending or 0.0, value)
            elif name == "hikaricp_connections_max":
                hikari_max = max(hikari_max or 0.0, value)
            elif name == "process_cpu_usage":
                process_cpu = value
            elif name == "system_cpu_usage":
                system_cpu = value
            elif name == "jvm_memory_used_bytes" and labels.get("area") == "heap":
                heap_used += value
            elif name == "jvm_memory_max_bytes" and labels.get("area") == "heap" and value > 0:
                heap_max += value

    http_avg = None if http_count <= 0 else http_sum / http_count
    heap_summary = "n/a"
    if heap_used > 0:
        heap_mib = heap_used / (1024 * 1024)
        if heap_max > 0:
            heap_summary = f"{round(heap_mib, 2)}MiB/{round(heap_max / (1024 * 1024), 2)}MiB"
        else:
            heap_summary = f"{round(heap_mib, 2)}MiB"
    http_avg_text = "n/a" if http_avg is None else f"{fmt(http_avg, 6)}s"
    http_max_text = "n/a" if http_count <= 0 and http_max <= 0 else f"{fmt(http_max, 6)}s"
    print(
        f"- {provider}：httpCount={fmt(http_count)} httpAvg={http_avg_text} httpMax={http_max_text} "
        f"hikariActive={fmt(hikari_active)} hikariPending={fmt(hikari_pending)} hikariMax={fmt(hikari_max)} "
        f"processCpu={fmt(process_cpu, 6)} systemCpu={fmt(system_cpu, 6)} heap={heap_summary}"
    )
PY
}

write_report() {
  local submit_ms="$1"
  local submit_tps="$2"
  local maker_count="$3"
  local refresh_count="$4"
  local taker_count="$5"
  local trades="$6"
  local matched_latency="$7"
  local account_latency="$8"
  local match_span_tps="$9"
  local websocket_summary="${10}"
  local machine_cpu="${11}"
  local machine_mem="${12}"
  local java_version="${13}"
  local git_head="${14}"
  local account_metrics="${15}"
  local kafka_lag_summary="${16}"
  local provider_topology_summary="${17}"
  local settled_count="${18}"
  local db_stat_delta="${19}"
  local table_counts_summary="${20}"
  local provider_metrics_summary="${21}"
  local infra_summary
  if [[ "${INFRA_MODE}" == "local" ]]; then
    infra_summary="Homebrew/local services: PostgreSQL localhost:5432, Kafka localhost:9092, Redis localhost:6379"
  else
    infra_summary="Docker Compose services via ${COMPOSE_FILE}"
  fi
  mkdir -p "$(dirname "${REPORT_FILE}")"
  cat > "${REPORT_FILE}" <<EOF
# 做市商模拟交易压测报告

时间：$(date -u +"%Y-%m-%dT%H:%M:%SZ")

## 环境

- Git：${git_head}
- CPU：${machine_cpu}
- 内存：${machine_mem}
- Java：${java_version}
- PostgreSQL/Kafka/Redis：${infra_summary}
- Provider：order、matching、account、websocket、gateway 真实进程
- 临时日志目录：\`${TMP_DIR}\`

## Provider 拓扑

${provider_topology_summary}

## 故障场景

${FAILURE_SCENARIO_SUMMARY}

## 压测参数

- 做市商账号数：${MM_ACCOUNT_COUNT}
- 做市商初始深度：每个 symbol、每侧 ${MM_DEPTH_LEVELS} 档，每档 ${MM_LEVEL_QUANTITY_STEPS} steps
- 做市商刷新挂单：每个 symbol、每侧 ${MM_REFRESH_LEVELS} 档，每档 ${MM_REFRESH_QUANTITY_STEPS} steps
- 做市商连续刷新轮数：${MM_REFRESH_CYCLES}，轮间隔 ${MM_REFRESH_INTERVAL_SECONDS}s
- 做市商入口：gateway \`/trading/batch\`，batchSize=${MAKER_BATCH_SIZE}，makerConcurrency=${MAKER_LOAD_CONCURRENCY}
- 普通用户订单数：${taker_count}
- 普通用户每单数量：${TAKER_QUANTITY_STEPS} steps
- 并发度：${LOAD_CONCURRENCY}
- WebSocket 私有 fanout 订阅用户数：${WS_FANOUT_USER_COUNT}
- WebSocket 捕获窗口：${WS_CAPTURE_TIMEOUT}s
- Consumer 并发：matching 每节点 ${MATCHING_CONSUMERS_PER_NODE}，account 每节点 ${ACCOUNT_CONSUMERS_PER_NODE}
- Hikari：order ${ORDER_HIKARI_MAX_POOL_SIZE}/${ORDER_HIKARI_CONNECTION_TIMEOUT_MS}ms，matching ${MATCHING_HIKARI_MAX_POOL_SIZE}/${MATCHING_HIKARI_CONNECTION_TIMEOUT_MS}ms，account ${ACCOUNT_HIKARI_MAX_POOL_SIZE}/${ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS}ms，gateway ${GATEWAY_HIKARI_MAX_POOL_SIZE}/${GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS}ms
- 等待窗口：takerFilled=${TAKER_FILL_WAIT_SECONDS}s，tradesWritten=${TAKER_TRADE_WAIT_SECONDS}s，accountSettled=${ACCOUNT_SETTLEMENT_WAIT_SECONDS}s
- Symbols：${BTC_SYMBOL}, ${ETH_SYMBOL}

## 结果

- 初始做市商挂单：${maker_count}
- 并发刷新挂单：${refresh_count}
- 普通用户 taker 订单：${taker_count}
- 撮合成交笔数：${trades}
- account 已结算普通用户成交：${settled_count}
- 客户端并发提交耗时：${submit_ms} ms
- 客户端提交吞吐：${submit_tps} orders/s
- matching event-time 吞吐：${match_span_tps} trades/s
- WebSocket：${websocket_summary}

## 压测中发现的瓶颈和修复

- 64 并发下单最初会把 order-provider 的 Hikari 连接池打满，表现为 PostgreSQL 连接获取超时和 HTTP 500。根因是高频 ID 分配使用 \`trading_sequences\` 表计数器热点行，并且 order/matching outbox 发布在数据库事务内等待 Kafka ACK。
- 已改为 PostgreSQL native sequence 分配交易链路 ID；order/matching outbox 改为先租约 claim DB 行，再在事务外发送 Kafka。修复后同样 64 并发没有再出现连接池 500。
- 本地压测直接用脚本写 \`price_mark_ticks\`，不是启动完整 mark-price provider。高并发下 \`docker exec psql\` 写 mark 会出现数秒级空洞；脚本已改为单 SQL 批量写 BTC/ETH mark，并把 LIMIT 订单价格保护的 \`limit-price-max-mark-age-ms\` 配到 15 秒。生产环境仍应由 mark-price provider 保证秒级刷新，不能靠放宽窗口掩盖行情故障。
- 当前 account-provider 持仓更新路径会复用成交结算时已经锁定的旧持仓数量，避免每个成交侧额外一次 \`SELECT ... FOR UPDATE\` 和一次更新后持仓回查。
- 当前 account-provider match-trade listener 使用 Kafka batch delivery 和 \`AckMode.BATCH\` 降低 per-record offset commit 开销；批次内每条成交仍独立走事务和 \`account_processed_trades(symbol, trade_id)\` 幂等。
- 当前 account-provider 余额结算只在 \`deficit_units\` 发生变化时写 \`account_deficits\`，普通开仓手续费、未穿仓平仓和强平费封顶扣款会跳过无变化 deficit 更新，减少成交热路径 SQL 写放大。
- 当前 account-provider 只在成交实际减少旧仓位时执行 reduce-only 开放平仓单剪枝，普通开仓/加仓成交不再额外查询 \`trading_orders ... reduce_only\`。
- 当前 account-provider 对 \`liquidation_orders\` 强平费上下文按订单维度做有界 JVM 缓存，并缓存普通订单的空结果；缓存只覆盖订单元数据/负查询，不作为余额、持仓或 deficit 的事实源。
- 当前 account-provider 对 instrument type、spot instrument spec、contract spec 和 order fee snapshot 使用 symbol/version 或 order 维度有界 JVM 缓存，减少每笔成交固定元数据读；缓存不覆盖余额、持仓、保证金或 deficit。
- 当前 account-provider 跨仓普通负向资金变动会先尝试 guarded available-balance fast path：可用余额足够时直接扣 \`available_units\` 并返回最新 equity，不再查询/锁 \`account_position_margins\`；余额不足、逐仓、穿仓或强平费封顶场景仍回落到完整锁持仓保证金和 deficit 结算路径。
- 本轮把 maker 与 taker 都切到 gateway 后，压测暴露出线性 SELL 限价开空在缺 fresh mark 时可能只按委托价预占保证金；已改为只有可能开仓/翻仓且成交价改善会增加保证金需求时，才强制 fresh mark 并按保护价预占，否则作为业务拒单返回。
- 高一档压测把 maker 初始深度提升到 30 档、taker 并发提升到 96 时，逐笔并发 maker 挂单会再次打满 order-provider 连接池。脚本已改为 maker 经 gateway 批量下单并使用独立 maker 并发上限；普通 taker 仍按真实用户逐笔经 gateway 进入，所有 POST 继续使用 \`clientOrderId\` 幂等重试。
- L4 10000 用户压测首次运行暴露 fixture 入金瓶颈：逐用户调用 admin balance-adjustment HTTP 使准备阶段耗时数分钟，且这不是交易链路瓶颈。脚本已把初始 maker/taker 资金准备改为一次批量 SQL，同时写入 \`account_balances\`、\`account_ledger_entries\` 和 \`account_admin_balance_adjustments\`；用户下单、maker batch、taker 单笔、撮合、account 结算仍全部走真实 gateway/Kafka/PostgreSQL 链路。

## 延迟

单位：ms，字段顺序为 \`count min p50 p95 p99 max\`。

- order accepted -> matching result：${matched_latency}
- order accepted -> account processed trade：${account_latency}

## Account Prometheus 指标

${account_metrics}

## Kafka Consumer Lag

${kafka_lag_summary}

## PostgreSQL 指标

${db_stat_delta}
${table_counts_summary}

## Provider Prometheus 摘要

${provider_metrics_summary}

## 裸撮合基准

\`exchange-core\` 裸撮合封装层性能用 \`./scripts/matching-engine-benchmark.sh\` 单独测量。这个 benchmark 不包含 HTTP、order 入库、outbox、Kafka、matching 结果落库、account 结算和 WebSocket fanout。

## 相关验证入口

- 本报告覆盖做市商挂单、做市商刷新挂单和普通用户 taker 全部经 gateway 入口进入的真实 order/matching/account/websocket 链路；maker 多档报价走 gateway 批量接口，taker 用户订单走 gateway 单笔接口。
- 全 provider 链路、资金费、爆仓、保险基金和 ADL 结果见 \`docs/integration-report.md\` / \`docs/integration-report_CN.md\`。
- exchange-core 裸撮合封装层性能用 \`./scripts/matching-engine-benchmark.sh\` 单独测量，不应与本报告的端到端吞吐直接比较。

## 关键观察

- 客户端并发提交耗时包含 ${refresh_count} 笔 maker 刷新挂单和 ${taker_count} 笔普通用户 IOC 吃单；吞吐按普通用户 taker 订单数计算。
- matching event-time 吞吐是完整服务链路中的撮合成交写入速度，不是 exchange-core 裸引擎基准。当前端到端主要受 order 入库/outbox、Kafka、本机 PostgreSQL 和 account 结算影响。
- Provider 拓扑中的 \`Matching active symbol consumers\` 用来判断本轮热 symbol 是否真的被分散到多个 matching consumer；如果 \`split=false\`，单纯提高 listener concurrency 不会让这些 symbol 并行撮合。
- account processed trade 延迟明显高于 matching result 延迟，说明账户结算和 position/ledger/margin 写库是本轮最大后置瓶颈。account-provider 暴露 \`surprising.account.match_trade.processing\`、\`surprising.account.match_trade.event_lag\` 和 \`surprising.account.match_trade.events{outcome=...}\`，后续压测可以直接按 processed/duplicate/failed 拆分定位。
- Provider Prometheus 摘要中的 HTTP、Hikari、CPU 和 heap 指标是压测进程运行期累计/采样值，用来辅助判断入口服务、数据库连接池或 JVM 是否先成为瓶颈；并发阶段 PostgreSQL delta 用来观察事务量、写放大、缓存命中和临时文件。
- WebSocket 私有事件订阅前 ${WS_FANOUT_USER_COUNT} 个普通用户账号，并逐个断言 orders/positions/executionReports 只推送给对应认证用户；公网生产压测仍应继续把该数值扩大到真实长连接规模。

## 一致性检查

- 普通用户订单全部 \`FILLED\`
- 成交全部被 account 消费，\`account_processed_trades(symbol, trade_id)\` 去重键生效
- 压测用户余额未出现负 available/locked
- 产品账户、仓位保证金、预占释放、account/product deficit 均无异常
- trading/account outbox 均清空
- 订单簿仍有双边深度
- WebSocket 收到 BTC/ETH depth 事件，并且前 ${WS_FANOUT_USER_COUNT} 个普通用户都收到各自的私有 orders/positions/executionReports 事件
- \`trading_symbol_open_interest\` 由账户结算维护；压测会从 \`account_positions\` 重算 long/short/open interest 并对账

## 结论

这次压测验证的是单机、单 Kafka broker、单 PostgreSQL、matching=$((1 + EXTRA_MATCHING_NODES))、account=$((1 + EXTRA_ACCOUNT_NODES))、websocket=$((1 + EXTRA_WEBSOCKET_NODES)) 的真实进程链路。matching 本身可以完成本报告中的并发撮合；端到端延迟还包含 order 入库、outbox、Kafka、account 结算和 WebSocket fanout。

后续要继续提高压力，应把 \`TAKER_ORDER_COUNT\` 和 \`LOAD_CONCURRENCY\` 逐级上调，并同时观察 order-provider HTTP 指标、Hikari pending/active、Kafka lag、matching event-time、account-provider settlement lag、PostgreSQL delta、CPU 和 IO。
EOF
}

require_command curl
require_command python3
require_command psql
require_command pg_isready
require_command kafka-topics
require_command kafka-consumer-groups
if [[ "${INFRA_MODE}" != "local" ]]; then
  require_command docker
fi

if ((MM_ACCOUNT_COUNT <= 0 || MM_DEPTH_LEVELS <= 0 || TAKER_ORDER_COUNT <= 0 || LOAD_CONCURRENCY <= 0)); then
  echo "stress parameters must be positive" >&2
  exit 1
fi
if ((WS_CAPTURE_TIMEOUT <= 0 || WS_FANOUT_USER_COUNT <= 0)); then
  echo "WS_CAPTURE_TIMEOUT and WS_FANOUT_USER_COUNT must be positive" >&2
  exit 1
fi
if ((MM_REFRESH_CYCLES <= 0 || MM_REFRESH_INTERVAL_SECONDS < 0)); then
  echo "MM_REFRESH_CYCLES must be positive and MM_REFRESH_INTERVAL_SECONDS must be zero or positive" >&2
  exit 1
fi
if ((MAKER_BATCH_SIZE <= 0 || MAKER_BATCH_SIZE > 20)); then
  echo "MAKER_BATCH_SIZE must be in [1, 20]" >&2
  exit 1
fi
if ((MAKER_LOAD_CONCURRENCY <= 0)); then
  echo "MAKER_LOAD_CONCURRENCY must be positive" >&2
  exit 1
fi
if ((EXTRA_MATCHING_NODES < 0 || EXTRA_ACCOUNT_NODES < 0 || EXTRA_WEBSOCKET_NODES < 0)); then
  echo "extra node counts must be zero or positive" >&2
  exit 1
fi
if ((MATCHING_CONSUMERS_PER_NODE <= 0 || ACCOUNT_CONSUMERS_PER_NODE <= 0)); then
  echo "MATCHING_CONSUMERS_PER_NODE and ACCOUNT_CONSUMERS_PER_NODE must be positive" >&2
  exit 1
fi
if [[ "${ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT}" == "true" ]] && ((EXTRA_ACCOUNT_NODES <= 0)); then
  echo "ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT=true requires EXTRA_ACCOUNT_NODES > 0" >&2
  exit 1
fi
if [[ "${ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT}" == "true" && "${START_PROVIDERS}" != "true" ]]; then
  echo "ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT=true requires START_PROVIDERS=true so the script can stop account-2" >&2
  exit 1
fi
if [[ "${MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK}" == "true" ]] && ((EXTRA_MATCHING_NODES <= 0)); then
  echo "MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK=true requires EXTRA_MATCHING_NODES > 0" >&2
  exit 1
fi
if [[ "${MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK}" == "true" && "${START_PROVIDERS}" != "true" ]]; then
  echo "MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK=true requires START_PROVIDERS=true so the script can stop and restart matching nodes" >&2
  exit 1
fi
TOTAL_TAKER_ORDER_COUNT=$((TAKER_ORDER_COUNT * MM_REFRESH_CYCLES))
if ((WS_FANOUT_USER_COUNT > TOTAL_TAKER_ORDER_COUNT)); then
  echo "WS_FANOUT_USER_COUNT cannot exceed total taker users (${TOTAL_TAKER_ORDER_COUNT})" >&2
  exit 1
fi

if [[ "${START_INFRA}" == "true" ]]; then
  echo "Starting ${INFRA_MODE} infrastructure"
  start_infra
fi
wait_until "PostgreSQL" 120 compose_exec postgres pg_isready -U "${DB_USER}"
wait_until "Kafka" 120 compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

if [[ "${RESET_STATE}" == "true" ]]; then
  echo "Resetting PostgreSQL schema and Kafka topics"
  reset_database
  seed_mark_prices
  if [[ "${RESET_KAFKA_MODE}" == "recreate" ]]; then
    recreate_kafka
  else
    delete_surprising_topics
  fi
  create_topics
  rm -rf "${ROOT_DIR}/data/kafka-streams"
fi

if [[ "${START_PROVIDERS}" == "true" ]]; then
  package_services
  start_matching_provider "matching"
  start_provider "account" 9086 "surprising-account/surprising-account-provider" "surprising-account-provider" \
    "--spring.datasource.hikari.maximum-pool-size=${ACCOUNT_HIKARI_MAX_POOL_SIZE}" \
    "--spring.datasource.hikari.connection-timeout=${ACCOUNT_HIKARI_CONNECTION_TIMEOUT_MS}" \
    "--surprising.account.kafka.client-id=mm-stress-${RUN_ID}-account-1" \
    "--surprising.account.kafka.concurrency=${ACCOUNT_CONSUMERS_PER_NODE}"
  start_provider "websocket" "${WEBSOCKET_PORT}" "surprising-websocket/surprising-websocket-provider" "surprising-websocket-provider"
  start_extra_nodes
  wait_consumer_members "surprising-matching-v1" "surprising.perp.order.commands.v1" $(((1 + EXTRA_MATCHING_NODES) * MATCHING_CONSUMERS_PER_NODE))
  wait_consumer_members "surprising-account-v1" "surprising.perp.match.trades.v1" $(((1 + EXTRA_ACCOUNT_NODES) * ACCOUNT_CONSUMERS_PER_NODE))
  for ((i = 1; i <= EXTRA_WEBSOCKET_NODES; i++)); do
    wait_consumer_members "surprising-websocket-mm-${RUN_ID}-$((i + 1))" "surprising.perp.orderbook.depth.v1" 1
  done
  start_provider "order" 9084 "surprising-trading/surprising-order-provider" "surprising-order-provider" \
    "--spring.datasource.hikari.maximum-pool-size=${ORDER_HIKARI_MAX_POOL_SIZE}" \
    "--spring.datasource.hikari.connection-timeout=${ORDER_HIKARI_CONNECTION_TIMEOUT_MS}" \
    "--surprising.trading.order.risk.market-max-mark-age-ms=15000"
  start_provider "gateway" 9094 "surprising-gateway/surprising-gateway-provider" "surprising-gateway-provider" \
    "--spring.datasource.hikari.maximum-pool-size=${GATEWAY_HIKARI_MAX_POOL_SIZE}" \
    "--spring.datasource.hikari.connection-timeout=${GATEWAY_HIKARI_CONNECTION_TIMEOUT_MS}"
else
  wait_http "matching" 9085
  wait_http "account" 9086
  wait_http "websocket" "${WEBSOCKET_PORT}"
  wait_http "order" 9084
  wait_http "gateway" 9094
fi

echo "Starting websocket captures"
start_kafka_lag_sampler
WS_BTC_DEPTH="${TMP_DIR}/ws-btc-depth.jsonl"
WS_ETH_DEPTH="${TMP_DIR}/ws-eth-depth.jsonl"
WS_PRIVATE_FILES=()
WS_EXTRA_DEPTH="${TMP_DIR}/ws-extra-depth.jsonl"
start_ws_capture "${WS_BTC_DEPTH}" "ws://localhost:${WEBSOCKET_PORT}/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"btc-depth\",\"channel\":\"depth\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_ETH_DEPTH}" "ws://localhost:${WEBSOCKET_PORT}/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"eth-depth\",\"channel\":\"depth\",\"symbol\":\"${ETH_SYMBOL}\"}"
wait_ws_subscribed "${WS_BTC_DEPTH}" "depth" 1
wait_ws_subscribed "${WS_ETH_DEPTH}" "depth" 1
for ((i = 0; i < WS_FANOUT_USER_COUNT; i++)); do
  private_file="${TMP_DIR}/ws-private-${i}.jsonl"
  private_user=$((TAKER_USER_START + i))
  WS_PRIVATE_FILES+=("${private_file}")
  start_ws_capture "${private_file}" "ws://localhost:${WEBSOCKET_PORT}/ws/v1?userId=${private_user}" \
    "{\"op\":\"subscribe\",\"id\":\"orders-${i}\",\"channel\":\"orders\"}" \
    "{\"op\":\"subscribe\",\"id\":\"execution-reports-${i}\",\"channel\":\"executionReports\"}" \
    "{\"op\":\"subscribe\",\"id\":\"positions-${i}\",\"channel\":\"positions\"}"
done
for private_file in "${WS_PRIVATE_FILES[@]}"; do
  wait_ws_subscribed "${private_file}" "orders" 1
  wait_ws_subscribed "${private_file}" "executionReports" 1
  wait_ws_subscribed "${private_file}" "positions" 1
done
if ((EXTRA_WEBSOCKET_NODES > 0)); then
  start_ws_capture "${WS_EXTRA_DEPTH}" "ws://localhost:$((WEBSOCKET_PORT + 100))/ws/v1" \
    "{\"op\":\"subscribe\",\"id\":\"extra-btc-depth\",\"channel\":\"depth\",\"symbol\":\"${BTC_SYMBOL}\"}"
  wait_ws_subscribed "${WS_EXTRA_DEPTH}" "depth" 1
fi

seed_mark_prices
start_mark_refresher

echo "Funding market-maker and ordinary user accounts"
fund_stress_accounts

echo "Placing initial market-maker book"
maker_commands=()
while IFS= read -r command; do
  maker_commands+=("${command}")
done < <(emit_maker_batch_commands "stress-mm-${RUN_ID}" "mm-stress-${RUN_ID}-mm" \
  "${MM_LEVEL_QUANTITY_STEPS}" 0 "${MM_DEPTH_LEVELS}")
if ((${#maker_commands[@]} == 0)); then
  echo "Initial market-maker batch command generation produced no requests" >&2
  exit 1
fi
run_with_concurrency "${MAKER_LOAD_CONCURRENCY}" "${maker_commands[@]}"
if ((RUN_FAILURES > 0)); then
  echo "Initial market-maker order placement failed: ${RUN_FAILURES} batch requests" >&2
  exit 1
fi
initial_maker_count=$((MM_ACCOUNT_COUNT * 2 * 2 * MM_DEPTH_LEVELS))
wait_sql_equals "initial market-maker orders accepted" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'stress-mm-${RUN_ID}-%' AND status = 'ACCEPTED'" \
  "${initial_maker_count}"

if [[ "${MATCHING_NODE_FAILURE_AFTER_OPEN_BOOK}" == "true" ]]; then
  run_matching_node_failure_after_open_book
fi

echo "Running concurrent market-maker refresh and ordinary user taker flow"
refresh_count=0
seed_mark_prices
pg_stat_before="$(collect_pg_stat_snapshot)"
load_start_ns="$(date +%s%N)"
for ((cycle = 1; cycle <= MM_REFRESH_CYCLES; cycle++)); do
  echo "Running market-maker/taker cycle ${cycle}/${MM_REFRESH_CYCLES}"
  maker_refresh_commands=()
  taker_commands=()
  cycle_offset=$(((cycle - 1) * MM_REFRESH_LEVELS))
  while IFS= read -r command; do
    maker_refresh_commands+=("${command}")
  done < <(emit_maker_batch_commands "stress-refresh-${RUN_ID}-${cycle}" \
    "mm-stress-${RUN_ID}-refresh-${cycle}" "${MM_REFRESH_QUANTITY_STEPS}" \
    "$((MM_DEPTH_LEVELS + cycle_offset))" "${MM_REFRESH_LEVELS}")
  if ((${#maker_refresh_commands[@]} == 0)); then
    echo "Market-maker refresh batch command generation produced no requests in cycle ${cycle}" >&2
    exit 1
  fi
  refresh_count=$((refresh_count + MM_ACCOUNT_COUNT * 2 * 2 * MM_REFRESH_LEVELS))

  cycle_taker_start=$(((cycle - 1) * TAKER_ORDER_COUNT))
  for ((i = 0; i < TAKER_ORDER_COUNT; i++)); do
    global_i=$((cycle_taker_start + i))
    user_id=$((TAKER_USER_START + global_i))
    if ((global_i % 2 == 0)); then
      symbol="${BTC_SYMBOL}"
    else
      symbol="${ETH_SYMBOL}"
    fi
    base_price="$(price_ticks_for "${symbol}")"
    taker_depth=$((MM_DEPTH_LEVELS + cycle * MM_REFRESH_LEVELS))
    if ((global_i % 4 < 2)); then
      side="BUY"
      price=$((base_price + taker_depth))
    else
      side="SELL"
      price=$((base_price - taker_depth))
    fi
    taker_commands+=("$(place_order_command "http://localhost:9094/api/v1/gateway/trading" "${user_id}" "stress-user-${RUN_ID}-${global_i}" "${symbol}" "${side}" "IOC" "${price}" "${TAKER_QUANTITY_STEPS}" "mm-stress-${RUN_ID}-user-${global_i}")")
  done

  seed_mark_prices
  (
    run_with_concurrency "${MAKER_LOAD_CONCURRENCY}" "${maker_refresh_commands[@]}"
    exit "${RUN_FAILURES}"
  ) &
  maker_refresh_pid="$!"
  run_with_concurrency "${LOAD_CONCURRENCY}" "${taker_commands[@]}"
  taker_failures="${RUN_FAILURES}"
  set +e
  wait "${maker_refresh_pid}"
  maker_failures="$?"
  set -e
  RUN_FAILURES=$((taker_failures + maker_failures))
  if ((RUN_FAILURES > 0)); then
    echo "Concurrent load placement failed in cycle ${cycle}: ${RUN_FAILURES} requests" >&2
    exit 1
  fi
  if ((cycle < MM_REFRESH_CYCLES && MM_REFRESH_INTERVAL_SECONDS > 0)); then
    sleep "${MM_REFRESH_INTERVAL_SECONDS}"
  fi
done
load_end_ns="$(date +%s%N)"

wait_sql_equals "ordinary user taker orders filled" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'stress-user-${RUN_ID}-%' AND status = 'FILLED'" \
  "${TOTAL_TAKER_ORDER_COUNT}" "${TAKER_FILL_WAIT_SECONDS}"
wait_sql_equals "ordinary user trades written" \
  "SELECT count(*) FROM trading_match_trades WHERE trace_id LIKE 'mm-stress-${RUN_ID}-user-%'" \
  "${TOTAL_TAKER_ORDER_COUNT}" "${TAKER_TRADE_WAIT_SECONDS}"
if [[ "${ACCOUNT_NODE_FAILURE_DURING_SETTLEMENT}" == "true" ]]; then
  echo "Scenario: account node failure during settlement"
  stop_provider "account-2"
  wait_consumer_members "surprising-account-v1" "surprising.perp.match.trades.v1" $((EXTRA_ACCOUNT_NODES * ACCOUNT_CONSUMERS_PER_NODE))
  add_failure_summary "- Account node failure during settlement：stopped account-2 after user trades were written; the account consumer group converged to the surviving node, final Kafka lag reached 0, and the database settled all taker trades."
fi
wait_sql_equals "ordinary user trades settled by account" \
  "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.trace_id LIKE 'mm-stress-${RUN_ID}-user-%'" \
  "${TOTAL_TAKER_ORDER_COUNT}" "${ACCOUNT_SETTLEMENT_WAIT_SECONDS}"
wait_sql_equals "no negative stress balances" \
  "SELECT count(*) FROM account_balances WHERE user_id BETWEEN ${MM_USER_START} AND $((TAKER_USER_START + TOTAL_TAKER_ORDER_COUNT + 100)) AND (available_units < 0 OR locked_units < 0)" \
  "0" 60
wait_sql_equals "no negative product balances" \
  "SELECT count(*) FROM account_product_balances WHERE available_units < 0 OR locked_units < 0" \
  "0" 60
wait_sql_equals "no negative position margins" \
  "SELECT count(*) FROM account_position_margins WHERE margin_units < 0" \
  "0" 60
wait_sql_equals "no over-released margin reservations" \
  "SELECT count(*) FROM account_margin_reservations WHERE released_units + position_margin_units > reserved_units" \
  "0" 60
wait_sql_equals "no over-released spot reservations" \
  "SELECT count(*) FROM account_spot_order_reservations WHERE settled_units + released_units > reserved_units" \
  "0" 60
wait_sql_equals "no account deficits remain" \
  "SELECT count(*) FROM account_deficits WHERE deficit_units <> 0" \
  "0" 60
wait_sql_equals "no product deficits remain" \
  "SELECT count(*) FROM account_product_deficits WHERE deficit_units <> 0" \
  "0" 60
wait_sql_equals "symbol OI table constraint still true" \
  "SELECT count(*) FROM trading_symbol_open_interest WHERE open_quantity_steps <> GREATEST(long_quantity_steps, short_quantity_steps) OR open_quantity_steps < 0" \
  "0" 60
wait_sql_equals "symbol OI matches account positions" \
  "WITH position_oi AS (SELECT symbol, COALESCE(SUM(GREATEST(signed_quantity_steps, 0)), 0) AS long_quantity_steps, COALESCE(SUM(GREATEST(-signed_quantity_steps, 0)), 0) AS short_quantity_steps FROM account_positions GROUP BY symbol), oi_compare AS (SELECT COALESCE(o.symbol, p.symbol) AS symbol, COALESCE(o.long_quantity_steps, 0) AS oi_long, COALESCE(p.long_quantity_steps, 0) AS pos_long, COALESCE(o.short_quantity_steps, 0) AS oi_short, COALESCE(p.short_quantity_steps, 0) AS pos_short, COALESCE(o.open_quantity_steps, 0) AS oi_open FROM trading_symbol_open_interest o FULL OUTER JOIN position_oi p ON p.symbol = o.symbol) SELECT count(*) FROM oi_compare WHERE oi_long <> pos_long OR oi_short <> pos_short OR oi_open <> GREATEST(pos_long, pos_short)" \
  "0" 60
wait_sql_equals "trading outbox drained" \
  "SELECT count(*) FROM trading_outbox_events WHERE published_at IS NULL" \
  "0" "${ACCOUNT_SETTLEMENT_WAIT_SECONDS}"
wait_sql_equals "account outbox drained" \
  "SELECT count(*) FROM account_outbox_events WHERE published_at IS NULL" \
  "0" "${ACCOUNT_SETTLEMENT_WAIT_SECONDS}"
pg_stat_after="$(collect_pg_stat_snapshot)"

sleep 3
websocket_summary="$(assert_ws_events "${WS_BTC_DEPTH}" "${WS_ETH_DEPTH}" "${WS_PRIVATE_FILES[0]}")"
websocket_summary="${websocket_summary} $(assert_ws_private_fanout_events)"
if ((EXTRA_WEBSOCKET_NODES > 0)); then
  websocket_summary="${websocket_summary} $(assert_ws_depth_events "${WS_EXTRA_DEPTH}" "extra_websocket")"
fi

submit_ms="$(python3 - "${load_start_ns}" "${load_end_ns}" <<'PY'
import sys
print(round((int(sys.argv[2]) - int(sys.argv[1])) / 1_000_000, 3))
PY
)"
submit_tps="$(python3 - "${TOTAL_TAKER_ORDER_COUNT}" "${submit_ms}" <<'PY'
import sys
orders = int(sys.argv[1])
ms = float(sys.argv[2])
print(round(orders / (ms / 1000), 2) if ms > 0 else "inf")
PY
)"

matched_latency="$(sql_scalar "
WITH lat AS (
  SELECT EXTRACT(EPOCH FROM (r.event_time - e.event_time)) * 1000 AS ms
    FROM trading_orders o
    JOIN trading_order_events e ON e.order_id = o.order_id AND e.event_type = 'ACCEPTED'
    JOIN trading_match_results r ON r.order_id = o.order_id AND r.command_type = 'PLACE'
   WHERE o.client_order_id LIKE 'stress-user-${RUN_ID}-%'
)
SELECT count(*) || ' ' || round(min(ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(max(ms)::numeric, 3)
  FROM lat")"

account_latency="$(sql_scalar "
WITH lat AS (
  SELECT EXTRACT(EPOCH FROM (p.processed_at - e.event_time)) * 1000 AS ms
    FROM trading_orders o
    JOIN trading_order_events e ON e.order_id = o.order_id AND e.event_type = 'ACCEPTED'
    JOIN trading_match_trades t ON t.taker_order_id = o.order_id
    JOIN account_processed_trades p ON p.symbol = t.symbol AND p.trade_id = t.trade_id
   WHERE o.client_order_id LIKE 'stress-user-${RUN_ID}-%'
)
SELECT count(*) || ' ' || round(min(ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(percentile_disc(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 3) || ' ' ||
       round(max(ms)::numeric, 3)
  FROM lat")"

match_span_tps="$(sql_scalar "
WITH span AS (
  SELECT count(*) AS c,
         EXTRACT(EPOCH FROM (max(event_time) - min(event_time))) AS seconds
    FROM trading_match_trades
   WHERE trace_id LIKE 'mm-stress-${RUN_ID}-user-%'
)
SELECT CASE WHEN seconds > 0 THEN round((c / seconds)::numeric, 2)::text ELSE 'inf' END
  FROM span")"

trade_count="$(query_value "SELECT count(*) FROM trading_match_trades WHERE trace_id LIKE 'mm-stress-${RUN_ID}-user-%'")"
settled_trade_count="$(query_value "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.trace_id LIKE 'mm-stress-${RUN_ID}-user-%'")"
account_metrics="$(collect_account_metrics)"
db_stat_delta="$(format_pg_stat_delta "${pg_stat_before}" "${pg_stat_after}")"
table_counts_summary="$(collect_table_counts_summary)"
provider_metrics_summary="$(collect_provider_metrics_summary)"
append_kafka_lag_sample >>"${KAFKA_LAG_LOG}" 2>&1 || true
touch "${KAFKA_LAG_STOP_FILE}" >/dev/null 2>&1 || true
sleep 1
kafka_lag_summary="$(collect_kafka_lag_summary)"
provider_topology_summary="$(collect_provider_topology_summary)"
machine_cpu="$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo unknown) cores"
machine_mem="$(python3 - <<'PY'
import subprocess
try:
    raw = subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True).strip()
    print(f"{round(int(raw) / (1024 ** 3), 2)} GiB")
except Exception:
    print("unknown")
PY
)"
java_version="$(java -version 2>&1 | head -n 1)"
git_head="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

write_report "${submit_ms}" "${submit_tps}" "${initial_maker_count}" "${refresh_count}" \
  "${TOTAL_TAKER_ORDER_COUNT}" "${trade_count}" "${matched_latency}" "${account_latency}" \
  "${match_span_tps}" "${websocket_summary}" "${machine_cpu}" "${machine_mem}" \
  "${java_version}" "${git_head}" "${account_metrics}" "${kafka_lag_summary}" \
  "${provider_topology_summary}" "${settled_trade_count}" "${db_stat_delta}" \
  "${table_counts_summary}" "${provider_metrics_summary}"

echo "Market-maker stress passed"
echo "report=${REPORT_FILE}"
echo "logs=${TMP_DIR}"
