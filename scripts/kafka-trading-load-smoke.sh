#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
BOOTSTRAP_SERVERS="${BOOTSTRAP_SERVERS:-localhost:9092}"
DB_USER="${DB_USER:-surprising}"
DB_PASSWORD="${DB_PASSWORD:-surprising}"
RUN_ID="${RUN_ID:-$(date +%s%N)}"
RUN_SEQ=$((RUN_ID % 1000000000))
DB_NAME="${DB_NAME:-surprising_load_${RUN_SEQ}}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/${DB_NAME}}"
SYMBOL="${SYMBOL:-BTC-USDT}"
PRICE_TICKS="${PRICE_TICKS:-600000}"
TICK_UNITS="${TICK_UNITS:-10000000}"
QUANTITY_STEPS="${QUANTITY_STEPS:-10}"
PAIR_COUNT="${PAIR_COUNT:-50}"
LOAD_CONCURRENCY="${LOAD_CONCURRENCY:-16}"
START_INFRA="${START_INFRA:-false}"
STOP_INFRA="${STOP_INFRA:-false}"
BUILD_SERVICES="${BUILD_SERVICES:-true}"
WS_TIMEOUT="${WS_TIMEOUT:-240}"
WEBSOCKET_PORT="${WEBSOCKET_PORT:-9097}"
KEEP_TMP="${KEEP_TMP:-false}"
TMP_DIR="$(mktemp -d /tmp/surprising-kafka-load-smoke.XXXXXX)"
WS_STOP_FILE="${TMP_DIR}/ws.stop"
WEBSOCKET_GROUP_ID="surprising-websocket-load-${RUN_ID}"
COMPOSE_BIN=""
COMPOSE_SUBCOMMAND=""
INFRA_MODE="${INFRA_MODE:-local}"
DOCKER_NETWORK="${DOCKER_NETWORK:-surprising-ex-net}"
KAFKA_IMAGE="${KAFKA_IMAGE:-apache/kafka:3.7.0}"

FULL_MAKER_USER=$((7000000000 + RUN_SEQ))
FULL_TAKER_USER=$((7100000000 + RUN_SEQ))
PARTIAL_MAKER_USER=$((7200000000 + RUN_SEQ))
PARTIAL_TAKER_USER=$((7300000000 + RUN_SEQ))
CANCEL_USER=$((7400000000 + RUN_SEQ))
ALL_CANCEL_USER_START=$((7500000000 + RUN_SEQ * 10))
LOAD_MAKER_START=$((9000000000 + RUN_SEQ * 1000))
LOAD_TAKER_START=$((9500000000 + RUN_SEQ * 1000))

FULL_PRICE_TICKS="${PRICE_TICKS}"
PARTIAL_PRICE_TICKS=$((PRICE_TICKS + 100))
CANCEL_PRICE_TICKS=$((PRICE_TICKS + 10000))
ALL_CANCEL_BID_PRICE=$((PRICE_TICKS - 10000))
ALL_CANCEL_ASK_PRICE=$((PRICE_TICKS + 11000))
LOAD_PRICE_TICKS=$((PRICE_TICKS + 20000))
PARTIAL_MAKER_QTY=$((QUANTITY_STEPS * 2))
PARTIAL_TAKER_QTY=$((QUANTITY_STEPS - 3))
LOAD_QTY="${QUANTITY_STEPS}"
LOAD_TOTAL_QTY=$((PAIR_COUNT * LOAD_QTY))

PIDS=()

