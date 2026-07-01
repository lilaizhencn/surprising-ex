#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
DB_NAME="surprising_exchange"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
BTC_SYMBOL="BTC-USDT"
ETH_SYMBOL="ETH-USDT"
BTC_TICK_UNITS=10000000
ETH_TICK_UNITS=1000000
BTC_PRICE_TICKS=600000
ETH_PRICE_TICKS=300000
MARK_SEQUENCE_BASE=$((100000 + RUN_SEQ * 100))
NEXT_MARK_SEQUENCE="${MARK_SEQUENCE_BASE}"
QUANTITY_STEPS="${QUANTITY_STEPS:-10}"
PAIR_COUNT="${PAIR_COUNT:-50}"
LOAD_CONCURRENCY="${LOAD_CONCURRENCY:-16}"
BOOK_DEPTH_LEVELS="${BOOK_DEPTH_LEVELS:-60}"
START_INFRA="${START_INFRA:-true}"
STOP_INFRA="${STOP_INFRA:-false}"
BUILD_SERVICES="${BUILD_SERVICES:-true}"
RESET_STATE="${RESET_STATE:-true}"
START_PROVIDERS="${START_PROVIDERS:-true}"
STOP_PROVIDERS="${STOP_PROVIDERS:-true}"
RUN_FAILURE_SCENARIOS="${RUN_FAILURE_SCENARIOS:-true}"
KEEP_TMP="${KEEP_TMP:-false}"
WS_TIMEOUT="${WS_TIMEOUT:-360}"
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

FULL_PRICE_TICKS="${BTC_PRICE_TICKS}"
PARTIAL_PRICE_TICKS=$((BTC_PRICE_TICKS + 100))
CANCEL_PRICE_TICKS=$((BTC_PRICE_TICKS + 10000))
ALL_CANCEL_BID_PRICE=$((BTC_PRICE_TICKS - 10000))
ALL_CANCEL_ASK_PRICE=$((BTC_PRICE_TICKS + 11000))
LOAD_PRICE_TICKS=$((BTC_PRICE_TICKS + 20000))
FAULT_PRICE_TICKS=$((BTC_PRICE_TICKS + 30000))
ACCOUNT_FAULT_PRICE_TICKS=$((BTC_PRICE_TICKS + 40000))
SELF_TRADE_PRICE_TICKS=$((BTC_PRICE_TICKS + 50000))
POST_ONLY_PRICE_TICKS=$((BTC_PRICE_TICKS + 60000))
DEPTH_START_PRICE_TICKS=$((BTC_PRICE_TICKS + 100000))
PARTIAL_MAKER_QTY=$((QUANTITY_STEPS * 2))
PARTIAL_TAKER_QTY=$((QUANTITY_STEPS - 3))
LOAD_QTY="${QUANTITY_STEPS}"
LOAD_TOTAL_QTY=$((PAIR_COUNT * LOAD_QTY))
CLOSE_QTY=4
TOPUP_QTY=10
LIQ_QTY=10
LIQ_MARK_PRICE_TICKS=160000

PROVIDER_NAMES=()
PROVIDER_PIDS=()
PIDS=()

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
    docker start surprising-ex-postgres >/dev/null
  else
    docker run -d --name surprising-ex-postgres \
      --network "${DOCKER_NETWORK}" \
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
  if [[ "${BUILD_SERVICES}" != "true" ]]; then
    return
  fi
  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
    mvn -q -pl :surprising-instrument-provider,:surprising-candlestick-provider,:surprising-index-price-provider,\
