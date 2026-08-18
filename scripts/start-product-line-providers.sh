#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
RUN_ID="${RUN_ID:?RUN_ID must be explicit}"
ACTION="${ACTION:-up}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
RUNTIME_ROOT="${RUNTIME_ROOT:-${TMPDIR:-/tmp}/surprising-product-line-runtime}"
RUN_DIR="$RUNTIME_ROOT/$RUN_ID"
PID_DIR="$RUN_DIR/pids"
LOG_DIR="$RUN_DIR/logs"
READY_FILE="$RUN_DIR/ready.tsv"
OWNER_FILE="$RUN_DIR/owner"
LOCK_DIR="$RUNTIME_ROOT/active.lock"
LOCK_OWNER="$LOCK_DIR/owner"
POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-postgres}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
VALKEY_HOST="${VALKEY_HOST:-127.0.0.1}"
VALKEY_PORT="${VALKEY_PORT:-6379}"
AERON_CLUSTER_HOSTNAMES="${AERON_CLUSTER_HOSTNAMES:-127.0.0.1,127.0.0.1,127.0.0.1}"
AERON_EGRESS_HOSTNAME="${AERON_EGRESS_HOSTNAME:-127.0.0.1}"
BUILD_CHANGED="${BUILD_CHANGED:-false}"

readonly SERVICES=(instrument exporter projector price account trading market-data derivatives-lifecycle funding gateway maker)
readonly HTTP_SERVICES=(instrument price account trading market-data derivatives-lifecycle funding gateway maker)
readonly HTTP_PORTS=(9080 9082 9086 9084 9081 9087 9089 9094 9096)

fail() {
  printf 'ERROR=%s\n' "$*" >&2
  exit 2
}

case "$PRODUCT_LINE" in
  SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
  *) fail "unsupported PRODUCT_LINE=$PRODUCT_LINE" ;;
esac
case "$ACTION" in
  up|fresh|down|status|test|dry-run) ;;
  *) fail "unsupported ACTION=$ACTION" ;;
esac
case "$BUILD_CHANGED" in true|false) ;; *) fail 'BUILD_CHANGED must be true or false' ;; esac
[[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || fail "invalid RUN_ID=$RUN_ID"

service_enabled() {
  case "$1" in
    funding) [[ "$PRODUCT_LINE" == LINEAR_PERPETUAL || "$PRODUCT_LINE" == INVERSE_PERPETUAL ]] ;;
    derivatives-lifecycle) [[ "$PRODUCT_LINE" != SPOT ]] ;;
    *) return 0 ;;
  esac
}

service_port() {
  local index
  for index in "${!HTTP_SERVICES[@]}"; do
    [[ "${HTTP_SERVICES[$index]}" == "$1" ]] && { printf '%s' "${HTTP_PORTS[$index]}"; return; }
  done
}

jar_path() {
  case "$1" in
    core) printf '%s/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar' "$ROOT_DIR" ;;
    tools) printf '%s/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar' "$ROOT_DIR" ;;
    exporter|projector) printf '%s/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar' "$ROOT_DIR" ;;
    instrument) printf '%s/surprising-instrument/surprising-instrument-provider/target/surprising-instrument-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    market-data) printf '%s/surprising-market-data/surprising-market-data-provider/target/surprising-market-data-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    price) printf '%s/surprising-price/surprising-price-provider/target/surprising-price-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    trading) printf '%s/surprising-trading/surprising-trading-provider/target/surprising-trading-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    account) printf '%s/surprising-account/surprising-account-provider/target/surprising-account-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    derivatives-lifecycle) printf '%s/surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider/target/surprising-derivatives-lifecycle-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    funding) printf '%s/surprising-funding/surprising-funding-provider/target/surprising-funding-provider-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    gateway) printf '%s/surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    maker) printf '%s/surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT-exec.jar' "$ROOT_DIR" ;;
    *) fail "unknown service=$1" ;;
  esac
}

preflight_port() {
  nc -z "$1" "$2" >/dev/null 2>&1 || fail "dependency unavailable host=$1 port=$2"
}

preflight() {
  [[ -x "$JAVA_HOME/bin/java" ]] || fail "JDK 25 unavailable JAVA_HOME=$JAVA_HOME"
  command -v docker >/dev/null || fail 'docker unavailable'
  command -v curl >/dev/null || fail 'curl unavailable'
  command -v nc >/dev/null || fail 'nc unavailable'
  docker info >/dev/null 2>&1 || fail 'docker daemon unavailable'
  preflight_port "$POSTGRES_HOST" "$POSTGRES_PORT"
  preflight_port "${KAFKA_BOOTSTRAP_SERVERS%:*}" "${KAFKA_BOOTSTRAP_SERVERS##*:}"
  preflight_port "$VALKEY_HOST" "$VALKEY_PORT"
}