cleanup() {
  touch "${WS_STOP_FILE}" >/dev/null 2>&1 || true
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
  compose exec -T "$@"
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
  if [[ "${INFRA_MODE}" == "compose" ]]; then
    compose down
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

postgres_exec() {
  compose_exec postgres env PGPASSWORD="${DB_PASSWORD}" "$@"
}

psql_exec() {
  compose_exec postgres env PGPASSWORD="${DB_PASSWORD}" \
    psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 "$@"
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
if [[ -n "${COMPOSE_SUBCOMMAND:-}" ]]; then
  exec "${COMPOSE_BIN}" "${COMPOSE_SUBCOMMAND}" -f "${COMPOSE_FILE}" exec -T kafka kafka-topics.sh "$@"
fi
exec "${COMPOSE_BIN}" -f "${COMPOSE_FILE}" exec -T kafka kafka-topics.sh "$@"
EOF
  chmod +x "${TMP_DIR}/bin/kafka-topics.sh"
}

package_services() {
  if [[ "${BUILD_SERVICES}" != "true" ]]; then
    return
  fi
  JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}" \
    mvn -q -pl :surprising-order-provider,:surprising-matching-provider,:surprising-account-provider,:surprising-websocket-provider \
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
  local extra_env=()
  if [[ "${name}" == "matching" ]]; then
    java_args+=(
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
      "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED"
      "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED"
      "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
      "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
    )
  fi
  if [[ "${name}" == "websocket" ]]; then
    extra_env+=("SURPRISING_WEBSOCKET_KAFKA_GROUP_ID=${WEBSOCKET_GROUP_ID}")
  fi
  echo "Starting ${name} provider on port ${port}"
  (
    cd "${ROOT_DIR}"
    env \
      "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}" \
      "SPRING_DATASOURCE_USERNAME=${DB_USER}" \
      "SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}" \
      "${kafka_env_name}=${BOOTSTRAP_SERVERS}" \
      "${extra_env[@]}" \
      java "${java_args[@]}" -jar "${jar}"
  ) >"${log_file}" 2>&1 &
  PIDS+=("$!")
  wait_http "${name}" "http://localhost:${port}/actuator/health"
}

json_field() {
  local field="$1"
  python3 -c "import json,sys; print(json.load(sys.stdin)['${field}'])"
}

place_order() {
  local user_id="$1"
  local client_order_id="$2"
  local side="$3"
  local tif="$4"
  local price_ticks="$5"
  local quantity_steps="$6"
  local response
  response="$(curl -fsS -X POST "http://localhost:9084/api/v1/trading/orders" \
    -H "Content-Type: application/json" \
    -H "X-Trace-Id: load-smoke-${RUN_ID}-${client_order_id}" \
    -d "{
      \"userId\": ${user_id},
      \"clientOrderId\": \"${client_order_id}\",
      \"symbol\": \"${SYMBOL}\",
      \"side\": \"${side}\",
      \"orderType\": \"LIMIT\",
      \"timeInForce\": \"${tif}\",
      \"priceTicks\": ${price_ticks},
      \"quantitySteps\": ${quantity_steps},
      \"reduceOnly\": false,
      \"postOnly\": false
    }")"
  printf '%s\n' "${response}" | json_field orderId
}

cancel_order() {
  local user_id="$1"
  local order_id="$2"
  curl -fsS -X POST "http://localhost:9084/api/v1/trading/orders/cancel" \
    -H "Content-Type: application/json" \
    -H "X-Trace-Id: load-smoke-${RUN_ID}-cancel-${order_id}" \
    -d "{\"userId\": ${user_id}, \"orderId\": ${order_id}}" >/dev/null
}