:surprising-mark-price-provider,:surprising-order-provider,:surprising-matching-provider,:surprising-account-provider,\
:surprising-risk-provider,:surprising-liquidation-provider,:surprising-funding-provider,:surprising-insurance-provider,\
:surprising-adl-provider,:surprising-websocket-provider,:surprising-gateway-provider -am -DskipTests package
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
    account) echo "surprising-account/surprising-account-provider" ;;
    risk) echo "surprising-risk/surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation/surprising-liquidation-provider" ;;
    funding) echo "surprising-funding/surprising-funding-provider" ;;
    insurance) echo "surprising-insurance/surprising-insurance-provider" ;;
    adl) echo "surprising-adl/surprising-adl-provider" ;;
    websocket) echo "surprising-websocket/surprising-websocket-provider" ;;
    gateway) echo "surprising-gateway/surprising-gateway-provider" ;;
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
    account) echo "surprising-account-provider" ;;
    risk) echo "surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation-provider" ;;
    funding) echo "surprising-funding-provider" ;;
    insurance) echo "surprising-insurance-provider" ;;
    adl) echo "surprising-adl-provider" ;;
    websocket) echo "surprising-websocket-provider" ;;
    gateway) echo "surprising-gateway-provider" ;;
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
  if [[ "${name}" == "matching" ]]; then
    java_args+=(
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
      "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
      "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
      "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
    )
  fi
  echo "Starting ${name} provider on port ${port}"
  (
    cd "${ROOT_DIR}"
    if (( ${#java_args[@]} > 0 )); then
      exec java "${java_args[@]}" -jar "${jar}"
    else
      exec java -jar "${jar}"
    fi
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

adjust_insurance_fund() {
  local amount_units="$1"
  local reference_id="$2"
  gateway_post "insurance/admin/fund-adjustments" 1 "real-config-${RUN_ID}-${reference_id}" "{
      \"asset\": \"USDT\",
      \"amountUnits\": ${amount_units},
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"FULL_STACK_REAL_CONFIG_TEST\"
    }" >/dev/null
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

wait_position_symbol() {
  local symbol="$1"
  local user_id="$2"
  local signed_quantity="$3"
  local entry_price="$4"
  wait_sql_equals "position ${symbol} user=${user_id} quantity=${signed_quantity}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE user_id = ${user_id} AND symbol = '${symbol}'), '0:0')" \
    "${signed_quantity}:${entry_price}"
}

wait_position() {
  wait_position_symbol "${BTC_SYMBOL}" "$@"
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
    if data.get("updateType") == "DELTA" and last_sequence is not None and previous != last_sequence:
        raise SystemExit(f"depth sequence gap: previous={previous} expected={last_sequence}")
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
  python3 - "${file}" "${user_id}" "${expected_position}" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
user_id = int(sys.argv[2])
expected_position = int(sys.argv[3])
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
if not orders:
    raise SystemExit(f"no websocket order events for user {user_id}")
if expected_position != 0 and not matches:
    raise SystemExit(f"no websocket match events for user {user_id}")
if expected_position != 0 and not any((m.get("data") or {}).get("signedQuantitySteps") == expected_position for m in positions):
    raise SystemExit(f"missing websocket position {expected_position} for user {user_id}")
print(f"user={user_id} orders={len(orders)} matches={len(matches)} positions={len(positions)}")
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
    "${CANCEL_USER}" "${CLOSE_MAKER_USER}" "${FAULT_MAKER_USER}" "${FAULT_TAKER_USER}" \
    "${ACCOUNT_FAULT_MAKER_USER}" "${ACCOUNT_FAULT_TAKER_USER}" "${SELF_TRADE_USER}" \
    "${POST_ONLY_MAKER_USER}" "${POST_ONLY_TAKER_USER}" "${DEPTH_USER}" \
    "${TOPUP_MAKER_USER}" "${LIQ_OPEN_MAKER_USER}" "${LIQ_CLOSE_MAKER_USER}" "${ADL_OPEN_MAKER_USER}" \
    "${ADL_TARGET_USER}"; do
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
  for pid in "${active_pids[@]}"; do
    wait "${pid}"
  done
}

assert_no_negative_balances() {
  wait_sql_equals "no negative account balances" \
    "SELECT count(*) FROM account_balances WHERE available_units < 0 OR locked_units < 0" \
    "0"
  wait_sql_equals "no over-released margin reservations" \
    "SELECT count(*) FROM account_margin_reservations WHERE released_units + position_margin_units > reserved_units" \
    "0"
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

if [[ "${RESET_STATE}" == "true" ]]; then
  echo "Resetting default database ${DB_NAME}"
  reset_database
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

  for provider in instrument candlestick index-price mark-price matching account risk liquidation funding insurance adl websocket order gateway; do
    start_provider "${provider}"
  done
else
  echo "Reusing already running providers"
  for provider in instrument candlestick index-price mark-price matching account risk liquidation funding insurance adl websocket order gateway; do
    wait_http "${provider}" "$(provider_port "${provider}")"
  done
fi

echo "Starting WebSocket captures"
WS_DEPTH_LOG="${TMP_DIR}/ws-depth.jsonl"
WS_FULL_TAKER_LOG="${TMP_DIR}/ws-full-taker.jsonl"
WS_PARTIAL_MAKER_LOG="${TMP_DIR}/ws-partial-maker.jsonl"
WS_CANCEL_LOG="${TMP_DIR}/ws-cancel.jsonl"
WS_MARK_LOG="${TMP_DIR}/ws-mark.jsonl"
WS_FUNDING_LOG="${TMP_DIR}/ws-funding.jsonl"
start_ws_capture "${WS_DEPTH_LOG}" "ws://localhost:9093/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"depth\",\"channel\":\"depth\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_FULL_TAKER_LOG}" "ws://localhost:9093/ws/v1?userId=${FULL_TAKER_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_PARTIAL_MAKER_LOG}" "ws://localhost:9093/ws/v1?userId=${PARTIAL_MAKER_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_CANCEL_LOG}" "ws://localhost:9093/ws/v1?userId=${CANCEL_USER}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-orders\",\"channel\":\"orders\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-matches\",\"channel\":\"matches\",\"symbol\":\"${BTC_SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-positions\",\"channel\":\"positions\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_MARK_LOG}" "ws://localhost:9093/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"mark\",\"channel\":\"mark\",\"symbol\":\"${BTC_SYMBOL}\"}"
start_ws_capture "${WS_FUNDING_LOG}" "ws://localhost:9093/ws/v1" \
  "{\"op\":\"subscribe\",\"id\":\"funding\",\"channel\":\"funding\",\"symbol\":\"${BTC_SYMBOL}\"}"

wait_ws_subscribed "${WS_DEPTH_LOG}" "depth" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "orders" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "matches" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "positions" 1
wait_ws_subscribed "${WS_PARTIAL_MAKER_LOG}" "orders" 1
wait_ws_subscribed "${WS_CANCEL_LOG}" "orders" 1
wait_ws_subscribed "${WS_MARK_LOG}" "mark" 1
wait_ws_subscribed "${WS_FUNDING_LOG}" "funding" 1

echo "Publishing price inputs through Kafka"
publish_price_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" 2001 "${BTC_PRICE_TICKS}"
publish_price_inputs "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 2001 "${ETH_PRICE_TICKS}"
publish_price_inputs_until_mark_event

echo "Funding users through gateway"
fund_users

echo "Scenario: full fill"
full_maker_order="$(place_order "${FULL_MAKER_USER}" "real-full-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}" false false)"
wait_order_result "${full_maker_order}" "SUCCESS"
assert_order_book_ask "full maker REST book" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}"
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

echo "Scenario: partial fill then cancel"
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
close_order="$(place_order "${FULL_TAKER_USER}" "real-active-close-${RUN_ID}" "SELL" "MARKET" "IOC" 0 "${CLOSE_QTY}" true false)"
wait_order_state "${close_order}" "FILLED" "${CLOSE_QTY}" "0"
wait_position "${FULL_TAKER_USER}" "$((QUANTITY_STEPS - CLOSE_QTY))" "${FULL_PRICE_TICKS}"
wait_position "${CLOSE_MAKER_USER}" "${CLOSE_QTY}" "${FULL_PRICE_TICKS}"

echo "Scenario: risk controls"
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
for ((i = 0; i < BOOK_DEPTH_LEVELS; i++)); do
  place_order "${DEPTH_USER}" "real-depth-${RUN_ID}-${i}" "SELL" "LIMIT" "GTC" "$((DEPTH_START_PRICE_TICKS + i))" 1 false false >/dev/null
done
wait_sql_equals "deep book open orders" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'real-depth-${RUN_ID}-%' AND status = 'ACCEPTED'" \
  "${BOOK_DEPTH_LEVELS}"
assert_order_book_depth_levels "${BOOK_DEPTH_LEVELS}"

echo "Scenario: funding rate publish and settlement"
publish_price_inputs "${BTC_SYMBOL}" "${BTC_TICK_UNITS}" 3001 "${BTC_PRICE_TICKS}"
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
topup_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_MAKER_USER}" "real-topup-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${ETH_PRICE_TICKS}" "${TOPUP_QTY}" false false)"
wait_order_result "${topup_maker_order}" "SUCCESS"
topup_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "real-topup-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${ETH_PRICE_TICKS}" "${TOPUP_QTY}" false false)"
wait_order_state "${topup_order}" "FILLED" "${TOPUP_QTY}" "0"
wait_position_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "${TOPUP_QTY}" "${ETH_PRICE_TICKS}"
insert_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 4001 288200
wait_sql_equals "top-up user reaches warning risk" \
  "SELECT COALESCE((SELECT status FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC LIMIT 1), '')" \
  "WARNING"
adjust_balance "${TOPUP_USER}" 1000000000 "real-config-topup-recovery-${RUN_ID}"
wait_sql_equals "top-up user recovers to normal risk" \
  "SELECT COALESCE((SELECT status FROM risk_account_snapshots WHERE user_id = ${TOPUP_USER} AND settle_asset = 'USDT' ORDER BY event_time DESC LIMIT 1), '')" \
  "NORMAL"
topup_close_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_MAKER_USER}" "real-topup-close-maker-${RUN_ID}" "BUY" "LIMIT" "GTC" "${ETH_PRICE_TICKS}" "${TOPUP_QTY}" true false)"
wait_order_result "${topup_close_maker_order}" "SUCCESS"
topup_close_order="$(place_order_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "real-topup-close-${RUN_ID}" "SELL" "LIMIT" "IOC" "${ETH_PRICE_TICKS}" "${TOPUP_QTY}" true false)"
wait_order_state "${topup_close_order}" "FILLED" "${TOPUP_QTY}" "0"
wait_position_symbol "${ETH_SYMBOL}" "${TOPUP_USER}" "0" "0"
wait_position_symbol "${ETH_SYMBOL}" "${TOPUP_MAKER_USER}" "0" "0"
wait_sql_equals "top-up user latest risk position is flat" \
  "SELECT signed_quantity_steps || ':' || notional_units || ':' || maintenance_margin_units FROM risk_position_snapshots WHERE user_id = ${TOPUP_USER} AND symbol = '${ETH_SYMBOL}' ORDER BY event_time DESC, snapshot_id DESC LIMIT 1" \
  "0:0:0"

