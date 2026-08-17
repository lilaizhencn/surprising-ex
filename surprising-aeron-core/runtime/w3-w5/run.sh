#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"
COMMON_SCRIPT="$SCRIPT_DIR/scenarios/common.sh"
PRODUCT_TOPIC_SOURCE="$REPO_ROOT/surprising-product-api/src/main/java/com/surprising/product/api/ProductTopicNames.java"
RUNTIME_ROOT="${RUNTIME_ROOT:-${TMPDIR:-/tmp}/surprising-w3-w5-runtime}"
RUN_ID="${RUN_ID:-}"
PRODUCT_LINE="${PRODUCT_LINE:-}"
WALLET_ENABLED="${WALLET_ENABLED:-false}"
TASK_RUN_FRESH="${TASK_RUN_FRESH:-false}"
POSTGRES_PORT="${POSTGRES_PORT:-25432}"
KAFKA_PORT="${KAFKA_PORT:-29092}"
POSTGRES_USER="${POSTGRES_USER:-surprising}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-surprising-local-only}"

readonly PROCESS_SERVICES=(exporter projector instrument price order matching trigger risk funding liquidation insurance adl gateway maker)
readonly HTTP_SERVICES=(instrument price order matching trigger risk funding liquidation insurance adl gateway maker)
readonly HTTP_PORTS=(
  "${INSTRUMENT_PORT:-9080}" "${PRICE_PORT:-9082}" "${ORDER_PORT:-9084}"
  "${MATCHING_PORT:-9085}" "${TRIGGER_PORT:-9095}" "${RISK_PORT:-9087}"
  "${FUNDING_PORT:-9089}" "${LIQUIDATION_PORT:-9088}" "${INSURANCE_PORT:-9090}"
  "${ADL_PORT:-9091}" "${GATEWAY_PORT:-9094}" "${MAKER_PORT:-9096}"
)

fail() {
  printf 'ERROR=%s\n' "$*" >&2
  exit 2
}

require_boolean() {
  case "$2" in true|false) ;; *) fail "$1 must be true or false" ;; esac
}