assert_order_book_ask() {
  local description="$1"
  local price_ticks="$2"
  local quantity_steps="$3"
  local response
  response="$(curl -fsS "http://localhost:9085/api/v1/trading/market/orderbook?symbol=${SYMBOL}&depth=5")"
  SNAPSHOT_JSON="${response}" python3 - "${description}" "${price_ticks}" "${quantity_steps}" <<'PY'
import json
import os
import sys

description = sys.argv[1]
price_ticks = int(sys.argv[2])
quantity_steps = int(sys.argv[3])
snapshot = json.loads(os.environ["SNAPSHOT_JSON"])
if snapshot.get("symbol") is None or snapshot.get("sequence") is None:
    raise SystemExit(f"{description}: invalid snapshot envelope")
for level in snapshot.get("asks") or []:
    if level.get("priceTicks") == price_ticks and level.get("quantitySteps") == quantity_steps:
        print(f"{description}: snapshot sequence={snapshot.get('sequence')}")
        break
else:
    raise SystemExit(f"{description}: missing ask price={price_ticks} quantity={quantity_steps}")
PY
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
      \"reason\": \"KAFKA_LOAD_SMOKE_DEPOSIT\"
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

wait_place_matched() {
  local order_id="$1"
  wait_sql_equals "matching PLACE success for order ${order_id}" \
    "SELECT count(*) FROM trading_match_results WHERE order_id = ${order_id} AND command_type = 'PLACE' AND result_code = 'SUCCESS'" \
    "1"
}

wait_position() {
  local user_id="$1"
  local signed_quantity="$2"
  local entry_price="$3"
  wait_sql_equals "position user=${user_id} quantity=${signed_quantity}" \
    "SELECT COALESCE((SELECT signed_quantity_steps || ':' || entry_price_ticks FROM account_positions WHERE user_id = ${user_id} AND symbol = '${SYMBOL}'), '0:0')" \
    "${signed_quantity}:${entry_price}"
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

start_ws_capture() {
  local name="$1"
  local url="$2"
  local output="$3"
  shift 3
  local args=(python3 "${ROOT_DIR}/scripts/ws_capture.py" --url "${url}" --output "${output}" --stop-file "${WS_STOP_FILE}" --timeout "${WS_TIMEOUT}")
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
  python3 - "${file}" "${SYMBOL}" "${FULL_PRICE_TICKS}" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_TAKER_QTY}" <<'PY'
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
events = [
    m for m in messages
    if m.get("op") == "event" and m.get("channel") == "depth" and m.get("symbol") == symbol
]
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
if not matches and expected_position != 0:
    raise SystemExit(f"no websocket match events for user {user_id}")
if expected_position != 0:
    if not any((m.get("data") or {}).get("signedQuantitySteps") == expected_position for m in positions):
        raise SystemExit(f"missing websocket position {expected_position} for user {user_id}")
print(f"user={user_id} orders={len(orders)} matches={len(matches)} positions={len(positions)}")
PY
}

fund_users() {
  adjust_balance "${FULL_MAKER_USER}" "load-smoke-full-maker-${RUN_ID}"
  adjust_balance "${FULL_TAKER_USER}" "load-smoke-full-taker-${RUN_ID}"
  adjust_balance "${PARTIAL_MAKER_USER}" "load-smoke-partial-maker-${RUN_ID}"
  adjust_balance "${PARTIAL_TAKER_USER}" "load-smoke-partial-taker-${RUN_ID}"
  adjust_balance "${CANCEL_USER}" "load-smoke-cancel-${RUN_ID}"
  for i in 0 1 2 3; do
    adjust_balance "$((ALL_CANCEL_USER_START + i))" "load-smoke-all-cancel-${RUN_ID}-${i}"
  done
  for ((i = 0; i < PAIR_COUNT; i++)); do
    adjust_balance "$((LOAD_MAKER_START + i))" "load-smoke-maker-${RUN_ID}-${i}"
    adjust_balance "$((LOAD_TAKER_START + i))" "load-smoke-taker-${RUN_ID}-${i}"
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

require_command curl
require_command python3
require_command psql
require_command pg_isready
require_command kafka-topics
require_command kafka-consumer-groups
if [[ "${INFRA_MODE}" != "local" ]]; then
  require_command docker
  detect_compose
fi

if [[ "${SYMBOL}" != "BTC-USDT" ]]; then
  echo "This smoke script currently expects init.sql seeded symbol BTC-USDT; got ${SYMBOL}" >&2
  exit 1
fi
if (( PARTIAL_TAKER_QTY <= 0 )); then
  echo "QUANTITY_STEPS must be greater than 3 for the partial-fill scenario" >&2
  exit 1
fi

if [[ "${START_INFRA}" == "true" ]]; then
  echo "Starting ${INFRA_MODE} infrastructure"
  start_infra
fi

wait_until "PostgreSQL" 120 compose_exec postgres pg_isready -U "${DB_USER}"
wait_until "Kafka" 120 compose_exec kafka kafka-topics.sh --bootstrap-server localhost:9092 --list

echo "Ensuring load-smoke database ${DB_NAME}"
ensure_smoke_database

echo "Applying init.sql"
psql_exec -f - < "${ROOT_DIR}/init.sql" >/dev/null
seed_mark_price

echo "Creating Kafka topics"
export COMPOSE_FILE COMPOSE_BIN COMPOSE_SUBCOMMAND INFRA_MODE
create_topic_wrapper
PATH="${TMP_DIR}/bin:${PATH}" "${ROOT_DIR}/scripts/create-topics.sh" >/dev/null

package_services

start_provider "matching" 9085 "surprising-trading/surprising-matching-provider" \
  "surprising-matching-provider" "SURPRISING_TRADING_MATCHING_KAFKA_BOOTSTRAP_SERVERS"
start_provider "account" 9086 "surprising-account/surprising-account-provider" \
  "surprising-account-provider" "SURPRISING_ACCOUNT_KAFKA_BOOTSTRAP_SERVERS"
start_provider "websocket" "${WEBSOCKET_PORT}" "surprising-edge/surprising-websocket/surprising-websocket-provider" \
  "surprising-websocket-provider" "SURPRISING_WEBSOCKET_KAFKA_BOOTSTRAP_SERVERS"
start_provider "order" 9084 "surprising-trading/surprising-order-provider" \
  "surprising-order-provider" "SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS"

WS_DEPTH_LOG="${TMP_DIR}/ws-depth.jsonl"
WS_FULL_TAKER_LOG="${TMP_DIR}/ws-full-taker.jsonl"
WS_PARTIAL_MAKER_LOG="${TMP_DIR}/ws-partial-maker.jsonl"
WS_CANCEL_LOG="${TMP_DIR}/ws-cancel.jsonl"

start_ws_capture "depth" "ws://localhost:${WEBSOCKET_PORT}/ws/v1" "${WS_DEPTH_LOG}" \
  "{\"op\":\"subscribe\",\"id\":\"depth\",\"channel\":\"depth\",\"symbol\":\"${SYMBOL}\"}"
start_ws_capture "full-taker" "ws://localhost:${WEBSOCKET_PORT}/ws/v1?userId=${FULL_TAKER_USER}" "${WS_FULL_TAKER_LOG}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-orders\",\"channel\":\"orders\",\"symbol\":\"${SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-matches\",\"channel\":\"matches\",\"symbol\":\"${SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"ft-positions\",\"channel\":\"positions\",\"symbol\":\"${SYMBOL}\"}"
start_ws_capture "partial-maker" "ws://localhost:${WEBSOCKET_PORT}/ws/v1?userId=${PARTIAL_MAKER_USER}" "${WS_PARTIAL_MAKER_LOG}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-orders\",\"channel\":\"orders\",\"symbol\":\"${SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-matches\",\"channel\":\"matches\",\"symbol\":\"${SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"pm-positions\",\"channel\":\"positions\",\"symbol\":\"${SYMBOL}\"}"
start_ws_capture "cancel-user" "ws://localhost:${WEBSOCKET_PORT}/ws/v1?userId=${CANCEL_USER}" "${WS_CANCEL_LOG}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-orders\",\"channel\":\"orders\",\"symbol\":\"${SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-matches\",\"channel\":\"matches\",\"symbol\":\"${SYMBOL}\"}" \
  "{\"op\":\"subscribe\",\"id\":\"cu-positions\",\"channel\":\"positions\",\"symbol\":\"${SYMBOL}\"}"

wait_ws_subscribed "${WS_DEPTH_LOG}" "depth" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "orders" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "matches" 1
wait_ws_subscribed "${WS_FULL_TAKER_LOG}" "positions" 1
wait_ws_subscribed "${WS_PARTIAL_MAKER_LOG}" "orders" 1
wait_ws_subscribed "${WS_CANCEL_LOG}" "orders" 1

seed_mark_price

echo "Funding users"
fund_users

echo "Scenario: full fill"
seed_mark_price
full_maker_order="$(place_order "${FULL_MAKER_USER}" "load-full-maker-${RUN_ID}" "SELL" "GTC" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}")"
wait_place_matched "${full_maker_order}"
assert_order_book_ask "full maker REST book" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}"
full_taker_order="$(place_order "${FULL_TAKER_USER}" "load-full-taker-${RUN_ID}" "BUY" "IOC" "${FULL_PRICE_TICKS}" "${QUANTITY_STEPS}")"
wait_order_state "${full_maker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
wait_order_state "${full_taker_order}" "FILLED" "${QUANTITY_STEPS}" "0"
wait_position "${FULL_MAKER_USER}" "-${QUANTITY_STEPS}" "${FULL_PRICE_TICKS}"
wait_position "${FULL_TAKER_USER}" "${QUANTITY_STEPS}" "${FULL_PRICE_TICKS}"