echo "Scenario: liquidation links risk, liquidation, matching, account, and insurance"
adjust_insurance_fund 100000000000 "real-config-insurance-seed-${RUN_ID}"
refresh_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" "${ETH_PRICE_TICKS}"
liq_open_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${LIQ_OPEN_MAKER_USER}" "real-liq-open-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${ETH_PRICE_TICKS}" "${LIQ_QTY}" false false)"
wait_order_result "${liq_open_maker_order}" "SUCCESS"
liq_open_order="$(place_order_symbol "${ETH_SYMBOL}" "${LIQ_USER}" "real-liq-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${ETH_PRICE_TICKS}" "${LIQ_QTY}" false false)"
wait_order_state "${liq_open_order}" "FILLED" "${LIQ_QTY}" "0"
wait_position_symbol "${ETH_SYMBOL}" "${LIQ_USER}" "${LIQ_QTY}" "${ETH_PRICE_TICKS}"
liq_close_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${LIQ_CLOSE_MAKER_USER}" "real-liq-close-maker-${RUN_ID}" "BUY" "LIMIT" "GTC" "${LIQ_MARK_PRICE_TICKS}" "${LIQ_QTY}" false false)"
wait_order_result "${liq_close_maker_order}" "SUCCESS"
publish_price_inputs "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 5002 "${LIQ_MARK_PRICE_TICKS}"
refresh_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" "${LIQ_MARK_PRICE_TICKS}"
wait_sql_nonzero "liquidation candidate created" \
  "SELECT count(*) FROM risk_liquidation_candidates WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}'"
