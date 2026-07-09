#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOOTSTRAP_SERVERS="${BOOTSTRAP_SERVERS:-localhost:9092}"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
DB_NAME="${DB_NAME:-surprising_smoke_${RUN_SEQ}}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/${DB_NAME}}"
MAKER_USER=$((7000000000 + RUN_SEQ))
TAKER_USER=$((8000000000 + RUN_SEQ))
SYMBOL="${SYMBOL:-BTC-USDT}"
PRICE_TICKS="${PRICE_TICKS:-600000}"
TICK_UNITS="${TICK_UNITS:-10000000}"
QUANTITY_STEPS="${QUANTITY_STEPS:-10}"
START_INFRA="${START_INFRA:-false}"
STOP_INFRA="${STOP_INFRA:-false}"
BUILD_SERVICES="${BUILD_SERVICES:-true}"
KEEP_TMP="${KEEP_TMP:-false}"
TMP_DIR="$(mktemp -d /tmp/surprising-kafka-smoke.XXXXXX)"
INFRA_MODE="${INFRA_MODE:-local}"
DOCKER_NETWORK="${DOCKER_NETWORK:-surprising-ex-net}"
KAFKA_IMAGE="${KAFKA_IMAGE:-apache/kafka:3.7.0}"

PIDS=()

cleanup() {
  if (( ${#PIDS[@]} > 0 )); then
    for pid in "${PIDS[@]}"; do
      if kill -0 "${pid}" >/dev/null 2>&1; then
        kill "${pid}" >/dev/null 2>&1 || true
      fi
    done
    for pid in "${PIDS[@]}"; do
      wait "${pid}" >/dev/null 2>&1 || true
    done
  fi
  if [[ "${STOP_INFRA}" == "true" ]]; then
    stop_infra >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_TMP}" == "true" ]]; then
    echo "Keeping smoke logs in ${TMP_DIR}" >&2
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

detect_docker() {
  if command -v docker >/dev/null 2>&1 && docker version >/dev/null 2>&1; then
    INFRA_MODE="docker"
    return
  fi
  echo "Missing running Docker environment: need a working docker daemon" >&2
  exit 1
}

infra_exec() {
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
  if [[ "${INFRA_MODE}" == "docker" ]]; then
    if [[ "${service}" == "kafka" && "$#" -gt 0 && "$1" == kafka-*.sh ]]; then
      set -- "/opt/kafka/bin/$1" "${@:2}"
    fi
    docker exec -i "surprising-ex-${service}" "$@"
    return
  fi
  echo "Unsupported INFRA_MODE=${INFRA_MODE}; use local or docker" >&2
  exit 1
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
  docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1 || docker network create "${DOCKER_NETWORK}" >/dev/null
  if docker container inspect surprising-ex-postgres >/dev/null 2>&1; then
    docker start surprising-ex-postgres >/dev/null
  else
    docker run -d --name surprising-ex-postgres \
      --network "${DOCKER_NETWORK}" \
      -e POSTGRES_DB=surprising_exchange \
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
  fi
}

stop_infra() {
  if [[ "${INFRA_MODE}" == "local" ]]; then
    return
  fi
  if [[ "${INFRA_MODE}" == "docker" ]]; then
    docker rm -f surprising-ex-kafka surprising-ex-postgres >/dev/null 2>&1 || true
  fi
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
  local url="$2"
  wait_until "${name} health" 120 bash -c "curl -fsS '${url}' | grep -q 'UP'"
}

psql_exec() {
  infra_exec postgres env PGPASSWORD="${DB_PASSWORD}" \
    psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 "$@"
}

postgres_exec() {
  infra_exec postgres env PGPASSWORD="${DB_PASSWORD}" "$@"
}

ensure_smoke_database() {
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

seed_mark_price() {
  local sequence
  local price
  local bid
  local ask
  local units
  sequence=$((($(date +%s%N) / 1000000) % 2000000000))
  price="$(decimal_price "${PRICE_TICKS}" "${TICK_UNITS}")"
  bid="$(decimal_price "$((PRICE_TICKS - 1))" "${TICK_UNITS}")"
  ask="$(decimal_price "$((PRICE_TICKS + 1))" "${TICK_UNITS}")"
  units=$((PRICE_TICKS * TICK_UNITS))
  psql_exec <<SQL >/dev/null
INSERT INTO price_index_ticks (
    symbol, sequence, index_price, status, component_count, valid_component_count,
    total_configured_weight, event_time
) VALUES
    ('${SYMBOL}', ${sequence}, ${price}, 'HEALTHY', 5, 5, 5.000000000000000000, now())
ON CONFLICT (symbol, sequence) DO NOTHING;

INSERT INTO price_mark_ticks (
    symbol, sequence, mark_price, mark_price_units, index_price, price1, price2,
    last_trade_price, best_bid_price, best_ask_price, funding_rate, next_funding_time,
    time_until_funding_seconds, basis_average, basis_window_seconds, clamp_low, clamp_high,
    status, event_time
) VALUES
    (
        '${SYMBOL}', ${sequence}, ${price}, ${units}, ${price}, ${price}, ${price},
        ${price}, ${bid}, ${ask}, 0.000000000000000000, now() + interval '8 hours',
        28800, 0.000000000000000000, 60,
        (${price} * 0.970000000000000000),
        (${price} * 1.030000000000000000),
        'HEALTHY', now()
    )
ON CONFLICT (symbol, sequence) DO NOTHING;
SQL
}

wait_sql_equals() {
  local description="$1"
  local sql="$2"
  local expected="$3"
  local actual
  local deadline=$((SECONDS + 120))
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

create_topic_wrapper() {
  mkdir -p "${TMP_DIR}/bin"
  cat > "${TMP_DIR}/bin/kafka-topics.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${INFRA_MODE:-}" == "docker" ]]; then
  exec docker exec -i surprising-ex-kafka /opt/kafka/bin/kafka-topics.sh "$@"
fi
if [[ "${INFRA_MODE:-}" == "local" ]]; then
  exec kafka-topics "$@"
fi
echo "Unsupported INFRA_MODE=${INFRA_MODE:-}; use local or docker" >&2
exit 1
EOF
  chmod +x "${TMP_DIR}/bin/kafka-topics.sh"
}

package_services() {
  if [[ "${BUILD_SERVICES}" != "true" ]]; then
    return
  fi
  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
    mvn -q -pl :surprising-order-provider,:surprising-matching-provider,:surprising-account-provider \
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

start_provider() {
  local name="$1"
  local port="$2"
  local module_path="$3"
  local artifact="$4"
  local kafka_env_name="$5"
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
      env \
        "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}" \
        "SPRING_DATASOURCE_USERNAME=${DB_USER}" \
        "SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}" \
        "${kafka_env_name}=${BOOTSTRAP_SERVERS}" \
        java "${java_args[@]}" -jar "${jar}"
    else
      env \
        "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}" \
        "SPRING_DATASOURCE_USERNAME=${DB_USER}" \
        "SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}" \
        "${kafka_env_name}=${BOOTSTRAP_SERVERS}" \
        java -jar "${jar}"
    fi
  ) >"${log_file}" 2>&1 &
  PIDS+=("$!")
  wait_http "${name}" "http://localhost:${port}/actuator/health"
}

place_order() {
  local user_id="$1"
  local client_order_id="$2"
  local side="$3"
  local tif="$4"
  curl -fsS -X POST "http://localhost:9084/api/v1/trading/orders" \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": ${user_id},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${SYMBOL}\",
      \"side\": \"${side}\",
      \"orderType\": \"LIMIT\",
      \"timeInForce\": \"${tif}\",
      \"priceTicks\": ${PRICE_TICKS},
      \"quantitySteps\": ${QUANTITY_STEPS},
      \"reduceOnly\": false,
      \"postOnly\": false
    }" >/dev/null
}

adjust_balance() {
  local user_id="$1"
  local reference_id="$2"
  curl -fsS -X POST "http://localhost:9086/api/v1/accounts/admin/balance-adjustments" \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": ${user_id},
      \"asset\": \"USDT\",
      \"amountUnits\": 100000000000,
      \"referenceId\": \"${reference_id}\",
      \"reason\": \"KAFKA_SMOKE_DEPOSIT\"
    }" >/dev/null
}

wait_consumer_group_lag_zero() {
  local group="$1"
  local topic="$2"
  local output
  local deadline=$((SECONDS + 120))
  while true; do
    output="$(infra_exec kafka kafka-consumer-groups.sh \
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
  payload="$(query_value "SELECT payload::text FROM trading_outbox_events WHERE aggregate_type = 'MATCH_TRADE' AND event_key = '${SYMBOL}' ORDER BY created_at DESC LIMIT 1")"
  if [[ -z "${payload}" ]]; then
    echo "Could not find MATCH_TRADE outbox payload for duplicate replay" >&2
    exit 1
  fi
  printf '%s\n' "${payload}" | infra_exec kafka kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic surprising.perp.match.trades.v1 >/dev/null
}

require_command curl
require_command python3
require_command psql
require_command pg_isready
require_command kafka-topics
require_command kafka-console-producer
require_command kafka-consumer-groups
if [[ "${INFRA_MODE}" != "local" ]]; then
  require_command docker
  detect_docker
fi

if [[ "${START_INFRA}" == "true" ]]; then
  echo "Starting ${INFRA_MODE} infrastructure"
  start_infra
fi

wait_until "PostgreSQL" 120 infra_exec postgres pg_isready -U "${DB_USER}"
wait_until "Kafka" 120 infra_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

echo "Ensuring smoke database ${DB_NAME}"
ensure_smoke_database

echo "Applying init.sql"
psql_exec -f - < "${ROOT_DIR}/init.sql" >/dev/null
seed_mark_price

echo "Creating Kafka topics"
export INFRA_MODE
create_topic_wrapper
PATH="${TMP_DIR}/bin:${PATH}" "${ROOT_DIR}/scripts/create-topics.sh" >/dev/null

package_services

start_provider "matching" 9085 "surprising-trading/surprising-matching-provider" \
  "surprising-matching-provider" "SURPRISING_TRADING_MATCHING_KAFKA_BOOTSTRAP_SERVERS"
start_provider "account" 9086 "surprising-account/surprising-account-provider" \
  "surprising-account-provider" "SURPRISING_ACCOUNT_KAFKA_BOOTSTRAP_SERVERS"
start_provider "order" 9084 "surprising-trading/surprising-order-provider" \
  "surprising-order-provider" "SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS"

seed_mark_price

echo "Funding smoke users maker=${MAKER_USER} taker=${TAKER_USER}"
adjust_balance "${MAKER_USER}" "kafka-smoke-maker-${RUN_ID}"
adjust_balance "${TAKER_USER}" "kafka-smoke-taker-${RUN_ID}"

echo "Placing crossing orders through REST"
place_order "${MAKER_USER}" "kafka-smoke-maker-${RUN_ID}" "SELL" "GTC"
place_order "${TAKER_USER}" "kafka-smoke-taker-${RUN_ID}" "BUY" "IOC"

positions_sql="SELECT COALESCE(string_agg(user_id || ':' || signed_quantity_steps || ':' || entry_price_ticks, ',' ORDER BY user_id), '') FROM account_positions WHERE symbol = '${SYMBOL}' AND user_id IN (${MAKER_USER}, ${TAKER_USER})"
expected_positions="${MAKER_USER}:-${QUANTITY_STEPS}:${PRICE_TICKS},${TAKER_USER}:${QUANTITY_STEPS}:${PRICE_TICKS}"
wait_sql_equals "account positions after Kafka match" "${positions_sql}" "${expected_positions}"
wait_sql_equals "match trade persistence" "SELECT count(*) FROM trading_match_trades WHERE symbol = '${SYMBOL}' AND taker_user_id = ${TAKER_USER} AND maker_user_id = ${MAKER_USER}" "1"
wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"

before_positions="$(query_value "${positions_sql}")"
before_processed="$(query_value "SELECT count(*) FROM account_processed_trades WHERE symbol = '${SYMBOL}'")"
publish_duplicate_match_trade
wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
after_positions="$(query_value "${positions_sql}")"
after_processed="$(query_value "SELECT count(*) FROM account_processed_trades WHERE symbol = '${SYMBOL}'")"

if [[ "${before_positions}" != "${after_positions}" || "${before_processed}" != "${after_processed}" ]]; then
  echo "Duplicate match-trade replay changed account state" >&2
  echo "before_positions=${before_positions}" >&2
  echo "after_positions=${after_positions}" >&2
  echo "before_processed=${before_processed}" >&2
  echo "after_processed=${after_processed}" >&2
  exit 1
fi

echo "Kafka trading smoke passed"
echo "Positions: ${after_positions}"