echo "Scenario: partial fill then cancel"
seed_mark_price
partial_maker_order="$(place_order "${PARTIAL_MAKER_USER}" "load-partial-maker-${RUN_ID}" "SELL" "GTC" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_MAKER_QTY}")"
wait_place_matched "${partial_maker_order}"
assert_order_book_ask "partial maker REST book" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_MAKER_QTY}"
partial_taker_order="$(place_order "${PARTIAL_TAKER_USER}" "load-partial-taker-${RUN_ID}" "BUY" "IOC" "${PARTIAL_PRICE_TICKS}" "${PARTIAL_TAKER_QTY}")"
wait_order_state "${partial_maker_order}" "PARTIALLY_FILLED" "${PARTIAL_TAKER_QTY}" "$((PARTIAL_MAKER_QTY - PARTIAL_TAKER_QTY))"
wait_order_state "${partial_taker_order}" "FILLED" "${PARTIAL_TAKER_QTY}" "0"
wait_position "${PARTIAL_MAKER_USER}" "-${PARTIAL_TAKER_QTY}" "${PARTIAL_PRICE_TICKS}"
wait_position "${PARTIAL_TAKER_USER}" "${PARTIAL_TAKER_QTY}" "${PARTIAL_PRICE_TICKS}"
cancel_order "${PARTIAL_MAKER_USER}" "${partial_maker_order}"
wait_order_state "${partial_maker_order}" "CANCELED" "${PARTIAL_TAKER_QTY}" "0"