build_artifacts() {
  if [[ "$BUILD_CHANGED" == true ]]; then
    BUILD_BASE="${BUILD_BASE:-HEAD}" "$ROOT_DIR/scripts/build-incremental.sh" --changed
  fi
  local service missing=()
  for service in core tools "${SERVICES[@]}"; do
    service_enabled "$service" || continue
    [[ -f "$(jar_path "$service")" ]] || missing+=("$service")
  done
  (( ${#missing[@]} == 0 )) || fail "artifacts missing services=${missing[*]}; build affected modules first"
}

postgres_container() {
  docker ps --filter publish="$POSTGRES_PORT" --format '{{.ID}}' | head -1
}

postgres_exec() {
  local container
  container="$(postgres_container)"
  [[ -n "$container" ]] || fail "PostgreSQL container exposing port $POSTGRES_PORT not found"
  docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" -i "$container" \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
}

initialize_database() {
  local initialized
  initialized="$(postgres_exec -Atqc "SELECT to_regclass('public.instruments') IS NOT NULL")"
  if [[ "$initialized" != t ]]; then
    postgres_exec < "$ROOT_DIR/init.sql" >/dev/null
  fi
  local migration
  for migration in "$ROOT_DIR"/surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/*.sql; do
    postgres_exec < "$migration" >/dev/null
  done
  printf 'DATABASE=READY database=%s\n' "$POSTGRES_DB"
}

assert_lock_available() {
  if [[ -f "$LOCK_OWNER" && "$(<"$LOCK_OWNER")" != "$RUN_ID" ]]; then
    fail "another product line is active runId=$(<"$LOCK_OWNER")"
  fi
}

claim_runtime() {
  assert_lock_available
  mkdir -p "$PID_DIR" "$LOG_DIR" "$LOCK_DIR"
  printf '%s\n' "$RUN_ID" > "$LOCK_OWNER"
  printf '%s\n' "$RUN_ID" > "$OWNER_FILE"
  : > "$READY_FILE"
}

mark_ready() {
  printf '%s\t%s\n' "$(( $(wc -l < "$READY_FILE") + 1 ))" "$1" >> "$READY_FILE"
  printf 'READY=%s\n' "$1"
}

port_owned_by_process_tree() {
  local port="$1" owner_pid="$2" listener current parent depth
  while IFS= read -r listener; do
    current="$listener"; depth=0
    while [[ "$current" =~ ^[0-9]+$ ]] && (( depth < 12 )); do
      [[ "$current" == "$owner_pid" ]] && return 0
      parent="$(ps -p "$current" -o ppid= 2>/dev/null | tr -d ' ' || true)"
      [[ -n "$parent" && "$parent" != "$current" ]] || break
      current="$parent"; depth=$((depth + 1))
    done
  done < <(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u)
  return 1
}

start_owned_process() {
  local name="$1" port="$2" pid label
  shift 2
  if command -v launchctl >/dev/null 2>&1; then
    label="com.surprising.product-line.${RUN_ID//[^a-zA-Z0-9.-]/-}.$name"
    launchctl remove "$label" >/dev/null 2>&1 || true
    launchctl submit -l "$label" -o "$LOG_DIR/$name.log" -e "$LOG_DIR/$name.log" -- \
      /bin/bash -c 'cd "$0"; exec "$@"' "$RUN_DIR" "$@"
    printf '%s\n' "$label" > "$PID_DIR/$name.label"
    local pid_deadline=$((SECONDS + 10))
    until pid="$(launchctl print "gui/$(id -u)/$label" 2>/dev/null | awk '/pid =/{print $3; exit}')" && [[ "$pid" =~ ^[0-9]+$ ]]; do
      (( SECONDS < pid_deadline )) || fail "launch timeout name=$name log=$LOG_DIR/$name.log"
      sleep 1
    done
  else
    nohup bash -c 'cd "$0"; exec "$@"' "$RUN_DIR" "$@" \
      >"$LOG_DIR/$name.log" 2>&1 < /dev/null &
    pid=$!
  fi
  printf '%s\n' "$pid" > "$PID_DIR/$name.pid"
  if [[ -n "$port" ]]; then
    local deadline=$((SECONDS + 120))
    until port_owned_by_process_tree "$port" "$pid" && curl --fail --silent --max-time 2 \
      "http://127.0.0.1:$port/actuator/health" >/dev/null; do
      kill -0 "$pid" 2>/dev/null || fail "service exited name=$name log=$LOG_DIR/$name.log"
      (( SECONDS < deadline )) || fail "health timeout name=$name port=$port log=$LOG_DIR/$name.log"
      sleep 1
    done
  else
    sleep 2
    kill -0 "$pid" 2>/dev/null || fail "service exited name=$name log=$LOG_DIR/$name.log"
  fi
  mark_ready "$name"
}

COMMON_ENV=(
    env \
    PRODUCT_LINE="$PRODUCT_LINE" WALLET_ENABLED=false \
    AERON_CLUSTER_HOSTNAMES="$AERON_CLUSTER_HOSTNAMES" AERON_HOSTNAMES="$AERON_CLUSTER_HOSTNAMES" \
    AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" AERON_CLIENT_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" SPRING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_INSTRUMENT_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_ACCOUNT_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_PRICE_CONSUMER_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_PRICE_INDEX_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_PRICE_MARK_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_TRADING_MATCHING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_FUNDING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://$POSTGRES_HOST:$POSTGRES_PORT/$POSTGRES_DB" \
    SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
    ACCOUNT_DB_URL="jdbc:postgresql://$POSTGRES_HOST:$POSTGRES_PORT/$POSTGRES_DB" \
    ACCOUNT_DB_USERNAME="$POSTGRES_USER" ACCOUNT_DB_PASSWORD="$POSTGRES_PASSWORD" \
    DATABASE_URL="jdbc:postgresql://$POSTGRES_HOST:$POSTGRES_PORT/$POSTGRES_DB" \
    DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
    REDIS_HOST="$VALKEY_HOST" REDIS_PORT="$VALKEY_PORT" \
    SURPRISING_PRICE_INDEX_HTTP_PROXY_ENABLED=true SURPRISING_PRICE_INDEX_HTTP_PROXY_HOST=127.0.0.1 \
    SURPRISING_PRICE_INDEX_HTTP_PROXY_PORT=7897
)

start_http_service() {
  local service="$1" port
  port="$(service_port "$service")"
  start_owned_process "$service" "$port" "${COMMON_ENV[@]}" SERVER_PORT="$port" \
    "$JAVA_HOME/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -jar "$(jar_path "$service")"
}

start_background_service() {
  local service="$1" main_class="$2"
  start_owned_process "$service" '' "${COMMON_ENV[@]}" \
    "$JAVA_HOME/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp "$(jar_path "$service")" "$main_class"
}

start_core() {
  local mode="${1:-up}" node
  if [[ "$mode" == fresh && -d "$RUN_DIR/aeron" ]]; then
    mv "$RUN_DIR/aeron" "$RUN_DIR/aeron.previous.$(date +%s)"
  fi
  mkdir -p "$RUN_DIR/aeron"
  for node in 0 1 2; do
    start_owned_process "core-node$node" '' "${COMMON_ENV[@]}" AERON_CORE_THREADING_MODE=DEDICATED \
      "$JAVA_HOME/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -Dsurprising.aeron.product-line="$PRODUCT_LINE" \
      -Dsurprising.aeron.node-id="$node" \
      -Dsurprising.aeron.hostnames="$AERON_CLUSTER_HOSTNAMES" \
      -Dsurprising.aeron.data-dir="$RUN_DIR/aeron" \
      -Dsurprising.aeron.core.threading-mode=DEDICATED \
      -jar "$(jar_path core)"
  done
  local deadline=$((SECONDS + 90))
  until "${COMMON_ENV[@]}" "$JAVA_HOME/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="$PRODUCT_LINE" \
    -Dsurprising.aeron.hostnames="$AERON_CLUSTER_HOSTNAMES" \
    -Dsurprising.aeron.egress-hostname="$AERON_EGRESS_HOSTNAME" \
    -Dsurprising.aeron.probe-mode=query -Dsurprising.aeron.source-id=910001 \
    -cp "$(jar_path tools)" com.surprising.aeron.tools.ClusterProbeMain >"$LOG_DIR/core-probe.log" 2>&1; do
    (( SECONDS < deadline )) || fail "Aeron Core readiness timeout log=$LOG_DIR/core-probe.log"
    sleep 2
  done
  mark_ready core-cluster
}

start_stack() {
  local core_action="$1"
  preflight
  build_artifacts
  claim_runtime
  trap 'cleanup_failed_start' EXIT ERR INT TERM
  initialize_database
  start_http_service instrument
  start_core "$core_action"
  start_background_service exporter com.surprising.aeron.exporter.ExporterMain
  start_background_service projector com.surprising.aeron.exporter.ProjectionMain
  start_http_service price
  start_http_service account
  start_http_service trading
  start_http_service market-data
  service_enabled derivatives-lifecycle && start_http_service derivatives-lifecycle
  service_enabled funding && start_http_service funding
  start_http_service gateway
  start_http_service maker
  trap - EXIT ERR INT TERM
  printf 'PRODUCT_LINE_RUNTIME=PASS productLine=%s runId=%s wallet=ABSENT\n' "$PRODUCT_LINE" "$RUN_ID"
}

stop_processes() {
  [[ -d "$PID_DIR" ]] || return 0
  local pid_file pid deadline
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    local label_file="${pid_file%.pid}.label"
    if [[ -f "$label_file" ]]; then
      launchctl remove "$(<"$label_file")" >/dev/null 2>&1 || true
      rm -f "$label_file"
    else
      kill -0 "$pid" 2>/dev/null && kill -TERM "$pid" 2>/dev/null || true
    fi
  done
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"; deadline=$((SECONDS + 15))
    while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
    rm -f "$pid_file"
  done
}

stop_stack() {
  if [[ -f "$OWNER_FILE" && "$(<"$OWNER_FILE")" != "$RUN_ID" ]]; then fail "runtime ownership mismatch"; fi
  stop_processes
  if [[ -f "$LOCK_OWNER" && "$(<"$LOCK_OWNER")" == "$RUN_ID" ]]; then rm -f "$LOCK_OWNER"; rmdir "$LOCK_DIR" 2>/dev/null || true; fi
  printf 'PRODUCT_LINE_RUNTIME=STOPPED productLine=%s runId=%s\n' "$PRODUCT_LINE" "$RUN_ID"
}

cleanup_failed_start() {
  local status=$?
  trap - ERR INT TERM
  printf 'START_FAILED productLine=%s cleanup=begin\n' "$PRODUCT_LINE" >&2
  stop_stack || true
  exit "$status"
}

run_test() {
  stop_stack >/dev/null 2>&1 || true
  start_stack fresh
  trap 'stop_stack' EXIT INT TERM
  local manifest="$RUN_DIR/product-line-test.manifest"
  "${COMMON_ENV[@]}" W4_LIFECYCLE_AUTHORITY=CORE W4_DERIVATIVES_LIFECYCLE_URL=http://127.0.0.1:9087 \
    "$JAVA_HOME/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="$PRODUCT_LINE" \
    -Dsurprising.aeron.hostnames="$AERON_CLUSTER_HOSTNAMES" \
    -Dsurprising.aeron.egress-hostname="$AERON_EGRESS_HOSTNAME" \
    -Dsurprising.aeron.lifecycle-manifest="$manifest" \
    -Dsurprising.aeron.lifecycle-seed="${TEST_SEED:-16001}" \
    -cp "$(jar_path tools)" com.surprising.aeron.tools.ProductLineLifecycleQaMain
  grep -q '^TEST_STATUS=PASS$' "$manifest" || fail "test manifest failed path=$manifest"
  grep -q '^FUNDS_DIFFERENCE=0$' "$manifest" || fail "funds reconciliation failed path=$manifest"
  printf 'PRODUCT_LINE_TEST=PASS productLine=%s runId=%s scope=FULL_HTTP_AERON\n' "$PRODUCT_LINE" "$RUN_ID"
}

print_status() {
  [[ -f "$LOCK_OWNER" && "$(<"$LOCK_OWNER")" == "$RUN_ID" ]] || fail "runtime not active runId=$RUN_ID"
  local pid_file pid
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    kill -0 "$pid" 2>/dev/null || fail "process not running service=$(basename "$pid_file" .pid)"
    printf 'PROCESS=RUNNING service=%s pid=%s\n' "$(basename "$pid_file" .pid)" "$pid"
  done
}

print_dry_run() {
  printf 'DRY_RUN=PASS\nPRODUCT_LINE=%s\nRUN_ID=%s\nCORE_MODE=HOST_THREE_NODE_DEDICATED\n' "$PRODUCT_LINE" "$RUN_ID"
  local service
  printf 'START_ORDER=instrument,host-core-node0,host-core-node1,host-core-node2,exporter,projector,price,account,trading,market-data'
  service_enabled derivatives-lifecycle && printf ',derivatives-lifecycle'
  service_enabled funding && printf ',funding'
  printf ',gateway,maker\nWALLET=ABSENT\nPOSTGRES=%s:%s/%s\nKAFKA=%s\nVALKEY=%s:%s\n' \
    "$POSTGRES_HOST" "$POSTGRES_PORT" "$POSTGRES_DB" "$KAFKA_BOOTSTRAP_SERVERS" "$VALKEY_HOST" "$VALKEY_PORT"
}

export JAVA_HOME PRODUCT_LINE RUN_ID AERON_CLUSTER_HOSTNAMES AERON_EGRESS_HOSTNAME
export POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD KAFKA_BOOTSTRAP_SERVERS
export VALKEY_HOST VALKEY_PORT
case "$ACTION" in
  up) start_stack up ;;
  fresh) stop_stack >/dev/null 2>&1 || true; start_stack fresh ;;
  down) stop_stack ;;
  status) print_status ;;
  test) run_test ;;
  dry-run) print_dry_run ;;
esac