validate_context() {
  [[ -n "$RUN_ID" ]] || fail 'RUN_ID_REQUIRED'
  [[ "$RUN_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$ ]] || fail "INVALID_RUN_ID runId=$RUN_ID"
  [[ "$PRODUCT_LINE" == 'LINEAR_PERPETUAL' ]] || fail "PRODUCT_LINE_REFUSED expected=LINEAR_PERPETUAL actual=${PRODUCT_LINE:-unset}"
  require_boolean WALLET_ENABLED "$WALLET_ENABLED"
  [[ "$WALLET_ENABLED" == false ]] || fail 'WALLET_REFUSED wallet must remain absent'
  require_boolean TASK_RUN_FRESH "$TASK_RUN_FRESH"
  [[ -f "$COMPOSE_FILE" && -x "$COMMON_SCRIPT" ]] || fail 'RUNTIME_BUNDLE_INCOMPLETE'
  [[ -f "$PRODUCT_TOPIC_SOURCE" ]] || fail "PRODUCT_TOPIC_SOURCE_MISSING path=$PRODUCT_TOPIC_SOURCE"
  MAIN_WORKTREE="$(git -C "$REPO_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')"
  [[ "$REPO_ROOT" != "$MAIN_WORKTREE" ]] || fail "MAIN_WORKTREE_REFUSED path=$REPO_ROOT"
}

initialize_names() {
  RUN_DIR="$RUNTIME_ROOT/runs/$RUN_ID"
  PID_DIR="$RUN_DIR/pids"
  LOG_DIR="$RUN_DIR/logs"
  OWNER_FILE="$RUN_DIR/owner"
  READY_FILE="$RUN_DIR/ready.tsv"
  BEFORE_MANIFEST="$RUN_DIR/ownership-before.txt"
  AFTER_MANIFEST="$RUN_DIR/ownership-after.txt"
  MAIN_BEFORE="$RUN_DIR/main-worktree-before.txt"
  MAIN_AFTER="$RUN_DIR/main-worktree-after.txt"
  LOCK_DIR="$RUNTIME_ROOT/linear-perpetual.lock"
  LOCK_OWNER="$LOCK_DIR/owner"
  COMPOSE_PROJECT_NAME="surprising-w3w5-$(printf '%s' "$RUN_ID" | tr '[:upper:]' '[:lower:]')"
  COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME//_/-}"
  POSTGRES_DB="surprising_${RUN_ID//[^a-zA-Z0-9]/_}"
  export RUN_ID PRODUCT_LINE POSTGRES_PORT KAFKA_PORT POSTGRES_USER POSTGRES_PASSWORD
  export POSTGRES_DB COMPOSE_PROJECT_NAME
}

compose() {
  docker compose --project-name "$COMPOSE_PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

topic_list() {
  awk -v prefix='surprising.linear-perp' '
    /return topic\("/ {
      value=$0; sub(/^.*return topic\("/, "", value); sub(/"\).*$/, "", value);
      print prefix "." value ".v1"
    }
    /return INSTRUMENT_EVENTS_TOPIC/ { print "surprising.instrument.events.v1" }
  ' "$PRODUCT_TOPIC_SOURCE"
}

port_lines() {
  printf 'postgres=%s\nkafka=%s\n' "$POSTGRES_PORT" "$KAFKA_PORT"
  local index
  for index in "${!HTTP_SERVICES[@]}"; do
    printf '%s=%s\n' "${HTTP_SERVICES[$index]}" "${HTTP_PORTS[$index]}"
  done
  printf '%s\n' \
    'core.node0.archive=21001' 'core.node0.client=21002' 'core.node0.member=21003' 'core.node0.log=21004' 'core.node0.transfer=21005' \
    'core.node1.archive=21101' 'core.node1.client=21102' 'core.node1.member=21103' 'core.node1.log=21104' 'core.node1.transfer=21105' \
    'core.node2.archive=21201' 'core.node2.client=21202' 'core.node2.member=21203' 'core.node2.log=21204' 'core.node2.transfer=21205'
}

service_lines() {
  printf '%s\n' postgres kafka migrations core-node0 core-node1 core-node2 \
    exporter projector instrument price order matching trigger risk funding liquidation insurance adl gateway maker
}

assert_lock_available() {
  if [[ -d "$LOCK_DIR" ]]; then
    local owner='unknown'
    [[ -f "$LOCK_OWNER" ]] && owner="$(<"$LOCK_OWNER")"
    [[ "$owner" == "$RUN_ID" ]] || fail "CONCURRENT_RUNTIME_REFUSED owner=$owner requested=$RUN_ID"
  fi
}

assert_run_ownership() {
  if [[ -e "$RUN_DIR" ]]; then
    [[ -f "$OWNER_FILE" ]] || fail "OWNERSHIP_REFUSED missingOwner=$OWNER_FILE"
    [[ "$(<"$OWNER_FILE")" == "$RUN_ID" ]] || fail "OWNERSHIP_REFUSED runDir=$RUN_DIR"
  fi
}

assert_ports_free() {
  local entry port
  while IFS= read -r entry; do
    port="${entry#*=}"
    [[ "$port" =~ ^[0-9]+$ ]] || fail "INVALID_PORT value=$port"
    (( port > 0 && port < 65536 )) || fail "INVALID_PORT value=$port"
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      fail "PORT_OCCUPIED port=$port"
    fi
  done < <(port_lines | grep -v '^core\.')
}

container_ids() {
  docker ps -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"
}

volume_names() {
  docker volume ls -q --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME"
}

assert_compose_ownership() {
  local id label project volume
  while IFS= read -r id; do
    [[ -z "$id" ]] && continue
    label="$(docker inspect --format '{{ index .Config.Labels "com.surprising.runtime.run-id" }}' "$id")"
    project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$id")"
    [[ "$label" == "$RUN_ID" && "$project" == "$COMPOSE_PROJECT_NAME" ]] || \
      fail "OWNERSHIP_REFUSED container=$id expectedRun=$RUN_ID actualRun=$label"
  done < <(container_ids)
  while IFS= read -r volume; do
    [[ -z "$volume" ]] && continue
    label="$(docker volume inspect --format '{{ index .Labels "com.surprising.runtime.run-id" }}' "$volume")"
    project="$(docker volume inspect --format '{{ index .Labels "com.docker.compose.project" }}' "$volume")"
    [[ "$label" == "$RUN_ID" && "$project" == "$COMPOSE_PROJECT_NAME" ]] || \
      fail "OWNERSHIP_REFUSED volume=$volume expectedRun=$RUN_ID actualRun=$label"
  done < <(volume_names)
}

assert_pid_ownership() {
  [[ -d "$PID_DIR" ]] || return 0
  local pid_file pid name command marker
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    name="$(basename "$pid_file" .pid)"
    [[ "$pid" =~ ^[0-9]+$ ]] || fail "OWNERSHIP_REFUSED pidFile=$pid_file"
    kill -0 "$pid" 2>/dev/null || continue
    command="$(ps -p "$pid" -o command=)"
    marker="surprising-w3w5:$RUN_ID:$name"
    [[ "$command" == *"$marker"* ]] || fail "OWNERSHIP_REFUSED pid=$pid service=$name"
  done
}

write_inventory() {
  local target="$1"
  {
    printf 'RUN_ID=%s\nPRODUCT_LINE=%s\nCOMPOSE_PROJECT=%s\n' "$RUN_ID" "$PRODUCT_LINE" "$COMPOSE_PROJECT_NAME"
    printf '[ports]\n'; port_lines
    printf '[pids]\n'
    if [[ -d "$PID_DIR" ]]; then
      for file in "$PID_DIR"/*.pid; do [[ -e "$file" ]] && printf '%s=%s\n' "$(basename "$file" .pid)" "$(<"$file")"; done
    fi
    printf '[containers]\n'; container_ids
    printf '[volumes]\n'; volume_names
  } > "$target"
}

write_main_worktree_fingerprint() {
  local target="$1" index_path
  index_path="$(git -C "$MAIN_WORKTREE" rev-parse --absolute-git-dir)/index"
  {
    printf 'HEAD=%s\n' "$(git -C "$MAIN_WORKTREE" rev-parse HEAD)"
    printf 'INDEX_SHA256='; shasum -a 256 "$index_path" | awk '{ print $1 }'
    printf '[status]\n'
    GIT_OPTIONAL_LOCKS=0 git -C "$MAIN_WORKTREE" status --porcelain=v2 --untracked-files=no
  } > "$target"
}

claim_runtime() {
  assert_lock_available
  assert_run_ownership
  assert_compose_ownership
  mkdir -p "$RUNTIME_ROOT" || fail "RUNTIME_ROOT_CREATE_FAILED path=$RUNTIME_ROOT"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    assert_lock_available
    fail 'CONCURRENT_RUNTIME_REFUSED lock race'
  fi
  printf '%s\n' "$RUN_ID" > "$LOCK_OWNER"
  mkdir -p "$PID_DIR" "$LOG_DIR"
  printf '%s\n' "$RUN_ID" > "$OWNER_FILE"
  : > "$READY_FILE"
  write_main_worktree_fingerprint "$MAIN_BEFORE"
  write_inventory "$BEFORE_MANIFEST"
}

release_lock() {
  [[ -d "$LOCK_DIR" && -f "$LOCK_OWNER" ]] || return 0
  [[ "$(<"$LOCK_OWNER")" == "$RUN_ID" ]] || fail "OWNERSHIP_REFUSED lock=$LOCK_DIR"
  rm -f "$LOCK_OWNER"
  rmdir "$LOCK_DIR"
}

wait_container() {
  local service="$1" deadline=$((SECONDS + 60)) state health
  while (( SECONDS < deadline )); do
    state="$(compose ps --format json "$service" 2>/dev/null | grep -o '"State":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    health="$(compose ps --format json "$service" 2>/dev/null | grep -o '"Health":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [[ "$state" == running && ( -z "$health" || "$health" == healthy ) ]]; then return 0; fi
    sleep 1
  done
  fail "READINESS_TIMEOUT service=$service state=${state:-missing} health=${health:-none}"
}

mark_ready() {
  printf '%s\t%s\n' "$(( $(wc -l < "$READY_FILE") + 1 ))" "$1" >> "$READY_FILE"
  printf 'READY=%s\n' "$1"
}

run_migrations() {
  local migration
  if [[ -f "$REPO_ROOT/init.sql" ]]; then
    compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      < "$REPO_ROOT/init.sql" >/dev/null
  fi
  for migration in "$REPO_ROOT"/migrations/*.sql; do
    [[ -e "$migration" ]] || continue
    compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
      < "$migration" >/dev/null
  done
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
  local name="$1" port="$2"
  shift 2
  local marker="surprising-w3w5:$RUN_ID:$name"
  bash -c '
    marker="$0"
    child=""
    terminate() { [[ -z "$child" ]] || kill "$child" 2>/dev/null || true; [[ -z "$child" ]] || wait "$child" 2>/dev/null || true; exit 0; }
    trap terminate TERM INT
    "$@" & child=$!
    wait "$child"
  ' "$marker" "$@" >"$LOG_DIR/$name.log" 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" > "$PID_DIR/$name.pid"
  if [[ -n "$port" ]]; then
    local deadline=$((SECONDS + 30))
    while ! port_owned_by_process_tree "$port" "$pid"; do
      kill -0 "$pid" 2>/dev/null || fail "PROCESS_EXITED service=$name log=$LOG_DIR/$name.log"
      (( SECONDS < deadline )) || fail "READINESS_TIMEOUT service=$name port=$port"
      sleep 1
    done
  else
    sleep 1
    kill -0 "$pid" 2>/dev/null || fail "PROCESS_EXITED service=$name log=$LOG_DIR/$name.log"
  fi
  mark_ready "$name"
}

port_owned_by_process_tree() {
  local port="$1" owner_pid="$2" listener current parent depth
  while IFS= read -r listener; do
    [[ -n "$listener" ]] || continue
    current="$listener"
    depth=0
    while [[ "$current" =~ ^[0-9]+$ ]] && (( depth < 12 )); do
      [[ "$current" == "$owner_pid" ]] && return 0
      parent="$(ps -p "$current" -o ppid= 2>/dev/null | tr -d ' ' || true)"
      [[ -n "$parent" && "$parent" != "$current" ]] || break
      current="$parent"
      depth=$((depth + 1))
    done
  done < <(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u)
  return 1
}

jar_path() {
  case "$1" in
    exporter|projector) printf '%s/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar' "$REPO_ROOT" ;;
    instrument) printf '%s/surprising-instrument/surprising-instrument-provider/target/surprising-instrument-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    price) printf '%s/surprising-price/surprising-price-provider/target/surprising-price-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    order) printf '%s/surprising-trading/surprising-order-provider/target/surprising-order-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    matching) printf '%s/surprising-trading/surprising-matching-provider/target/surprising-matching-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    trigger) printf '%s/surprising-trading/surprising-trigger-provider/target/surprising-trigger-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    risk) printf '%s/surprising-risk/surprising-risk-provider/target/surprising-risk-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    funding) printf '%s/surprising-funding/surprising-funding-provider/target/surprising-funding-provider-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    liquidation) printf '%s/surprising-liquidation/target/surprising-liquidation-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    insurance) printf '%s/surprising-insurance/target/surprising-insurance-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    adl) printf '%s/surprising-adl/target/surprising-adl-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    gateway) printf '%s/surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    maker) printf '%s/surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT.jar' "$REPO_ROOT" ;;
    *) fail "UNKNOWN_SERVICE service=$1" ;;
  esac
}

preflight_real_artifacts() {
  docker image inspect "surprising/aeron-core:${AERON_CORE_IMAGE_TAG:-local}" >/dev/null 2>&1 || \
    fail "CORE_IMAGE_MISSING image=surprising/aeron-core:${AERON_CORE_IMAGE_TAG:-local}"
  local service jar
  for service in "${PROCESS_SERVICES[@]}"; do
    jar="$(jar_path "$service")"
    [[ -f "$jar" ]] || fail "SERVICE_ARTIFACT_MISSING service=$service path=$jar"
  done
}

start_process_stack() {
  local mode="$1" index service port jar main_class
  for index in "${!PROCESS_SERVICES[@]}"; do
    service="${PROCESS_SERVICES[$index]}"
    port=''
    for http_index in "${!HTTP_SERVICES[@]}"; do
      [[ "${HTTP_SERVICES[$http_index]}" != "$service" ]] || port="${HTTP_PORTS[$http_index]}"
    done
    if [[ "$mode" == fixture ]]; then
      if [[ -n "$port" ]]; then
        start_owned_process "$service" "$port" "$COMMON_SCRIPT" health-server "$service" "$port"
      else
        start_owned_process "$service" '' "$COMMON_SCRIPT" idle
      fi
      continue
    fi
    jar="$(jar_path "$service")"
    if [[ "$service" == projector ]]; then
      main_class=com.surprising.aeron.exporter.ProjectionMain
      start_owned_process "$service" '' env PRODUCT_LINE="$PRODUCT_LINE" KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT" \
        DATABASE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$POSTGRES_DB" DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
        "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$jar" "$main_class"
    else
      start_owned_process "$service" "$port" env PRODUCT_LINE="$PRODUCT_LINE" SERVER_PORT="${port:-0}" \
        KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT" SPRING_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KAFKA_PORT" \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$POSTGRES_DB" SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
        "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$jar"
    fi
  done
}

up_internal() {
  local mode="$1"
  assert_ports_free
  [[ "$mode" == fixture ]] || preflight_real_artifacts
  claim_runtime
  trap 'cleanup_after_failed_up' EXIT ERR INT TERM
  compose up -d postgres kafka node0 node1 node2
  wait_container postgres; mark_ready postgres
  wait_container kafka; mark_ready kafka
  run_migrations
  create_topics
  wait_container node0; mark_ready core-node0
  wait_container node1; mark_ready core-node1
  wait_container node2; mark_ready core-node2
  start_process_stack "$mode"
  [[ "$(tail -1 "$READY_FILE" | cut -f2)" == maker ]] || fail 'MAKER_ORDER_VIOLATION'
  write_inventory "$RUN_DIR/ownership-live.txt"
  trap - EXIT ERR INT TERM
  printf 'UP=PASS runId=%s mode=%s\n' "$RUN_ID" "$mode"
}

stop_processes() {
  assert_pid_ownership
  [[ -d "$PID_DIR" ]] || return 0
  local pid_file pid
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    kill -0 "$pid" 2>/dev/null && kill -TERM "$pid"
  done
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    local deadline=$((SECONDS + 15))
    while kill -0 "$pid" 2>/dev/null && (( SECONDS < deadline )); do sleep 1; done
    kill -0 "$pid" 2>/dev/null && fail "PROCESS_CLEANUP_TIMEOUT pid=$pid"
    rm -f "$pid_file"
  done
}

down_internal() {
  assert_run_ownership
  assert_pid_ownership
  assert_compose_ownership
  stop_processes
  if [[ "$TASK_RUN_FRESH" == true ]]; then
    compose down --volumes --remove-orphans
  else
    compose down --remove-orphans
  fi
  release_lock
  write_inventory "$AFTER_MANIFEST"
  write_main_worktree_fingerprint "$MAIN_AFTER"
  if container_ids | grep -q .; then fail 'CLEANUP_FAILED containers remain'; fi
  if [[ -d "$PID_DIR" ]] && find "$PID_DIR" -name '*.pid' -print -quit | grep -q .; then fail 'CLEANUP_FAILED pids remain'; fi
  cmp -s "$MAIN_BEFORE" "$MAIN_AFTER" || fail 'MAIN_WORKTREE_CHANGED protected status/index fingerprint differs'
  printf 'MAIN_WORKTREE_PROTECTED=PASS\n'
  printf 'CLEANUP=PASS runId=%s volumes=%s\n' "$RUN_ID" "$(if [[ "$TASK_RUN_FRESH" == true ]]; then printf removed; else printf preserved; fi)"
}

cleanup_after_failed_up() {
  local status=$?
  trap - EXIT ERR INT TERM
  printf 'UP_FAILED runId=%s cleanup=begin\n' "$RUN_ID" >&2
  down_internal || true
  exit "$status"
}

print_dry_run() {
  assert_lock_available
  assert_run_ownership
  assert_compose_ownership
  assert_pid_ownership
  assert_ports_free
  printf 'DRY_RUN=PASS\nRUN_ID=%s\nPRODUCT_LINE=%s\nCOMPOSE_PROJECT=%s\n' "$RUN_ID" "$PRODUCT_LINE" "$COMPOSE_PROJECT_NAME"
  printf 'TOPIC_PREFIX=surprising.linear-perp\n[topics]\n'; topic_list
  printf '[services]\n'; service_lines
  printf '[ports]\n'; port_lines
  printf 'MAKER_POSITION=LAST\nWALLET=ABSENT\nMUTATION=NONE\n'
}

print_status() {
  assert_run_ownership
  assert_pid_ownership
  assert_compose_ownership
  [[ -d "$LOCK_DIR" && -f "$LOCK_OWNER" && "$(<"$LOCK_OWNER")" == "$RUN_ID" ]] || \
    fail "NOT_RUNNING runId=$RUN_ID"
  [[ -f "$READY_FILE" ]] || fail "NOT_RUNNING runId=$RUN_ID"
  printf 'STATUS=RUNNING\nRUN_ID=%s\nPRODUCT_LINE=%s\n' "$RUN_ID" "$PRODUCT_LINE"
  printf '[ready]\n'; cat "$READY_FILE"
  printf '[containers]\n'; compose ps --format json
  printf 'MAKER_POSITION=%s\nWALLET=ABSENT\n' "$(tail -1 "$READY_FILE" | cut -f2)"
}

validate_context
initialize_names
command_name="${1:-status}"

case "$command_name" in
  dry-run) print_dry_run ;;
  up) up_internal real ;;
  status) print_status ;;
  down) down_internal ;;
  run)
    up_internal real
    trap 'down_internal' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    print_status
    while true; do sleep 30; done
    ;;
  smoke)
    up_internal fixture
    print_status
    down_internal
    ;;
  *) fail "USAGE command=$command_name expected=up,status,run,down,dry-run,smoke" ;;
esac