echo "Scenario: single open order cancel without fill"
seed_mark_price
cancel_order_id="$(place_order "${CANCEL_USER}" "load-cancel-only-${RUN_ID}" "SELL" "GTC" "${CANCEL_PRICE_TICKS}" "${QUANTITY_STEPS}")"
wait_place_matched "${cancel_order_id}"
cancel_order "${CANCEL_USER}" "${cancel_order_id}"
wait_order_state "${cancel_order_id}" "CANCELED" "0" "0"
wait_position "${CANCEL_USER}" "0" "0"

echo "Scenario: cancel all open orders"
seed_mark_price
all_cancel_orders=()
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 0))" "load-all-cancel-0-${RUN_ID}" "BUY" "GTC" "${ALL_CANCEL_BID_PRICE}" "${QUANTITY_STEPS}")")
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 1))" "load-all-cancel-1-${RUN_ID}" "BUY" "GTC" "$((ALL_CANCEL_BID_PRICE + 1))" "${QUANTITY_STEPS}")")
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 2))" "load-all-cancel-2-${RUN_ID}" "SELL" "GTC" "${ALL_CANCEL_ASK_PRICE}" "${QUANTITY_STEPS}")")
all_cancel_orders+=("$(place_order "$((ALL_CANCEL_USER_START + 3))" "load-all-cancel-3-${RUN_ID}" "SELL" "GTC" "$((ALL_CANCEL_ASK_PRICE + 1))" "${QUANTITY_STEPS}")")
for order_id in "${all_cancel_orders[@]}"; do
  wait_place_matched "${order_id}"