wait_sql_nonzero "liquidation order submitted" \
  "SELECT count(*) FROM liquidation_orders WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}' AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED')"
liq_order_id="$(query_value "SELECT order_id FROM liquidation_orders WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}' ORDER BY created_at DESC LIMIT 1")"
wait_order_state "${liq_order_id}" "FILLED" "${LIQ_QTY}" "0"
wait_sql_equals "liquidation audit filled" \
  "SELECT status FROM liquidation_orders WHERE order_id = ${liq_order_id}" \
  "FILLED"
wait_position_symbol "${ETH_SYMBOL}" "${LIQ_USER}" "0" "0"
wait_sql_equals "liquidation candidate completed" \
  "SELECT status FROM risk_liquidation_candidates WHERE user_id = ${LIQ_USER} AND symbol = '${ETH_SYMBOL}' ORDER BY event_time DESC LIMIT 1" \
  "COMPLETED"
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
insert_mark_price "${ETH_SYMBOL}" "${ETH_TICK_UNITS}" 6001 360000
adl_maker_order="$(place_order_symbol "${ETH_SYMBOL}" "${ADL_OPEN_MAKER_USER}" "real-adl-open-maker-${RUN_ID}" "SELL" "LIMIT" "GTC" "${ETH_PRICE_TICKS}" "${LIQ_QTY}" false false)"
wait_order_result "${adl_maker_order}" "SUCCESS"
adl_target_order="$(place_order_symbol "${ETH_SYMBOL}" "${ADL_TARGET_USER}" "real-adl-target-long-${RUN_ID}" "BUY" "LIMIT" "IOC" "${ETH_PRICE_TICKS}" "${LIQ_QTY}" false false)"
wait_order_state "${adl_target_order}" "FILLED" "${LIQ_QTY}" "0"
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

