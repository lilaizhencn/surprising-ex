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
JFR_DIR="$RUN_DIR/jfr"
READY_FILE="$RUN_DIR/ready.tsv"
OWNER_FILE="$RUN_DIR/owner"
LOCK_DIR="$RUNTIME_ROOT/active.lock"
LOCK_OWNER="$LOCK_DIR/owner"
JVM_XMS="${JVM_XMS:-512m}"
JVM_XMX="${JVM_XMX:-512m}"
JVM_GC="${JVM_GC:-ZGC}"
JFR_ENABLED="${JFR_ENABLED:-false}"
JFR_SETTINGS="${JFR_SETTINGS:-profile}"
JFR_STACK_DEPTH="${JFR_STACK_DEPTH:-256}"
POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_DB="${POSTGRES_DB:-postgres}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
POSTGRES_MODE="${POSTGRES_MODE:-auto}"
PRICE_HTTP_PROXY_ENABLED="${PRICE_HTTP_PROXY_ENABLED:-false}"
PRICE_HTTP_PROXY_HOST="${PRICE_HTTP_PROXY_HOST:-127.0.0.1}"
PRICE_HTTP_PROXY_PORT="${PRICE_HTTP_PROXY_PORT:-7897}"
PRICE_CONSUMER_CONCURRENCY="${PRICE_CONSUMER_CONCURRENCY:-8}"
PRICE_CONSUMER_REQUIRED_SYMBOLS="${PRICE_CONSUMER_REQUIRED_SYMBOLS:-BTC-USDT}"
PRICE_INDEX_REQUIRED_SYMBOLS="${PRICE_INDEX_REQUIRED_SYMBOLS:-}"
MM_SYMBOL="${MM_SYMBOL:-}"
MM_BASE_QUANTITY_STEPS="${MM_BASE_QUANTITY_STEPS:-20}"
MM_ORDER_LEVELS="${MM_ORDER_LEVELS:-20}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
VALKEY_HOST="${VALKEY_HOST:-127.0.0.1}"
VALKEY_PORT="${VALKEY_PORT:-6379}"
AERON_CLUSTER_HOSTNAMES="${AERON_CLUSTER_HOSTNAMES:-127.0.0.1,127.0.0.1,127.0.0.1}"
AERON_EGRESS_HOSTNAME="${AERON_EGRESS_HOSTNAME:-127.0.0.1}"
EXPORTER_METRICS_HOST="${EXPORTER_METRICS_HOST:-}"
EXPORTER_METRICS_PORT="${EXPORTER_METRICS_PORT:-}"
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
case "$JVM_GC" in ZGC|G1) ;; *) fail 'JVM_GC must be ZGC or G1' ;; esac
case "$JFR_ENABLED" in true|false) ;; *) fail 'JFR_ENABLED must be true or false' ;; esac
case "$JFR_SETTINGS" in profile|default) ;; *) fail 'JFR_SETTINGS must be profile or default' ;; esac
case "$POSTGRES_MODE" in auto|docker|native) ;; *) fail 'POSTGRES_MODE must be auto, docker or native' ;; esac
[[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || fail "invalid RUN_ID=$RUN_ID"

validate_exporter_metrics() {
  [[ "$EXPORTER_METRICS_HOST" =~ [^[:space:]] ]] || fail 'EXPORTER_METRICS_HOST must be non-empty'
  [[ "$EXPORTER_METRICS_PORT" =~ ^[0-9]{1,5}$ ]] || fail 'EXPORTER_METRICS_PORT must be numeric'
  (( 10#$EXPORTER_METRICS_PORT >= 1 && 10#$EXPORTER_METRICS_PORT <= 65535 )) || \
    fail 'EXPORTER_METRICS_PORT must be between 1 and 65535'
}

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
  command -v curl >/dev/null || fail 'curl unavailable'
  command -v nc >/dev/null || fail 'nc unavailable'
  if [[ "$POSTGRES_MODE" == docker ]] || {
    [[ "$POSTGRES_MODE" == auto ]] && [[ -z "$(postgres_container)" ]] && ! command -v psql >/dev/null
  }; then
    command -v docker >/dev/null || fail 'docker unavailable'
    docker info >/dev/null 2>&1 || fail 'docker daemon unavailable'
  fi
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
  command -v docker >/dev/null 2>&1 || return 0
  docker ps --filter publish="$POSTGRES_PORT" --format '{{.ID}}' | head -1
}

postgres_transport() {
  case "$POSTGRES_MODE" in
    docker) printf 'docker\n' ;;
    native) printf 'native\n' ;;
    auto)
      if [[ -n "$(postgres_container)" ]]; then
        printf 'docker\n'
      else
        printf 'native\n'
      fi
      ;;
  esac
}

