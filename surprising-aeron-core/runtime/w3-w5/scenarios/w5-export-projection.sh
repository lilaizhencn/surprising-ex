#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$RUNTIME_DIR/../../.." && pwd)"
RUNNER="$RUNTIME_DIR/run.sh"
RUN_ID="${RUN_ID:-}"
PRODUCT_LINE="${PRODUCT_LINE:-}"
WALLET_ENABLED="${WALLET_ENABLED:-false}"
TASK_RUN_FRESH="${TASK_RUN_FRESH:-true}"
POSTGRES_PORT="${POSTGRES_PORT:-25432}"
KAFKA_PORT="${KAFKA_PORT:-29092}"
POSTGRES_USER="${POSTGRES_USER:-surprising}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-surprising-local-only}"
RUNTIME_ROOT="${RUNTIME_ROOT:-${TMPDIR:-/tmp}/surprising-w3-w5-runtime}"
SCENARIO="${W5_SCENARIO:-export-projection}"
CORE_SMOKE_ONLY="${W5_CORE_SMOKE_ONLY:-false}"

fail() {
  printf 'ERROR=%s\n' "$*" >&2
  exit 2
}

[[ -n "$RUN_ID" ]] || fail RUN_ID_REQUIRED
[[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || fail "INVALID_RUN_ID runId=$RUN_ID"
[[ "$PRODUCT_LINE" == LINEAR_PERPETUAL ]] || fail "PRODUCT_LINE_REFUSED actual=${PRODUCT_LINE:-unset}"
[[ "$WALLET_ENABLED" == false ]] || fail WALLET_REFUSED
[[ "$TASK_RUN_FRESH" == true ]] || fail TASK_RUN_FRESH_REQUIRED
[[ "$SCENARIO" == export-projection || "$SCENARIO" == isolation ]] || fail "INVALID_SCENARIO scenario=$SCENARIO"
[[ "$CORE_SMOKE_ONLY" == true || "$CORE_SMOKE_ONLY" == false ]] || fail "INVALID_CORE_SMOKE_ONLY value=$CORE_SMOKE_ONLY"
[[ -x "$RUNNER" ]] || fail "RUNTIME_RUNNER_MISSING path=$RUNNER"
[[ -f "$RUNTIME_DIR/compose.yaml" ]] || fail "COMPOSE_MISSING path=$RUNTIME_DIR/compose.yaml"
[[ -e "$RUNTIME_ROOT/runs/$RUN_ID" ]] && fail "RUN_ID_REUSE runId=$RUN_ID"

RUN_DIR="$RUNTIME_ROOT/runs/$RUN_ID"
ATTEMPT_DIR="$RUNTIME_ROOT/attempts/$RUN_ID"
BUILD_DIR="$ATTEMPT_DIR/build"
JARS_DIR="$BUILD_DIR/jars"
mkdir -p "$ATTEMPT_DIR" "$JARS_DIR"
ORIGINAL_BOOT_JARS_DIR="$BUILD_DIR/original-boot-jars"
exec > >(tee "$ATTEMPT_DIR/console.raw.log") 2>&1

COMPOSE_PROJECT_NAME="surprising-w3w5-$(printf '%s' "$RUN_ID" | tr '[:upper:]' '[:lower:]')"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME//_/-}"
POSTGRES_DB="surprising_${RUN_ID//[^a-zA-Z0-9]/_}"
POSTGRES_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$POSTGRES_DB"
KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT"
LOCK_DIR="$RUNTIME_ROOT/linear-perpetual.lock"
LOCK_OWNER="$LOCK_DIR/owner"
MAIN_WORKTREE=""
CORE_DATA_ROOT="$RUN_DIR/core-data"
CORE_HOSTNAMES="127.0.0.1,127.0.0.1,127.0.0.1"
CORE_CLIENT_PORTS=(21002 21102 21202)

export RUN_ID PRODUCT_LINE WALLET_ENABLED TASK_RUN_FRESH RUNTIME_ROOT POSTGRES_PORT KAFKA_PORT
export POSTGRES_USER POSTGRES_PASSWORD POSTGRES_DB COMPOSE_PROJECT_NAME
export AERON_HOSTNAMES="$CORE_HOSTNAMES"
export AERON_EGRESS_HOSTNAME=127.0.0.1
export EXPORT_BATCH_SIZE=256
export EXPORT_IDLE_MS=10
export SURPRISING_WEBSOCKET_GROUP_ID="$RUN_ID"
export SURPRISING_WEBSOCKET_SESSION_OUTBOUND_QUEUE_CAPACITY=2
export SURPRISING_WEBSOCKET_SESSION_SEND_TIMEOUT=200ms

JAVA_CANDIDATES=()
[[ -n "${JAVA_HOME:-}" ]] && JAVA_CANDIDATES+=("$JAVA_HOME")
JAVA_CANDIDATES+=(
  /Library/Java/JavaVirtualMachines/ibm-semeru-open-25.jdk/Contents/Home
  /Users/atomex/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
)
JAVA_HOME=""
for candidate in "${JAVA_CANDIDATES[@]}"; do
  if [[ -x "$candidate/bin/java" ]] && [[ "$($candidate/bin/java -version 2>&1 | head -1)" == *25.* ]]; then
    JAVA_HOME="$candidate"
    break
  fi
done
[[ -n "$JAVA_HOME" ]] || fail JDK25_REQUIRED
JAVA_BIN="$JAVA_HOME/bin/java"
JAVA_VERSION="$($JAVA_BIN -version 2>&1 | head -1)"
[[ "$JAVA_VERSION" == *25.* ]] || fail "JDK25_REQUIRED actual=$JAVA_VERSION"
JAVA_FLAGS=(
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
)
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
MAVEN_BIN="${MAVEN_BIN:-$(command -v mvn || true)}"
[[ -x "$MAVEN_BIN" ]] || fail MAVEN_REQUIRED

compose() {
  docker compose --project-name "$COMPOSE_PROJECT_NAME" --file "$RUNTIME_DIR/compose.yaml" "$@"
}

initialize_runtime_state() {
  [[ ! -e "$LOCK_DIR" ]] || fail "CONCURRENT_RUNTIME_REFUSED lock=$LOCK_DIR"
  mkdir -p "$RUN_DIR/pids" "$RUN_DIR/logs"
  printf '%s\n' "$RUN_ID" > "$RUN_DIR/owner"
  mkdir "$LOCK_DIR"
  printf '%s\n' "$RUN_ID" > "$LOCK_OWNER"
  : > "$RUN_DIR/ready.tsv"
  MAIN_WORKTREE="$(git -C "$REPO_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')"
  local index_path
  index_path="$(git -C "$MAIN_WORKTREE" rev-parse --absolute-git-dir)/index"
  {
    printf 'HEAD=%s\n' "$(git -C "$MAIN_WORKTREE" rev-parse HEAD)"
    printf 'INDEX_SHA256='
    shasum -a 256 "$index_path" | awk '{ print $1 }'
    printf '[status]\n'
    GIT_OPTIONAL_LOCKS=0 git -C "$MAIN_WORKTREE" status --porcelain=v2 --untracked-files=no
  } > "$RUN_DIR/main-worktree-before.txt"
}

mark_ready() {
  local service="$1"
  printf '%s\t%s\n' "$(( $(wc -l < "$RUN_DIR/ready.tsv") + 1 ))" "$service" >> "$RUN_DIR/ready.tsv"
  printf 'READY=%s\n' "$service"
}

service_id() {
  docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
    --filter "label=com.docker.compose.service=$1" --filter "label=com.surprising.runtime.run-id=$RUN_ID" | head -1
}

service_state() {
  docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$(service_id "$1")"
}

wait_service() {
  local service="$1" deadline=$((SECONDS + 90)) state health
  while (( SECONDS < deadline )); do
    if [[ -n "$(service_id "$service")" ]]; then
      state="$(service_state "$service" | awk '{print $1}')"
      health="$(service_state "$service" | awk '{print $2}')"
      if [[ "$state" == running && "$health" == healthy ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  fail "CONTAINER_READINESS_TIMEOUT service=$service state=${state:-missing}"
}

topic_list() {
  awk -v prefix='surprising.linear-perp' '
    /return topic\("/ {
      value=$0; sub(/^.*return topic\("/, "", value); sub(/"\).*$/, "", value);
      print prefix "." value ".v1"
    }
    /return INSTRUMENT_EVENTS_TOPIC/ { print "surprising.instrument.events.v1" }
  ' "$REPO_ROOT/surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java"
}

run_migrations() {
  local migration
  for migration in "$REPO_ROOT"/surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/*.sql; do
    compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" < "$migration" >/dev/null
  done
  mark_ready migrations
}

create_topics() {
  local topic
  while IFS= read -r topic; do
    compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --create --if-not-exists --partitions 1 --replication-factor 1 --topic "$topic" </dev/null >/dev/null
  done < <(topic_list)
  verify_topics
  mark_ready topics
}

verify_topics() {
  local expected metadata actual expected_csv actual_csv metadata_status=0
  expected="$(topic_list | awk '{ print $0 "\t1" }' | LC_ALL=C sort)"
  metadata="$(compose exec -T kafka timeout 30s /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --describe </dev/null)" || metadata_status=$?
  (( metadata_status == 0 )) || fail "KAFKA_TOPIC_METADATA_COMMAND_FAILED status=$metadata_status"
  metadata_status=0
  actual="$(printf '%s\n' "$metadata" | awk '
    /PartitionCount:/ {
      topic=""; partitions=""
      for (field = 1; field <= NF; field++) {
        if ($field == "Topic:") topic=$(field + 1)
        else if ($field ~ /^Topic:/) { topic=$field; sub(/^Topic:/, "", topic) }
        if ($field == "PartitionCount:") partitions=$(field + 1)
        else if ($field ~ /^PartitionCount:/) { partitions=$field; sub(/^PartitionCount:/, "", partitions) }
      }
      if (topic == "" || partitions !~ /^[0-9]+$/) exit 2
      print topic "\t" partitions
      summaries++
    }
    END { if (summaries == 0) exit 2 }
  ' | LC_ALL=C sort)" || metadata_status=$?
  (( metadata_status == 0 )) || fail "KAFKA_TOPIC_METADATA_INVALID status=$metadata_status"
  if [[ "$actual" != "$expected" ]]; then
    expected_csv="$(printf '%s\n' "$expected" | tr '\t\n' '=,')"
    actual_csv="$(printf '%s\n' "$actual" | tr '\t\n' '=,')"
    fail "KAFKA_TOPIC_METADATA_MISMATCH expected=$expected_csv actual=$actual_csv"
  fi
}

start_owned_process() {
  local service="$1" port="$2" defer_ready=false readiness_timeout=45
  shift 2
  [[ "$service" != gateway ]] || readiness_timeout=90
  if [[ "${1:-}" == --defer-ready ]]; then
    defer_ready=true
    shift
  fi
  local marker="surprising-w3w5:$RUN_ID:$service"
  bash -c "$WRAPPER_SCRIPT" "$marker" "$@" > "$RUN_DIR/logs/$service.log" 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" > "$RUN_DIR/pids/$service.pid"
  local deadline=$((SECONDS + readiness_timeout))
  while (( SECONDS < deadline )); do
    kill -0 "$pid" 2>/dev/null || fail "PROCESS_EXITED service=$service log=$RUN_DIR/logs/$service.log"
    if [[ -n "$port" ]] && port_ready "$port"; then
      [[ "$defer_ready" == true ]] || mark_ready "$service"
      return 0
    fi
    if [[ -z "$port" ]]; then
      sleep 1
      [[ "$defer_ready" == true ]] || mark_ready "$service"
      return 0
    fi
    sleep 1
  done
  fail "READINESS_TIMEOUT service=$service port=$port timeout=${readiness_timeout}s log=$RUN_DIR/logs/$service.log"
}

port_ready() {
  local port="$1"
  case "$port" in
    udp:*) lsof -nP -iUDP:"${port#udp:}" >/dev/null 2>&1 ;;
    tcp:*) lsof -nP -iTCP:"${port#tcp:}" -sTCP:LISTEN >/dev/null 2>&1 ;;
    *) lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 ;;
  esac
}

start_host_core() {
  local node service data_dir
  mkdir -p "$CORE_DATA_ROOT"
  for node in 0 1 2; do
    service="core-node$node"
    data_dir="$CORE_DATA_ROOT/node$node"
    mkdir -p "$data_dir"
    start_owned_process "$service" '' --defer-ready env \
      PRODUCT_LINE="$PRODUCT_LINE" AERON_HOSTNAMES="$AERON_HOSTNAMES" \
      AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
      "$JAVA_BIN" "${JAVA_FLAGS[@]}" \
      "-Dsurprising.aeron.product-line=$PRODUCT_LINE" \
      "-Dsurprising.aeron.node-id=$node" \
      "-Dsurprising.aeron.hostnames=$AERON_HOSTNAMES" \
      "-Dsurprising.aeron.data-dir=$data_dir" \
      -jar "$JARS_DIR/surprising-aeron-service.jar"
  done
  printf 'CORE_HOST_PROCESSES=PASS productLine=%s nodes=0,1,2 hostnames=%s clientPorts=%s dataRoot=%s\n' \
    "$PRODUCT_LINE" "$AERON_HOSTNAMES" "${CORE_CLIENT_PORTS[*]// /,}" "$CORE_DATA_ROOT"
}

verify_host_core() {
  local smoke_log="$ATTEMPT_DIR/core-connectivity-smoke.txt" attempt_log status=1
  local attempt=0 deadline=$((SECONDS + 90))
  : > "$smoke_log"
  while (( SECONDS < deadline )); do
    attempt=$((attempt + 1))
    attempt_log="$ATTEMPT_DIR/core-connectivity-smoke-attempt-$attempt.txt"
    set +e
    "$JAVA_BIN" "${JAVA_FLAGS[@]}" \
      "-Dsurprising.aeron.product-line=$PRODUCT_LINE" \
      "-Dsurprising.aeron.hostnames=$AERON_HOSTNAMES" \
      "-Dsurprising.aeron.egress-hostname=$AERON_EGRESS_HOSTNAME" \
      -Dsurprising.aeron.probe-mode=query \
      -Dsurprising.aeron.source-id=17017 \
      -cp "$JARS_DIR/surprising-aeron-tools.jar" \
      com.surprising.aeron.tools.ClusterProbeMain > "$attempt_log" 2>&1
    status=$?
    set -e
    {
      printf 'CORE_CONNECTIVITY_ATTEMPT=%s status=%s\n' "$attempt" "$status"
      cat "$attempt_log"
    } >> "$smoke_log"
    if (( status == 0 )) && rg -q '^status=OK ' "$attempt_log"; then
      break
    fi
    sleep 1
  done
  cat "$smoke_log"
  (( status == 0 )) || fail "CORE_CONNECTIVITY_FAILED status=$status artifact=$smoke_log"
  rg -q '^status=OK ' "$smoke_log" || fail "CORE_ELECTION_RESPONSE_MISSING artifact=$smoke_log"
  mark_ready core-node0
  mark_ready core-node1
  mark_ready core-node2
  mark_ready core-cluster
  printf 'CORE_CLUSTER=PASS productLine=%s hosts=%s egress=%s response=STATE_HASH_QUERY artifact=%s\n' \
    "$PRODUCT_LINE" "$AERON_HOSTNAMES" "$AERON_EGRESS_HOSTNAME" "$smoke_log"
}

start_required_stack() {
  start_owned_process exporter '' env PRODUCT_LINE="$PRODUCT_LINE" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    DATABASE_URL="$POSTGRES_URL" DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
    AERON_HOSTNAMES="$AERON_HOSTNAMES" AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
    EXPORT_BATCH_SIZE="$EXPORT_BATCH_SIZE" EXPORT_IDLE_MS="$EXPORT_IDLE_MS" \
    "$JAVA_BIN" "${JAVA_FLAGS[@]}" -jar "$JARS_DIR/surprising-aeron-exporter.jar"
  start_owned_process projector '' env PRODUCT_LINE="$PRODUCT_LINE" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    DATABASE_URL="$POSTGRES_URL" DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
    AERON_HOSTNAMES="$AERON_HOSTNAMES" AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
    "$JAVA_BIN" "${JAVA_FLAGS[@]}" -cp "$JARS_DIR/surprising-aeron-exporter.jar" com.surprising.aeron.exporter.ProjectionMain
  start_owned_process gateway 9094 env PRODUCT_LINE="$PRODUCT_LINE" SERVER_PORT=9094 \
    SPRING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" SURPRISING_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    SPRING_DATASOURCE_URL="$POSTGRES_URL" SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" \
    SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
    SURPRISING_WEBSOCKET_GROUP_ID="$SURPRISING_WEBSOCKET_GROUP_ID" \
    SURPRISING_WEBSOCKET_SESSION_OUTBOUND_QUEUE_CAPACITY="$SURPRISING_WEBSOCKET_SESSION_OUTBOUND_QUEUE_CAPACITY" \
    SURPRISING_WEBSOCKET_SESSION_SEND_TIMEOUT="$SURPRISING_WEBSOCKET_SESSION_SEND_TIMEOUT" \
    GATEWAY_CUSTODY_WALLET_ENABLED=false GATEWAY_KYC_DOCUMENTS_ENABLED=false \
    "$JAVA_BIN" "${JAVA_FLAGS[@]}" -jar "$JARS_DIR/surprising-gateway.jar"
  record_inventory "$RUN_DIR/ownership-live.txt"
  printf 'RUNTIME_REQUIRED_STACK=PASS exporter=REAL projector=REAL gateway=REAL websocket=REAL maker=NOT_STARTED scope=projection-fault-gate\n'
}

unrelated_snapshot() {
  local name
  for name in rainbo-postgres rainbo-valkey rainbo-kafka; do
    if docker inspect "$name" >/dev/null 2>&1; then
      docker inspect --format "$name {{.Id}} {{.State.Status}}" "$name"
    else
      printf '%s missing\n' "$name"
    fi
  done
}

record_inventory() {
  local target="$1"
  {
    printf 'RUN_ID=%s\nPRODUCT_LINE=%s\nWALLET_ENABLED=%s\nCOMPOSE_PROJECT=%s\nCOMPOSE_SERVICES=postgres,kafka\n' \
      "$RUN_ID" "$PRODUCT_LINE" "$WALLET_ENABLED" "$COMPOSE_PROJECT_NAME"
    printf 'CORE_RUNTIME=HOST_JAVA\nCORE_PRODUCT_LINE=%s\nCORE_HOSTNAMES=%s\nCORE_CLIENT_PORTS=21002,21102,21202\nCORE_DATA_ROOT=%s\n' \
      "$PRODUCT_LINE" "$AERON_HOSTNAMES" "$CORE_DATA_ROOT"
    printf '[containers]\n'
    docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
      --format '{{.ID}} {{.Names}} {{.Status}}'
    printf '[volumes]\n'
    docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"
    printf '[processes]\n'
    ps -axo pid,ppid,command | rg "surprising-w3w5:$RUN_ID|W5FaultQaMain|mvn" || true
  } > "$target"
}

MAVEN_MODULES=(
  surprising-aeron-core/surprising-aeron-service
  surprising-aeron-core/surprising-aeron-exporter
  surprising-aeron-core/surprising-aeron-tools
  surprising-instrument/surprising-instrument-provider
  surprising-price/surprising-price-provider
  surprising-trading/surprising-order-provider
  surprising-trading/surprising-matching-provider
  surprising-trading/surprising-trigger-provider
  surprising-risk/surprising-risk-provider
  surprising-funding/surprising-funding-provider
  surprising-liquidation
  surprising-insurance
  surprising-adl
  surprising-gateway
  surprising-maker
)

build_artifacts() {
  local modules
  modules="$(IFS=,; printf '%s' "${MAVEN_MODULES[*]}")"
  {
    printf 'JAVA_HOME=%s\nJAVA_VERSION=%s\n' "$JAVA_HOME" "$JAVA_VERSION"
    "$MAVEN_BIN" --version
  } > "$ATTEMPT_DIR/toolchain.txt" 2>&1
  set +e
  "$MAVEN_BIN" -pl "$modules" -am -DskipTests package \
    > "$ATTEMPT_DIR/maven-package.log" 2>&1
  local status=$?
  set -e
  tail -120 "$ATTEMPT_DIR/maven-package.log"
  (( status == 0 )) || fail "MAVEN_PACKAGE_FAILED status=$status artifact=$ATTEMPT_DIR/maven-package.log"

  local source target name
  while IFS='|' read -r source name; do
    target="$JARS_DIR/$name"
    [[ -f "$REPO_ROOT/$source" ]] || fail "JAR_MISSING source=$source"
    cp "$REPO_ROOT/$source" "$target"
    shasum -a 256 "$target"
  done <<'EOF'
surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar|surprising-aeron-exporter.jar
surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar|surprising-aeron-tools.jar
surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar|surprising-aeron-service.jar
surprising-instrument/surprising-instrument-provider/target/surprising-instrument-provider-1.0.0-SNAPSHOT-exec.jar|surprising-instrument-provider.jar
surprising-price/surprising-price-provider/target/surprising-price-provider-1.0.0-SNAPSHOT-exec.jar|surprising-price-provider.jar
surprising-trading/surprising-order-provider/target/surprising-order-provider-1.0.0-SNAPSHOT-exec.jar|surprising-order-provider.jar
surprising-trading/surprising-matching-provider/target/surprising-matching-provider-1.0.0-SNAPSHOT-exec.jar|surprising-matching-provider.jar
surprising-trading/surprising-trigger-provider/target/surprising-trigger-provider-1.0.0-SNAPSHOT-exec.jar|surprising-trigger-provider.jar
surprising-risk/surprising-risk-provider/target/surprising-risk-provider-1.0.0-SNAPSHOT-exec.jar|surprising-risk-provider.jar
surprising-funding/surprising-funding-provider/target/surprising-funding-provider-1.0.0-SNAPSHOT-exec.jar|surprising-funding-provider.jar
surprising-liquidation/target/surprising-liquidation-1.0.0-SNAPSHOT-exec.jar|surprising-liquidation.jar
surprising-insurance/target/surprising-insurance-1.0.0-SNAPSHOT-exec.jar|surprising-insurance.jar
surprising-adl/target/surprising-adl-1.0.0-SNAPSHOT-exec.jar|surprising-adl.jar
surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar|surprising-gateway.jar
surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT-exec.jar|surprising-maker.jar
EOF
}

BOOT_JAR_SPECS=(
  "surprising-instrument/surprising-instrument-provider/target/surprising-instrument-provider-1.0.0-SNAPSHOT.jar|surprising-instrument/surprising-instrument-provider/target/surprising-instrument-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-price/surprising-price-provider/target/surprising-price-provider-1.0.0-SNAPSHOT.jar|surprising-price/surprising-price-provider/target/surprising-price-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-trading/surprising-order-provider/target/surprising-order-provider-1.0.0-SNAPSHOT.jar|surprising-trading/surprising-order-provider/target/surprising-order-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-trading/surprising-matching-provider/target/surprising-matching-provider-1.0.0-SNAPSHOT.jar|surprising-trading/surprising-matching-provider/target/surprising-matching-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-trading/surprising-trigger-provider/target/surprising-trigger-provider-1.0.0-SNAPSHOT.jar|surprising-trading/surprising-trigger-provider/target/surprising-trigger-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-risk/surprising-risk-provider/target/surprising-risk-provider-1.0.0-SNAPSHOT.jar|surprising-risk/surprising-risk-provider/target/surprising-risk-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-funding/surprising-funding-provider/target/surprising-funding-provider-1.0.0-SNAPSHOT.jar|surprising-funding/surprising-funding-provider/target/surprising-funding-provider-1.0.0-SNAPSHOT-exec.jar"
  "surprising-liquidation/target/surprising-liquidation-1.0.0-SNAPSHOT.jar|surprising-liquidation/target/surprising-liquidation-1.0.0-SNAPSHOT-exec.jar"
  "surprising-insurance/target/surprising-insurance-1.0.0-SNAPSHOT.jar|surprising-insurance/target/surprising-insurance-1.0.0-SNAPSHOT-exec.jar"
  "surprising-adl/target/surprising-adl-1.0.0-SNAPSHOT.jar|surprising-adl/target/surprising-adl-1.0.0-SNAPSHOT-exec.jar"
  "surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT.jar|surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar"
  "surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT.jar|surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT-exec.jar"
)
BOOT_JARS_PREPARED=0

prepare_boot_jars() {
  local spec source executable backup
  mkdir -p "$ORIGINAL_BOOT_JARS_DIR"
  for spec in "${BOOT_JAR_SPECS[@]}"; do
    source="${spec%%|*}"
    executable="${spec#*|}"
    [[ -f "$REPO_ROOT/$source" ]] || fail "BOOT_JAR_SOURCE_MISSING path=$REPO_ROOT/$source"
    [[ -f "$REPO_ROOT/$executable" ]] || fail "BOOT_JAR_EXECUTABLE_MISSING path=$REPO_ROOT/$executable"
    backup="$ORIGINAL_BOOT_JARS_DIR/$source"
    mkdir -p "$(dirname "$backup")"
    cp "$REPO_ROOT/$source" "$backup"
    cp "$REPO_ROOT/$executable" "$REPO_ROOT/$source"
    printf 'BOOT_JAR_ALIAS source=%s executable=%s backup=%s\n' "$source" "$executable" "$backup"
  done
  BOOT_JARS_PREPARED=1
}

restore_boot_jars() {
  (( BOOT_JARS_PREPARED == 1 )) || return 0
  local spec source backup
  for spec in "${BOOT_JAR_SPECS[@]}"; do
    source="${spec%%|*}"
    backup="$ORIGINAL_BOOT_JARS_DIR/$source"
    [[ -f "$backup" ]] || continue
    cp "$backup" "$REPO_ROOT/$source"
  done
  printf 'BOOT_JARS_RESTORED=PASS path=%s\n' "$ORIGINAL_BOOT_JARS_DIR"
  BOOT_JARS_PREPARED=0
}

CORE_SERVICES=(core-node0 core-node1 core-node2)
CORE_DRAIN_TIMEOUT_SECONDS=60
CLEANUP_DONE=0
BEFORE_UNRELATED="$ATTEMPT_DIR/unrelated.before.txt"
AFTER_UNRELATED="$ATTEMPT_DIR/unrelated.after.txt"

remove_core_data() {
  local expected="$RUN_DIR/core-data"
  [[ "$CORE_DATA_ROOT" == "$expected" ]] || fail "CORE_DATA_PATH_REFUSED path=$CORE_DATA_ROOT"
  if [[ -e "$CORE_DATA_ROOT" ]]; then
    rm -rf "$CORE_DATA_ROOT"
  fi
  [[ ! -e "$CORE_DATA_ROOT" ]] || fail "CORE_DATA_CLEANUP_FAILED path=$CORE_DATA_ROOT"
  printf 'CORE_DATA_CLEANUP=PASS path=%s\n' "$CORE_DATA_ROOT"
}

drain_owned_core_wrappers() {
  local -a pids=()
  local service pid pid_file marker command process_state prior_pid
  local index deadline live pid_file_count=0 expected_pid_file_count=${#CORE_SERVICES[@]}

  for service in "${CORE_SERVICES[@]}"; do
    [[ ! -f "$RUN_DIR/pids/$service.pid" ]] || pid_file_count=$((pid_file_count + 1))
  done
  if (( pid_file_count == 0 )); then
    printf 'CORE_DRAIN=SKIPPED reason=ALL_CORE_PID_FILES_ABSENT\n'
    return 0
  fi
  if (( pid_file_count != expected_pid_file_count )); then
    printf 'ERROR=CORE_DRAIN_PID_SET_PARTIAL expected=%s actual=%s path=%s\n' \
      "$expected_pid_file_count" "$pid_file_count" "$RUN_DIR/pids" >&2
    return 1
  fi

  for service in "${CORE_SERVICES[@]}"; do
    pid_file="$RUN_DIR/pids/$service.pid"
    if [[ ! -f "$pid_file" ]]; then
      printf 'ERROR=CORE_DRAIN_PID_FILE_MISSING service=%s path=%s\n' "$service" "$pid_file" >&2
      return 1
    fi
    pid="$(<"$pid_file")"
    if [[ ! "$pid" =~ ^[1-9][0-9]*$ ]] || (( pid <= 1 )); then
      printf 'ERROR=CORE_DRAIN_PID_MALFORMED service=%s path=%s value=%q\n' "$service" "$pid_file" "$pid" >&2
      return 1
    fi
    if (( ${#pids[@]} > 0 )); then
      for prior_pid in "${pids[@]}"; do
        if [[ "$prior_pid" == "$pid" ]]; then
          printf 'ERROR=CORE_DRAIN_PID_DUPLICATE service=%s pid=%s\n' "$service" "$pid" >&2
          return 1
        fi
      done
    fi
    marker="surprising-w3w5:$RUN_ID:$service"
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [[ -z "$command" ]]; then
      printf 'ERROR=CORE_DRAIN_PROCESS_MISSING service=%s pid=%s\n' "$service" "$pid" >&2
      return 1
    fi
    if [[ "$command" != *"$marker"* ]]; then
      printf 'ERROR=CORE_DRAIN_FOREIGN_PROCESS service=%s pid=%s marker=%s command=%q\n' \
        "$service" "$pid" "$marker" "$command" >&2
      return 1
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      printf 'ERROR=CORE_DRAIN_PROCESS_NOT_LIVE service=%s pid=%s\n' "$service" "$pid" >&2
      return 1
    fi
    pids+=("$pid")
  done

  printf 'CORE_DRAIN=TERM services=%s timeout=%ss_total_and_per_core\n' \
    "${CORE_SERVICES[*]}" "$CORE_DRAIN_TIMEOUT_SECONDS"
  for index in "${!CORE_SERVICES[@]}"; do
    service="${CORE_SERVICES[$index]}"
    pid="${pids[$index]}"
    if ! kill -TERM "$pid" 2>/dev/null; then
      printf 'ERROR=CORE_DRAIN_TERM_FAILED service=%s pid=%s\n' "$service" "$pid" >&2
      return 1
    fi
  done

  deadline=$((SECONDS + CORE_DRAIN_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    live=0
    for index in "${!CORE_SERVICES[@]}"; do
      service="${CORE_SERVICES[$index]}"
      pid="${pids[$index]}"
      command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
      process_state="$(ps -p "$pid" -o stat= 2>/dev/null | tr -d '[:space:]' || true)"
      [[ -z "$command" || "$process_state" == Z* ]] && continue
      marker="surprising-w3w5:$RUN_ID:$service"
      if [[ "$command" != *"$marker"* ]]; then
        printf 'ERROR=CORE_DRAIN_FOREIGN_REPLACEMENT service=%s pid=%s marker=%s command=%q\n' \
          "$service" "$pid" "$marker" "$command" >&2
        return 1
      fi
      live=1
    done
    if (( live == 0 )); then
      printf 'CORE_DRAIN=PASS services=%s elapsed_or_less_than=%ss\n' \
        "${CORE_SERVICES[*]}" "$CORE_DRAIN_TIMEOUT_SECONDS"
      return 0
    fi
    sleep 1
  done

  for index in "${!CORE_SERVICES[@]}"; do
    service="${CORE_SERVICES[$index]}"
    pid="${pids[$index]}"
    command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    process_state="$(ps -p "$pid" -o stat= 2>/dev/null | tr -d '[:space:]' || true)"
    [[ -z "$command" || "$process_state" == Z* ]] && continue
    marker="surprising-w3w5:$RUN_ID:$service"
    if [[ "$command" != *"$marker"* ]]; then
      printf 'ERROR=CORE_DRAIN_FOREIGN_REPLACEMENT service=%s pid=%s marker=%s command=%q\n' \
        "$service" "$pid" "$marker" "$command" >&2
      return 1
    fi
    printf 'ERROR=CORE_DRAIN_TIMEOUT service=%s pid=%s timeout=%ss\n' \
      "$service" "$pid" "$CORE_DRAIN_TIMEOUT_SECONDS" >&2
  done
  return 1
}

verify_task17_cleanup() {
  local process_snapshot="$ATTEMPT_DIR/core-processes-after-cleanup.txt"
  ps -axo pid=,ppid=,command= > "$process_snapshot"
  if [[ -d "$RUN_DIR/pids" ]] && find "$RUN_DIR/pids" -name '*.pid' -print -quit | grep -q .; then
    printf 'ERROR=TASK17_STALE_PID_FILES path=%s\n' "$RUN_DIR/pids" >&2
    return 1
  fi
  if [[ -e "$LOCK_DIR" ]]; then
    printf 'ERROR=TASK17_STALE_LOCK path=%s\n' "$LOCK_DIR" >&2
    return 1
  fi
  if rg -Fq "surprising-w3w5:$RUN_ID:core-node" "$process_snapshot"; then
    printf 'ERROR=TASK17_STALE_CORE_WRAPPER_PROCESS marker=surprising-w3w5:%s:core-node artifact=%s\n' \
      "$RUN_ID" "$process_snapshot" >&2
    return 1
  fi
  if rg -Fq "$JARS_DIR/surprising-aeron-service.jar" "$process_snapshot"; then
    printf 'ERROR=TASK17_STALE_CORE_JAVA_PROCESS jar=%s artifact=%s\n' \
      "$JARS_DIR/surprising-aeron-service.jar" "$process_snapshot" >&2
    return 1
  fi
  printf 'TASK17_CLEANUP_VERIFY=PASS pidFiles=absent lock=absent coreProcesses=absent artifact=%s\n' \
    "$process_snapshot"
}

cleanup() {
  local status=$? down_status=0 cleanup_status=0 core_drain_status=0
  set +e
  trap - EXIT INT TERM
  record_inventory "$ATTEMPT_DIR/inventory.before-cleanup.txt"
  if docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" | grep -q .; then
    compose logs --no-color > "$ATTEMPT_DIR/compose-logs-before-cleanup.log" 2>&1
  fi
  if [[ -f "$RUN_DIR/owner" ]]; then
    drain_owned_core_wrappers > "$ATTEMPT_DIR/core-drain.log" 2>&1
    core_drain_status=$?
    cat "$ATTEMPT_DIR/core-drain.log"
    if (( core_drain_status == 0 )); then
      "$RUNNER" down > "$ATTEMPT_DIR/runtime-down.log" 2>&1
      down_status=$?
      cat "$ATTEMPT_DIR/runtime-down.log"
      if (( down_status == 0 )) && rg -q '^CLEANUP=PASS ' "$ATTEMPT_DIR/runtime-down.log"; then
        CLEANUP_DONE=1
      else
        cleanup_status=1
      fi
    else
      cleanup_status=1
      printf 'CLEANUP_FAIL_CLOSED=PASS reason=CORE_DRAIN_FAILED artifact=%s\n' \
        "$ATTEMPT_DIR/core-drain.log"
    fi
  fi
  if (( core_drain_status == 0 && CLEANUP_DONE == 0 )); then
    compose down --volumes --remove-orphans > "$ATTEMPT_DIR/compose-cleanup.log" 2>&1
    (( $? == 0 )) || cleanup_status=1
  fi
  restore_boot_jars
  if (( core_drain_status == 0 )) && docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" | grep -q .; then
    compose down --volumes --remove-orphans > "$ATTEMPT_DIR/compose-final-cleanup.log" 2>&1
    (( $? == 0 )) || cleanup_status=1
  fi
  if (( CLEANUP_DONE == 1 )); then
    verify_task17_cleanup || cleanup_status=1
    if (( cleanup_status == 0 )); then
      remove_core_data || cleanup_status=1
    else
      printf 'CORE_DATA_CLEANUP=SKIPPED reason=TASK17_CLEANUP_VERIFY_FAILED\n'
    fi
  else
    printf 'CORE_DATA_CLEANUP=SKIPPED reason=RUNNER_DOWN_NOT_PASS\n'
  fi
  unrelated_snapshot > "$AFTER_UNRELATED"
  record_inventory "$ATTEMPT_DIR/inventory.after-cleanup.txt"
  if ! cmp -s "$BEFORE_UNRELATED" "$AFTER_UNRELATED"; then
    printf 'UNRELATED_RESOURCES_PROTECTED=FAIL before=%s after=%s\n' "$BEFORE_UNRELATED" "$AFTER_UNRELATED"
    cleanup_status=1
  else
    printf 'UNRELATED_RESOURCES_PROTECTED=PASS artifact=%s\n' "$AFTER_UNRELATED"
  fi
  if docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" | grep -q .; then
    printf 'CLEANUP_RESOURCES=FAIL project=%s\n' "$COMPOSE_PROJECT_NAME"
    cleanup_status=1
  else
    printf 'CLEANUP_RESOURCES=PASS project=%s volumes=fresh-removed\n' "$COMPOSE_PROJECT_NAME"
  fi
  if (( CLEANUP_DONE == 1 && cleanup_status == 0 )); then
    printf 'CLEANUP=PASS runId=%s\n' "$RUN_ID"
  fi
  if (( status == 0 && cleanup_status != 0 )); then status=1; fi
  printf 'CLEANUP_ARTIFACT=%s\nSCENARIO_EXIT=%d\n' "$ATTEMPT_DIR" "$status"
  exit "$status"
}
trap cleanup EXIT INT TERM

unrelated_snapshot > "$BEFORE_UNRELATED"
record_inventory "$ATTEMPT_DIR/inventory.initial.txt"
build_artifacts
prepare_boot_jars

initialize_runtime_state
printf 'PRESTART=PASS compose=postgres,kafka core=HOST_JAVA productLine=%s hostnames=%s clientPorts=21002,21102,21202 image=NOT_BUILT wallet=ABSENT secondLine=ABSENT\n' \
  "$PRODUCT_LINE" "$AERON_HOSTNAMES" | tee "$ATTEMPT_DIR/prestart.txt"

compose up -d postgres kafka > "$ATTEMPT_DIR/compose-required-up.log" 2>&1
for service in postgres kafka; do wait_service "$service"; done
mark_ready postgres
mark_ready kafka
run_migrations
create_topics
printf 'INFRA=PASS project=%s postgres=%s kafka=%s core=HOST_JAVA\n' \
  "$COMPOSE_PROJECT_NAME" "$(service_id postgres)" "$(service_id kafka)"

WRAPPER_SCRIPT='marker="$0"; child=""; terminate(){ if [[ -n "$child" ]]; then kill -TERM "$child" 2>/dev/null || true; child_deadline=$((SECONDS + 10)); while kill -0 "$child" 2>/dev/null && (( SECONDS < child_deadline )); do sleep 1; done; kill -KILL "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true; fi; exit 0; }; trap terminate TERM INT; "$@" & child=$!; wait "$child"'
start_host_core
verify_host_core
if [[ "$CORE_SMOKE_ONLY" == true ]]; then
  printf 'CORE_CONNECTIVITY_SMOKE=PASS cleanup=run.sh-down artifact=%s\n' "$ATTEMPT_DIR/core-connectivity-smoke.txt"
  exit 0
fi

set +e
start_required_stack > "$ATTEMPT_DIR/runtime-up.log" 2>&1
UP_STATUS=$?
set -e
cat "$ATTEMPT_DIR/runtime-up.log"
(( UP_STATUS == 0 )) || fail "RUNTIME_UP_FAILED status=$UP_STATUS artifact=$ATTEMPT_DIR/runtime-up.log"
record_inventory "$ATTEMPT_DIR/inventory.after-up.txt"
printf 'WALLET=ABSENT productLine=%s runId=%s maker=LAST\n' "$PRODUCT_LINE" "$RUN_ID"

EXPORTER_JAR="$JARS_DIR/surprising-aeron-exporter.jar"
TOOLS_JAR="$JARS_DIR/surprising-aeron-tools.jar"
GATEWAY_JAR="$JARS_DIR/surprising-gateway.jar"
export EXPORTER_JAR TOOLS_JAR GATEWAY_JAR REPO_ROOT

DRIVER_LOG="$ATTEMPT_DIR/task-17-w5-faults.txt"
set +e
env PRODUCT_LINE="$PRODUCT_LINE" RUN_ID="$RUN_ID" KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
  DATABASE_URL="$POSTGRES_URL" DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
  AERON_HOSTNAMES="$AERON_HOSTNAMES" AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
  RUNTIME_ROOT="$RUNTIME_ROOT" REPO_ROOT="$REPO_ROOT" EXPORTER_JAR="$EXPORTER_JAR" \
  TOOLS_JAR="$TOOLS_JAR" GATEWAY_JAR="$GATEWAY_JAR" "$JAVA_BIN" "${JAVA_FLAGS[@]}" -cp "$EXPORTER_JAR" \
  com.surprising.aeron.exporter.W5FaultQaMain "$SCENARIO" > "$DRIVER_LOG" 2>&1
DRIVER_STATUS=$?
set -e
cat "$DRIVER_LOG"
(( DRIVER_STATUS == 0 )) || fail "W5_DRIVER_FAILED status=$DRIVER_STATUS artifact=$DRIVER_LOG"
if [[ "$SCENARIO" == export-projection ]]; then
  rg -q '^W5_EXPORT_PROJECTION=PASS ' "$DRIVER_LOG" || fail W5_EXPORT_PROJECTION_MARKER_MISSING
else
  rg -q '^W5_ISOLATION_RUNTIME=PASS ' "$DRIVER_LOG" || fail W5_ISOLATION_RUNTIME_MARKER_MISSING
  LIVE_LOG="$ATTEMPT_DIR/task-17-live-slow-client.txt"
  set +e
  env LIVE_GATEWAY_WS_URL="ws://127.0.0.1:9094/ws/v1" LIVE_GATEWAY_HTTP_URL="http://127.0.0.1:9094" \
    LIVE_KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" LIVE_DATABASE_URL="$POSTGRES_URL" \
    LIVE_DATABASE_USER="$POSTGRES_USER" LIVE_DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
    LIVE_PRODUCT_LINE="$PRODUCT_LINE" LIVE_RUN_ID="$RUN_ID" \
    SURPRISING_WEBSOCKET_SESSION_OUTBOUND_QUEUE_CAPACITY=2 \
    SURPRISING_WEBSOCKET_SESSION_SEND_TIMEOUT=200ms \
    "$MAVEN_BIN" -pl surprising-gateway -am \
    -Dtest=LiveSlowClientIsolationTest -DfailIfNoTests=false test > "$LIVE_LOG" 2>&1
  LIVE_STATUS=$?
  set -e
  cat "$LIVE_LOG"
  (( LIVE_STATUS == 0 )) || fail "LIVE_WS_TEST_FAILED status=$LIVE_STATUS artifact=$LIVE_LOG"
  rg -q '^LIVE_SLOW_CLIENT_ISOLATION=PASS ' "$LIVE_LOG" || fail LIVE_WS_PASS_MARKER_MISSING
  printf 'W5_ISOLATION_LIVE_GATE=PASS artifact=%s\n' "$LIVE_LOG"
fi

printf 'SCENARIO=PASS name=%s runId=%s evidence=%s\n' "$SCENARIO" "$RUN_ID" "$ATTEMPT_DIR"