echo "Asserting websocket and accounting invariants"
wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
sleep 5
assert_ws_depth "${WS_DEPTH_LOG}"
assert_ws_private "${WS_FULL_TAKER_LOG}" "${FULL_TAKER_USER}" "$((QUANTITY_STEPS - CLOSE_QTY))"
assert_ws_private "${WS_PARTIAL_MAKER_LOG}" "${PARTIAL_MAKER_USER}" "-${PARTIAL_TAKER_QTY}"
assert_ws_private "${WS_CANCEL_LOG}" "${CANCEL_USER}" "0"
assert_ws_public_channel "${WS_MARK_LOG}" "mark" "${BTC_SYMBOL}"
assert_ws_public_channel "${WS_FUNDING_LOG}" "funding" "${BTC_SYMBOL}"
assert_no_negative_balances
wait_sql_equals "unpublished trading outbox drained" \
  "SELECT count(*) FROM trading_outbox_events WHERE published_at IS NULL AND attempts > 0" \
  "0"
wait_sql_equals "unpublished account outbox drained" \
  "SELECT count(*) FROM account_outbox_events WHERE published_at IS NULL AND attempts > 0" \
  "0"

touch "${WS_STOP_FILE}"

echo "Full-stack real-config smoke passed"
echo "logs=${TMP_DIR}"
echo "pairs=${PAIR_COUNT} depthLevels=${BOOK_DEPTH_LEVELS} loadTotalQuantity=${LOAD_TOTAL_QTY}"