done
for i in "${!all_cancel_orders[@]}"; do
  cancel_order "$((ALL_CANCEL_USER_START + i))" "${all_cancel_orders[$i]}"
done
for order_id in "${all_cancel_orders[@]}"; do
  wait_order_state "${order_id}" "CANCELED" "0" "0"
done
wait_sql_equals "all cancel leaves no open orders" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'load-all-cancel-%-${RUN_ID}' AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')" \
  "0"

echo "Scenario: concurrent load PAIR_COUNT=${PAIR_COUNT} LOAD_CONCURRENCY=${LOAD_CONCURRENCY}"
seed_mark_price
maker_commands=()
taker_commands=()
for ((i = 0; i < PAIR_COUNT; i++)); do
  maker_commands+=("curl -fsS -X POST 'http://localhost:9084/api/v1/trading/orders' -H 'Content-Type: application/json' -H 'X-Trace-Id: load-smoke-${RUN_ID}-maker-${i}' -d '{\"userId\": $((LOAD_MAKER_START + i)), \"clientOrderId\": \"load-maker-${RUN_ID}-${i}\", \"symbol\": \"${SYMBOL}\", \"side\": \"SELL\", \"orderType\": \"LIMIT\", \"timeInForce\": \"GTC\", \"priceTicks\": ${LOAD_PRICE_TICKS}, \"quantitySteps\": ${LOAD_QTY}, \"reduceOnly\": false, \"postOnly\": false}' >/dev/null")
done
run_with_concurrency "${LOAD_CONCURRENCY}" "${maker_commands[@]}"
wait_sql_equals "all load maker PLACE commands matched" \
  "SELECT count(*) FROM trading_match_results r JOIN trading_orders o ON o.order_id = r.order_id WHERE o.client_order_id LIKE 'load-maker-${RUN_ID}-%' AND r.command_type = 'PLACE' AND r.result_code = 'SUCCESS'" \
  "${PAIR_COUNT}"

for ((i = 0; i < PAIR_COUNT; i++)); do
  taker_commands+=("curl -fsS -X POST 'http://localhost:9084/api/v1/trading/orders' -H 'Content-Type: application/json' -H 'X-Trace-Id: load-smoke-${RUN_ID}-taker-${i}' -d '{\"userId\": $((LOAD_TAKER_START + i)), \"clientOrderId\": \"load-taker-${RUN_ID}-${i}\", \"symbol\": \"${SYMBOL}\", \"side\": \"BUY\", \"orderType\": \"LIMIT\", \"timeInForce\": \"IOC\", \"priceTicks\": ${LOAD_PRICE_TICKS}, \"quantitySteps\": ${LOAD_QTY}, \"reduceOnly\": false, \"postOnly\": false}' >/dev/null")
done
run_with_concurrency "${LOAD_CONCURRENCY}" "${taker_commands[@]}"

wait_sql_equals "load maker orders filled" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'load-maker-${RUN_ID}-%' AND status = 'FILLED' AND executed_quantity_steps = ${LOAD_QTY} AND remaining_quantity_steps = 0" \
  "${PAIR_COUNT}"
wait_sql_equals "load taker orders filled" \
  "SELECT count(*) FROM trading_orders WHERE client_order_id LIKE 'load-taker-${RUN_ID}-%' AND status = 'FILLED' AND executed_quantity_steps = ${LOAD_QTY} AND remaining_quantity_steps = 0" \
  "${PAIR_COUNT}"