postgres_exec() {
  if [[ "$(postgres_transport)" == docker ]]; then
    local container
    container="$(postgres_container)"
    [[ -n "$container" ]] || fail "PostgreSQL container exposing port $POSTGRES_PORT not found"
    docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" -i "$container" \
      psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
    return
  fi
  command -v psql >/dev/null || fail 'native PostgreSQL selected but psql unavailable'
  PGPASSWORD="$POSTGRES_PASSWORD" psql -v ON_ERROR_STOP=1 \
    -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"
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
  mkdir -p "$PID_DIR" "$LOG_DIR" "$JFR_DIR" "$LOCK_DIR"
  printf '%s\n' "$RUN_ID" > "$LOCK_OWNER"
  printf '%s\n' "$RUN_ID" > "$OWNER_FILE"
  : > "$READY_FILE"
}

java_args_for() {
  local service="$1"
  JVM_ARGS=(
    "-Xms$JVM_XMS"
    "-Xmx$JVM_XMX"
    "-Dsurprising.launcher.identity=$RUN_ID/$service"
    "-XX:+AlwaysPreTouch"
    "--enable-native-access=ALL-UNNAMED"
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
    "-Xlog:gc*,safepoint:file=$LOG_DIR/$service-gc.log:time,uptime,level,tags:filecount=5,filesize=100M"
  )
  if [[ "$JVM_GC" == ZGC ]]; then
    JVM_ARGS+=("-XX:+UseZGC")
  else
    JVM_ARGS+=("-XX:+UseG1GC")
  fi
  if [[ "$JFR_ENABLED" == true ]]; then
    JVM_ARGS+=(
      "-XX:FlightRecorderOptions=stackdepth=$JFR_STACK_DEPTH"
      "-XX:StartFlightRecording=filename=$JFR_DIR/$service.jfr,settings=$JFR_SETTINGS,dumponexit=true"
    )
  fi
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

launchctl_path() {
  local resolved
  if [[ -x /bin/launchctl ]]; then
    printf '/bin/launchctl\n'
  elif resolved="$(command -v launchctl 2>/dev/null)" && [[ -n "$resolved" ]]; then
    printf '%s\n' "$resolved"
  else
    return 1
  fi
}

start_owned_process() {
  local name="$1" port="$2" pid label launchctl_bin setsid_bin
  shift 2
  if launchctl_bin="$(launchctl_path)"; then
    label="com.surprising.product-line.${RUN_ID//[^a-zA-Z0-9.-]/-}.$name"
    "$launchctl_bin" remove "$label" >/dev/null 2>&1 || true
    "$launchctl_bin" submit -l "$label" -o "$LOG_DIR/$name.log" -e "$LOG_DIR/$name.log" -- \
      /bin/bash -c 'cd "$0"; exec "$@"' "$RUN_DIR" "$@"
    printf '%s\n' "$label" > "$PID_DIR/$name.label"
    local pid_deadline=$((SECONDS + 10))
    until pid="$("$launchctl_bin" print "gui/$(id -u)/$label" 2>/dev/null | awk '/pid =/{print $3; exit}')" && [[ "$pid" =~ ^[0-9]+$ ]]; do
      (( SECONDS < pid_deadline )) || fail "launch timeout name=$name log=$LOG_DIR/$name.log"
      sleep 1
    done
  else
    setsid_bin="$(command -v setsid 2>/dev/null)" || \
      fail "durable process supervisor unavailable name=$name; require launchctl or setsid"
    nohup "$setsid_bin" bash -c 'cd "$0"; exec "$@"' "$RUN_DIR" "$@" \
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
    EXPORTER_METRICS_HOST="$EXPORTER_METRICS_HOST" EXPORTER_METRICS_PORT="$EXPORTER_METRICS_PORT" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" SPRING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_INSTRUMENT_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_ACCOUNT_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SURPRISING_PRICE_CONSUMER_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    PRICE_INDEX_REQUIRED_SYMBOLS="$PRICE_INDEX_REQUIRED_SYMBOLS" \
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
    SURPRISING_PRICE_INDEX_HTTP_PROXY_ENABLED="$PRICE_HTTP_PROXY_ENABLED" \
    SURPRISING_PRICE_INDEX_HTTP_PROXY_HOST="$PRICE_HTTP_PROXY_HOST" \
    SURPRISING_PRICE_INDEX_HTTP_PROXY_PORT="$PRICE_HTTP_PROXY_PORT" \
    PRICE_CONSUMER_CONCURRENCY="$PRICE_CONSUMER_CONCURRENCY" \
    PRICE_CONSUMER_REQUIRED_SYMBOLS="$PRICE_CONSUMER_REQUIRED_SYMBOLS" \
    MM_SYMBOL="$MM_SYMBOL" \
    MM_BASE_QUANTITY_STEPS="$MM_BASE_QUANTITY_STEPS" \
    MM_ORDER_LEVELS="$MM_ORDER_LEVELS"
)

start_http_service() {
  local service="$1" port
  port="$(service_port "$service")"
  java_args_for "$service"
  start_owned_process "$service" "$port" "${COMMON_ENV[@]}" SERVER_PORT="$port" \
    "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" -jar "$(jar_path "$service")"
}

start_background_service() {
  local service="$1" main_class="$2"
  java_args_for "$service"
  start_owned_process "$service" '' "${COMMON_ENV[@]}" \
    "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" \
    -cp "$(jar_path "$service")" "$main_class"
}

start_core() {
  local mode="${1:-up}" node
  if [[ "$mode" == fresh && -d "$RUN_DIR/aeron" ]]; then
    mv "$RUN_DIR/aeron" "$RUN_DIR/aeron.previous.$(date +%s)"
  fi
  mkdir -p "$RUN_DIR/aeron"
  for node in 0 1 2; do
    java_args_for "core-node$node"
    start_owned_process "core-node$node" '' "${COMMON_ENV[@]}" AERON_CORE_THREADING_MODE=DEDICATED \
      "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" \
      -Dsurprising.aeron.product-line="$PRODUCT_LINE" \
      -Dsurprising.aeron.node-id="$node" \
      -Dsurprising.aeron.hostnames="$AERON_CLUSTER_HOSTNAMES" \
      -Dsurprising.aeron.data-dir="$RUN_DIR/aeron" \
      -Dsurprising.aeron.core.threading-mode=DEDICATED \
      -jar "$(jar_path core)"
  done
  local deadline=$((SECONDS + 90))
  java_args_for core-probe
  until "${COMMON_ENV[@]}" "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" \
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
  local pid_file pid deadline launchctl_bin
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    local label_file="${pid_file%.pid}.label"
    if [[ -f "$label_file" ]]; then
      if launchctl_bin="$(launchctl_path)"; then
        "$launchctl_bin" remove "$(<"$label_file")" >/dev/null 2>&1 || true
      else
        kill -0 "$pid" 2>/dev/null && kill -TERM "$pid" 2>/dev/null || true
      fi
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
  java_args_for lifecycle-qa
  "${COMMON_ENV[@]}" W4_LIFECYCLE_AUTHORITY=CORE W4_DERIVATIVES_LIFECYCLE_URL=http://127.0.0.1:9087 \
    "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" \
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
  local service pid index
  local required_services=(core-node0 core-node1 core-node2) verified_pids=()
  for service in "${SERVICES[@]}"; do
    service_enabled "$service" && required_services+=("$service")
  done
  local pid_files=("$PID_DIR"/*.pid)
  [[ -e "${pid_files[0]}" && "${#pid_files[@]}" -eq "${#required_services[@]}" ]] || \
    fail "process ownership set mismatch expected=${#required_services[@]}"
  for service in "${required_services[@]}"; do
    verify_owned_process "$service" >/dev/null
  done
  sleep 1
  for service in "${required_services[@]}"; do
    pid="$(verify_owned_process "$service")" || return $?
    verified_pids+=("$pid")
  done
  for index in "${!required_services[@]}"; do
    printf 'PROCESS=RUNNING service=%s pid=%s\n' \
      "${required_services[$index]}" "${verified_pids[$index]}"
  done
}

verify_owned_process() {
  local service="$1" pid_file="$PID_DIR/$1.pid" label_file="$PID_DIR/$1.label"
  local pid label expected_label launchctl_bin supervised_pid command_line
  [[ -f "$pid_file" ]] || fail "process ownership missing service=$service"
  pid="$(<"$pid_file")"
  [[ "$pid" =~ ^[0-9]+$ ]] || fail "invalid process ownership service=$service"
  kill -0 "$pid" 2>/dev/null || fail "process not running service=$service"
  if [[ -f "$label_file" ]]; then
    launchctl_bin="$(launchctl_path)" || fail "launchd ownership unavailable service=$service"
    label="$(<"$label_file")"
    expected_label="com.surprising.product-line.${RUN_ID//[^a-zA-Z0-9.-]/-}.$service"
    [[ "$label" == "$expected_label" ]] || fail "launchd label mismatch service=$service"
    supervised_pid="$("$launchctl_bin" print "gui/$(id -u)/$expected_label" 2>/dev/null | \
      awk '/pid =/{print $3; exit}')"
    [[ "$supervised_pid" == "$pid" ]] || fail "launchd pid mismatch service=$service"
  elif launchctl_path >/dev/null; then
    fail "launchd label missing service=$service"
  else
    command_line="$(ps -p "$pid" -o command= 2>/dev/null)" || \
      fail "process identity unavailable service=$service"
    [[ " $command_line " == *" -Dsurprising.launcher.identity=$RUN_ID/$service "* ]] || \
      fail "process identity mismatch service=$service"
  fi
  printf '%s\n' "$pid"
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
  up|fresh|dry-run) validate_exporter_metrics ;;
esac
case "$ACTION" in
  up) start_stack up ;;
  fresh) stop_stack >/dev/null 2>&1 || true; start_stack fresh ;;
  down) stop_stack ;;
  status) print_status ;;
  test) run_test ;;
  dry-run) print_dry_run ;;
esac