wait_sql_equals "load trade count and quantity" \
  "SELECT count(*) || ':' || COALESCE(sum(quantity_steps), 0) FROM trading_match_trades WHERE symbol = '${SYMBOL}' AND maker_user_id BETWEEN ${LOAD_MAKER_START} AND $((LOAD_MAKER_START + PAIR_COUNT - 1)) AND taker_user_id BETWEEN ${LOAD_TAKER_START} AND $((LOAD_TAKER_START + PAIR_COUNT - 1))" \
  "${PAIR_COUNT}:${LOAD_TOTAL_QTY}"
wait_sql_equals "load account processed trades" \
  "SELECT count(*) FROM account_processed_trades p JOIN trading_match_trades t ON t.symbol = p.symbol AND t.trade_id = p.trade_id WHERE t.symbol = '${SYMBOL}' AND t.maker_user_id BETWEEN ${LOAD_MAKER_START} AND $((LOAD_MAKER_START + PAIR_COUNT - 1)) AND t.taker_user_id BETWEEN ${LOAD_TAKER_START} AND $((LOAD_TAKER_START + PAIR_COUNT - 1))" \
  "${PAIR_COUNT}"
wait_sql_equals "load maker positions" \
  "SELECT count(*) FROM account_positions WHERE symbol = '${SYMBOL}' AND user_id BETWEEN ${LOAD_MAKER_START} AND $((LOAD_MAKER_START + PAIR_COUNT - 1)) AND signed_quantity_steps = -${LOAD_QTY}" \
  "${PAIR_COUNT}"
wait_sql_equals "load taker positions" \
  "SELECT count(*) FROM account_positions WHERE symbol = '${SYMBOL}' AND user_id BETWEEN ${LOAD_TAKER_START} AND $((LOAD_TAKER_START + PAIR_COUNT - 1)) AND signed_quantity_steps = ${LOAD_QTY}" \
  "${PAIR_COUNT}"
wait_sql_equals "no open load orders remain" \
  "SELECT count(*) FROM trading_orders WHERE (client_order_id LIKE 'load-maker-${RUN_ID}-%' OR client_order_id LIKE 'load-taker-${RUN_ID}-%') AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')" \
  "0"
wait_sql_equals "no negative load balances" \
  "SELECT count(*) FROM account_balances WHERE ((user_id BETWEEN ${LOAD_MAKER_START} AND $((LOAD_MAKER_START + PAIR_COUNT - 1))) OR (user_id BETWEEN ${LOAD_TAKER_START} AND $((LOAD_TAKER_START + PAIR_COUNT - 1)))) AND (available_units < 0 OR locked_units < 0)" \
  "0"

wait_consumer_group_lag_zero "surprising-account-v1" "surprising.perp.match.trades.v1"
wait_consumer_group_lag_zero "${WEBSOCKET_GROUP_ID}" "surprising.perp.orderbook.depth.v1"
wait_consumer_group_lag_zero "${WEBSOCKET_GROUP_ID}" "surprising.account.position.events.v1"
sleep 2

echo "Asserting WebSocket push data"
assert_ws_depth "${WS_DEPTH_LOG}"
assert_ws_private "${WS_FULL_TAKER_LOG}" "${FULL_TAKER_USER}" "${QUANTITY_STEPS}"
assert_ws_private "${WS_PARTIAL_MAKER_LOG}" "${PARTIAL_MAKER_USER}" "-${PARTIAL_TAKER_QTY}"
assert_ws_private "${WS_CANCEL_LOG}" "${CANCEL_USER}" "0"

touch "${WS_STOP_FILE}"

echo "Kafka trading load smoke passed"
echo "Full fill maker=${full_maker_order} taker=${full_taker_order}"
echo "Partial maker=${partial_maker_order} taker=${partial_taker_order}"
echo "Concurrent pairs=${PAIR_COUNT} totalQuantity=${LOAD_TOTAL_QTY}"
