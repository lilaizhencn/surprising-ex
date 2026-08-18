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

readonly PROCESS_SERVICES=(exporter projector instrument price account order matching trigger risk funding liquidation insurance adl gateway maker)
readonly HTTP_SERVICES=(instrument price account order matching trigger risk funding liquidation insurance adl gateway maker)
readonly HTTP_PORTS=(
  "${INSTRUMENT_PORT:-9080}" "${PRICE_PORT:-9082}" "${ACCOUNT_PORT:-9086}" "${ORDER_PORT:-9084}"
  "${MATCHING_PORT:-9085}" "${TRIGGER_PORT:-9095}" "${RISK_PORT:-9087}"
  "${FUNDING_PORT:-9089}" "${LIQUIDATION_PORT:-9088}" "${INSURANCE_PORT:-9090}"
  "${ADL_PORT:-9091}" "${GATEWAY_PORT:-9094}" "${MAKER_PORT:-9096}"
)

service_enabled() {
  case "$1" in
    funding) [[ "$PRODUCT_LINE" == LINEAR_PERPETUAL || "$PRODUCT_LINE" == INVERSE_PERPETUAL ]] ;;
    liquidation|insurance|adl) [[ "$PRODUCT_LINE" != SPOT ]] ;;
    *) return 0 ;;
  esac
}

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
  case "$PRODUCT_LINE" in
    SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
    *) fail "PRODUCT_LINE_REFUSED unsupported=${PRODUCT_LINE:-unset}" ;;
  esac
  require_boolean WALLET_ENABLED "$WALLET_ENABLED"
  [[ "$WALLET_ENABLED" == false ]] || fail 'WALLET_REFUSED wallet must remain absent'
  require_boolean TASK_RUN_FRESH "$TASK_RUN_FRESH"
  [[ -f "$COMPOSE_FILE" && -x "$COMMON_SCRIPT" ]] || fail 'RUNTIME_BUNDLE_INCOMPLETE'
  [[ -f "$PRODUCT_TOPIC_SOURCE" ]] || fail "PRODUCT_TOPIC_SOURCE_MISSING path=$PRODUCT_TOPIC_SOURCE"
  if [[ "${W4_STATIC_ONLY:-false}" != true ]]; then
    MAIN_WORKTREE="${W4_MAIN_WORKTREE:-$(git -C "$REPO_ROOT" worktree list --porcelain | awk '/^worktree / { print substr($0, 10); exit }')}"
    [[ -d "$MAIN_WORKTREE" ]] || fail "MAIN_WORKTREE_MISSING path=$MAIN_WORKTREE"
    [[ "$REPO_ROOT" != "$MAIN_WORKTREE" ]] || fail "MAIN_WORKTREE_REFUSED path=$REPO_ROOT"
  fi
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
  initialize_core_ports
  AERON_CLUSTER_HOSTNAMES="${AERON_CLUSTER_HOSTNAMES:-127.0.0.1,127.0.0.1,127.0.0.1}"
  AERON_EGRESS_HOSTNAME="${AERON_EGRESS_HOSTNAME:-127.0.0.1}"
  export RUN_ID PRODUCT_LINE POSTGRES_PORT KAFKA_PORT POSTGRES_USER POSTGRES_PASSWORD
  export POSTGRES_DB COMPOSE_PROJECT_NAME
  export AERON_CLUSTER_HOSTNAMES AERON_EGRESS_HOSTNAME
  export CORE_NODE0_ARCHIVE_PORT CORE_NODE0_CLIENT_PORT CORE_NODE0_MEMBER_PORT CORE_NODE0_LOG_PORT CORE_NODE0_TRANSFER_PORT
  export CORE_NODE1_ARCHIVE_PORT CORE_NODE1_CLIENT_PORT CORE_NODE1_MEMBER_PORT CORE_NODE1_LOG_PORT CORE_NODE1_TRANSFER_PORT
  export CORE_NODE2_ARCHIVE_PORT CORE_NODE2_CLIENT_PORT CORE_NODE2_MEMBER_PORT CORE_NODE2_LOG_PORT CORE_NODE2_TRANSFER_PORT
}

initialize_core_ports() {
  local product_index
  case "$PRODUCT_LINE" in
    SPOT) product_index=0 ;;
    LINEAR_PERPETUAL) product_index=1 ;;
    INVERSE_PERPETUAL) product_index=2 ;;
    LINEAR_DELIVERY) product_index=3 ;;
    INVERSE_DELIVERY) product_index=4 ;;
    OPTION) product_index=5 ;;
    *) fail "PRODUCT_LINE_REFUSED unsupported=$PRODUCT_LINE" ;;
  esac
  local product_base=$((20000 + product_index * 1000)) node node_base
  for node in 0 1 2; do
    node_base=$((product_base + node * 100))
    printf -v "CORE_NODE${node}_ARCHIVE_PORT" '%d' "$((node_base + 1))"
    printf -v "CORE_NODE${node}_CLIENT_PORT" '%d' "$((node_base + 2))"
    printf -v "CORE_NODE${node}_MEMBER_PORT" '%d' "$((node_base + 3))"
    printf -v "CORE_NODE${node}_LOG_PORT" '%d' "$((node_base + 4))"
    printf -v "CORE_NODE${node}_TRANSFER_PORT" '%d' "$((node_base + 5))"
  done
}

compose() {
  docker compose --project-name "$COMPOSE_PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

topic_list() {
  awk -v prefix="surprising.$(topic_segment)" '
    /return topic\("/ {
      value=$0; sub(/^.*return topic\("/, "", value); sub(/"\).*$/, "", value);
      print prefix "." value ".v1"
    }
    /return INSTRUMENT_EVENTS_TOPIC/ { print "surprising.instrument.events.v1" }
  ' "$PRODUCT_TOPIC_SOURCE"
}

topic_segment() {
  case "$PRODUCT_LINE" in
    SPOT) printf 'spot' ;;
    LINEAR_PERPETUAL) printf 'linear-perp' ;;
    INVERSE_PERPETUAL) printf 'inverse-perp' ;;
    LINEAR_DELIVERY) printf 'linear-delivery' ;;
    INVERSE_DELIVERY) printf 'inverse-delivery' ;;
    OPTION) printf 'option' ;;
    *) fail "PRODUCT_LINE_REFUSED unsupported=$PRODUCT_LINE" ;;
  esac
}

port_lines() {
  printf 'postgres=%s\nkafka=%s\n' "$POSTGRES_PORT" "$KAFKA_PORT"
  local index
  for index in "${!HTTP_SERVICES[@]}"; do
    printf '%s=%s\n' "${HTTP_SERVICES[$index]}" "${HTTP_PORTS[$index]}"
  done
  local node port_name port
  for node in 0 1 2; do
    for port_name in archive client member log transfer; do
      case "${node}:${port_name}" in
        0:archive) port="$CORE_NODE0_ARCHIVE_PORT" ;; 0:client) port="$CORE_NODE0_CLIENT_PORT" ;;
        0:member) port="$CORE_NODE0_MEMBER_PORT" ;; 0:log) port="$CORE_NODE0_LOG_PORT" ;; 0:transfer) port="$CORE_NODE0_TRANSFER_PORT" ;;
        1:archive) port="$CORE_NODE1_ARCHIVE_PORT" ;; 1:client) port="$CORE_NODE1_CLIENT_PORT" ;;
        1:member) port="$CORE_NODE1_MEMBER_PORT" ;; 1:log) port="$CORE_NODE1_LOG_PORT" ;; 1:transfer) port="$CORE_NODE1_TRANSFER_PORT" ;;
        2:archive) port="$CORE_NODE2_ARCHIVE_PORT" ;; 2:client) port="$CORE_NODE2_CLIENT_PORT" ;;
        2:member) port="$CORE_NODE2_MEMBER_PORT" ;; 2:log) port="$CORE_NODE2_LOG_PORT" ;; 2:transfer) port="$CORE_NODE2_TRANSFER_PORT" ;;
      esac
      printf 'core.node%s.%s=%s\n' "$node" "$port_name" "$port"
    done
  done
}

service_lines() {
  printf '%s\n' postgres kafka migrations core-node0 core-node1 core-node2 \
    exporter projector instrument price account order matching trigger risk funding liquidation insurance adl gateway maker
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
    if [[ "$entry" == core.* ]]; then
      if lsof -nP -iUDP:"$port" >/dev/null 2>&1; then
        fail "PORT_OCCUPIED port=$port protocol=udp"
      fi
    elif lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      fail "PORT_OCCUPIED port=$port"
    fi
  done < <(port_lines)
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
  nohup bash -c '
    marker="$0"
    child=""
    terminate() {
      if [[ -n "$child" ]]; then
        kill -TERM "$child" 2>/dev/null || true
        child_deadline=$((SECONDS + 10))
        while kill -0 "$child" 2>/dev/null && (( SECONDS < child_deadline )); do sleep 1; done
        kill -KILL "$child" 2>/dev/null || true
        wait "$child" 2>/dev/null || true
      fi
      exit 0
    }
    trap terminate TERM INT
    "$@" & child=$!
    wait "$child"
  ' "$marker" "$@" >"$LOG_DIR/$name.log" 2>&1 < /dev/null &
  local pid=$!
  printf '%s\n' "$pid" > "$PID_DIR/$name.pid"
  if [[ -n "$port" ]]; then
    local deadline=$((SECONDS + ${PROCESS_READINESS_TIMEOUT_SECONDS:-90}))
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

seed_instrument_snapshot() {
  local symbol="BTC-USDT"
  local instrument_port="${INSTRUMENT_PORT:-9080}"
  local contract_type instrument_type quote_asset settle_asset expiry_json settlement_json
  local reduce_only="false" funding_interval=0 interest_rate=0 funding_cap=0 funding_floor=0
  local min_sources=1 brackets='[]' underlying_json=null strike_json=null option_type_json=null option_style_json=null
  case "$PRODUCT_LINE" in
    SPOT)
      contract_type=SPOT; instrument_type=SPOT; quote_asset=USDT; settle_asset=USDT
      ;;
    LINEAR_PERPETUAL|INVERSE_PERPETUAL)
      contract_type="$PRODUCT_LINE"; instrument_type=PERPETUAL
      quote_asset=USDT; settle_asset=USDT; reduce_only="true"
      [[ "$PRODUCT_LINE" == INVERSE_PERPETUAL ]] && quote_asset=USD && settle_asset=BTC
      funding_interval=8; interest_rate=100; funding_cap=3000; funding_floor=-3000
      min_sources=2
      brackets='[{"bracketNo":1,"notionalFloorUnits":0,"notionalCapUnits":5000000000000,"maxLeveragePpm":100000000,"initialMarginRatePpm":10000,"maintenanceMarginRatePpm":5000}]'
      ;;
    LINEAR_DELIVERY|INVERSE_DELIVERY)
      contract_type="$PRODUCT_LINE"; instrument_type=DELIVERY
      quote_asset=USDT; settle_asset=USDT; reduce_only="true"; min_sources=2
      [[ "$PRODUCT_LINE" == INVERSE_DELIVERY ]] && quote_asset=USD && settle_asset=BTC
      expiry_json='"2030-01-01T00:00:00Z"'; settlement_json='"CASH"'
      brackets='[{"bracketNo":1,"notionalFloorUnits":0,"notionalCapUnits":5000000000000,"maxLeveragePpm":100000000,"initialMarginRatePpm":10000,"maintenanceMarginRatePpm":5000}]'
      ;;
    OPTION)
      contract_type=VANILLA_OPTION; instrument_type=OPTION
      symbol="BTC-USDT-OPTION-SEED"
      quote_asset=USDT; settle_asset=USDT; reduce_only="true"; min_sources=2
      expiry_json='"2030-01-01T00:00:00Z"'; settlement_json='"CASH"'
      underlying_json='"BTC-USDT"'; strike_json=100
      option_type_json='"CALL"'; option_style_json='"EUROPEAN"'
      brackets='[{"bracketNo":1,"notionalFloorUnits":0,"notionalCapUnits":5000000000000,"maxLeveragePpm":100000000,"initialMarginRatePpm":10000,"maintenanceMarginRatePpm":5000}]'
      ;;
    *) fail "BOOTSTRAP_PRODUCT_LINE_REFUSED line=$PRODUCT_LINE" ;;
  esac
  expiry_json="${expiry_json:-null}"
  settlement_json="${settlement_json:-null}"
  local sources='[{"source":"BOOTSTRAP-A","enabled":true,"baseUrl":"https://api.exchange.coinbase.com","path":"/products/BTC-USD/ticker","sourceSymbol":"BTC-USD","parser":"COINBASE_TICKER","quoteCurrency":"USD","targetQuoteCurrency":"USDT","conversionBaseUrl":null,"conversionPath":null,"conversionParser":null,"conversionMode":null,"conversionOperation":null,"fallbackWeightMultiplierPpm":500000,"websocketEnabled":false,"websocketUrl":null,"websocketSubscribeMessage":null,"websocketParser":null,"weightPpm":500000}'
  if (( min_sources == 2 )); then
    sources+=',{"source":"BOOTSTRAP-B","enabled":true,"baseUrl":"https://api.exchange.coinbase.com","path":"/products/BTC-USD/ticker","sourceSymbol":"BTC-USD","parser":"COINBASE_TICKER","quoteCurrency":"USD","targetQuoteCurrency":"USDT","conversionBaseUrl":null,"conversionPath":null,"conversionParser":null,"conversionMode":null,"conversionOperation":null,"fallbackWeightMultiplierPpm":500000,"websocketEnabled":false,"websocketUrl":null,"websocketSubscribeMessage":null,"websocketParser":null,"weightPpm":500000}'
  fi
  sources+=']'
  if [[ "$PRODUCT_LINE" == OPTION ]]; then
    local underlying_body="{\"symbol\":\"BTC-USDT\",\"instrumentType\":\"SPOT\",\"contractType\":\"SPOT\",\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"settleAsset\":\"USDT\",\"contractMultiplierPpm\":1000000,\"contractValueAsset\":\"USDT\",\"priceTickUnits\":1,\"quantityStepUnits\":1,\"minQuantitySteps\":1,\"maxQuantitySteps\":100000,\"minNotionalUnits\":1,\"maxNotionalUnits\":1000000000000,\"notionalMultiplierUnits\":1,\"pricePrecision\":2,\"quantityPrecision\":3,\"supportedOrderTypes\":[\"LIMIT\"],\"supportedTimeInForce\":[\"GTC\",\"IOC\"],\"postOnlyEnabled\":true,\"reduceOnlyEnabled\":false,\"marketOrderEnabled\":false,\"maxLeveragePpm\":100000000,\"initialMarginRatePpm\":10000,\"maintenanceMarginRatePpm\":5000,\"makerFeeRatePpm\":200,\"takerFeeRatePpm\":500,\"maxPositionNotionalUnits\":25000000000000,\"userOpenInterestLimitRatePpm\":0,\"userOpenInterestLimitFloorUnits\":1,\"fundingIntervalHours\":0,\"interestRatePpm\":0,\"fundingRateCapPpm\":0,\"fundingRateFloorPpm\":0,\"impactNotionalUnits\":1000000000000,\"minValidIndexSources\":1,\"expiryTime\":null,\"deliveryTime\":null,\"underlyingSymbol\":null,\"strikePriceUnits\":null,\"optionType\":null,\"optionExerciseStyle\":null,\"settlementMethod\":null,\"status\":\"TRADING\",\"effectiveTime\":null,\"riskLimitBrackets\":[],\"indexSources\":$sources}"
    curl --fail-with-body --silent --show-error --retry 10 --retry-delay 1 --max-time 20 \
      -H 'Content-Type: application/json' -X POST \
      --data "$underlying_body" "http://127.0.0.1:${instrument_port}/api/v1/instruments/admin/upsert" --output /dev/stderr
  fi
  local body="{\"symbol\":\"$symbol\",\"instrumentType\":\"$instrument_type\",\"contractType\":\"$contract_type\",\"baseAsset\":\"BTC\",\"quoteAsset\":\"$quote_asset\",\"settleAsset\":\"$settle_asset\",\"contractMultiplierPpm\":1000000,\"contractValueAsset\":\"$settle_asset\",\"priceTickUnits\":1,\"quantityStepUnits\":1,\"minQuantitySteps\":1,\"maxQuantitySteps\":100000,\"minNotionalUnits\":1,\"maxNotionalUnits\":1000000000000,\"notionalMultiplierUnits\":1,\"pricePrecision\":2,\"quantityPrecision\":3,\"supportedOrderTypes\":[\"LIMIT\"],\"supportedTimeInForce\":[\"GTC\",\"IOC\"],\"postOnlyEnabled\":true,\"reduceOnlyEnabled\":$reduce_only,\"marketOrderEnabled\":false,\"maxLeveragePpm\":100000000,\"initialMarginRatePpm\":10000,\"maintenanceMarginRatePpm\":5000,\"makerFeeRatePpm\":200,\"takerFeeRatePpm\":500,\"maxPositionNotionalUnits\":25000000000000,\"userOpenInterestLimitRatePpm\":0,\"userOpenInterestLimitFloorUnits\":1,\"fundingIntervalHours\":$funding_interval,\"interestRatePpm\":$interest_rate,\"fundingRateCapPpm\":$funding_cap,\"fundingRateFloorPpm\":$funding_floor,\"impactNotionalUnits\":1000000000000,\"minValidIndexSources\":$min_sources,\"expiryTime\":$expiry_json,\"deliveryTime\":$expiry_json,\"underlyingSymbol\":$underlying_json,\"strikePriceUnits\":$strike_json,\"optionType\":$option_type_json,\"optionExerciseStyle\":$option_style_json,\"settlementMethod\":$settlement_json,\"status\":\"TRADING\",\"effectiveTime\":null,\"riskLimitBrackets\":$brackets,\"indexSources\":$sources}"
  curl --fail-with-body --silent --show-error --retry 10 --retry-delay 1 --max-time 20 \
    -H 'Content-Type: application/json' -X POST \
    --data "$body" "http://127.0.0.1:${instrument_port}/api/v1/instruments/admin/upsert" --output /dev/stderr
  curl --fail --silent --show-error --retry 10 --retry-delay 1 --max-time 10 \
    "http://127.0.0.1:${instrument_port}/api/v1/instruments/admin/$symbol?productLine=$PRODUCT_LINE" >/dev/null
  printf 'BOOTSTRAP=instrument productLine=%s symbol=%s\n' "$PRODUCT_LINE" "$symbol"
}

jar_path() {
  case "$1" in
    core) printf '%s/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar' "$REPO_ROOT" ;;
    tools) printf '%s/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar' "$REPO_ROOT" ;;
    exporter|projector) printf '%s/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar' "$REPO_ROOT" ;;
    instrument) printf '%s/surprising-instrument/surprising-instrument-provider/target/surprising-instrument-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    price) printf '%s/surprising-price/surprising-price-provider/target/surprising-price-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    account) printf '%s/surprising-account/surprising-account-provider/target/surprising-account-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    order) printf '%s/surprising-trading/surprising-order-provider/target/surprising-order-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    matching) printf '%s/surprising-trading/surprising-matching-provider/target/surprising-matching-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    trigger) printf '%s/surprising-trading/surprising-trigger-provider/target/surprising-trigger-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    risk) printf '%s/surprising-risk/surprising-risk-provider/target/surprising-risk-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    funding) printf '%s/surprising-funding/surprising-funding-provider/target/surprising-funding-provider-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    liquidation) printf '%s/surprising-liquidation/target/surprising-liquidation-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    insurance) printf '%s/surprising-insurance/target/surprising-insurance-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    adl) printf '%s/surprising-adl/target/surprising-adl-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    gateway) printf '%s/surprising-gateway/target/surprising-gateway-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    maker) printf '%s/surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT-exec.jar' "$REPO_ROOT" ;;
    *) fail "UNKNOWN_SERVICE service=$1" ;;
  esac
}

preflight_real_artifacts() {
  local service jar
  for service in core tools; do
    jar="$(jar_path "$service")"
    [[ -f "$jar" ]] || fail "SERVICE_ARTIFACT_MISSING service=$service path=$jar"
  done
  for service in "${PROCESS_SERVICES[@]}"; do
    service_enabled "$service" || continue
    jar="$(jar_path "$service")"
    [[ -f "$jar" ]] || fail "SERVICE_ARTIFACT_MISSING service=$service path=$jar"
  done
}

start_host_core() {
  local node java_bin core_jar
  local -a java_options=(
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  )
  java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  core_jar="$(jar_path core)"
  for node in 0 1 2; do
    start_owned_process "core-node$node" '' env \
      PRODUCT_LINE="$PRODUCT_LINE" \
      AERON_HOSTNAMES="$AERON_CLUSTER_HOSTNAMES" \
      AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
      "$java_bin" "${java_options[@]}" \
      "-Dsurprising.aeron.product-line=$PRODUCT_LINE" \
      "-Dsurprising.aeron.node-id=$node" \
      "-Dsurprising.aeron.hostnames=$AERON_CLUSTER_HOSTNAMES" \
      "-Dsurprising.aeron.data-dir=$RUN_DIR/aeron" \
      -jar "$core_jar"
  done
}

verify_host_core() {
  local java_bin tools_jar attempt output status=1 deadline=$((SECONDS + 60))
  local -a java_options=(
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  )
  java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  tools_jar="$(jar_path tools)"
  while (( SECONDS < deadline )); do
    set +e
    output="$("$java_bin" "${java_options[@]}" \
      "-Dsurprising.aeron.product-line=$PRODUCT_LINE" \
      "-Dsurprising.aeron.hostnames=$AERON_CLUSTER_HOSTNAMES" \
      "-Dsurprising.aeron.egress-hostname=$AERON_EGRESS_HOSTNAME" \
      -Dsurprising.aeron.probe-mode=query -Dsurprising.aeron.source-id=17017 \
      -cp "$tools_jar" com.surprising.aeron.tools.ClusterProbeMain 2>&1)"
    status=$?
    set -e
    printf '%s\n' "$output" >> "$LOG_DIR/core-probe.log"
    if (( status == 0 )) && grep -q '^status=OK ' <<<"$output"; then
      mark_ready core-cluster
      printf 'CORE_CLUSTER=PASS productLine=%s hosts=%s egress=%s\n' \
        "$PRODUCT_LINE" "$AERON_CLUSTER_HOSTNAMES" "$AERON_EGRESS_HOSTNAME"
      return 0
    fi
    sleep 1
  done
  printf '%s\n' "$output" >&2
  fail "CORE_CONNECTIVITY_FAILED productLine=$PRODUCT_LINE log=$LOG_DIR/core-probe.log"
}

start_process_stack() {
  local mode="$1" index service port jar main_class java_bin kafka_endpoint
  local -a java_options=(
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
  )
  kafka_endpoint="127.0.0.1:$KAFKA_PORT"
  local -a kafka_options=(
    "KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SPRING_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_INSTRUMENT_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_ACCOUNT_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_PRICE_CONSUMER_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_PRICE_INDEX_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_PRICE_MARK_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_TRADING_MATCHING_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_FUNDING_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_INSURANCE_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
    "SURPRISING_ADL_KAFKA_BOOTSTRAP_SERVERS=$kafka_endpoint"
  )
  java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  for index in "${!PROCESS_SERVICES[@]}"; do
    service="${PROCESS_SERVICES[$index]}"
    service_enabled "$service" || continue
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
    if [[ "$service" == instrument ]]; then
      start_owned_process "$service" "$port" env "${kafka_options[@]}" \
        PRODUCT_LINE="$PRODUCT_LINE" SERVER_PORT="${port:-0}" \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$POSTGRES_DB" SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
        AERON_CLUSTER_HOSTNAMES="$AERON_CLUSTER_HOSTNAMES" \
        AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
        "$java_bin" "${java_options[@]}" -jar "$jar"
      seed_instrument_snapshot
      continue
    fi
    if [[ "$service" == projector ]]; then
      main_class=com.surprising.aeron.exporter.ProjectionMain
      start_owned_process "$service" '' env PRODUCT_LINE="$PRODUCT_LINE" KAFKA_BOOTSTRAP_SERVERS="$kafka_endpoint" \
        DATABASE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$POSTGRES_DB" DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" \
        "$java_bin" "${java_options[@]}" -cp "$jar" "$main_class"
    elif [[ "$service" == exporter ]]; then
      main_class=com.surprising.aeron.exporter.ExporterMain
      start_owned_process "$service" '' env PRODUCT_LINE="$PRODUCT_LINE" \
        KAFKA_BOOTSTRAP_SERVERS="$kafka_endpoint" \
        AERON_HOSTNAMES="$AERON_CLUSTER_HOSTNAMES" AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
        "$java_bin" "${java_options[@]}" -cp "$jar" "$main_class"
    else
      start_owned_process "$service" "$port" env "${kafka_options[@]}" \
        PRODUCT_LINE="$PRODUCT_LINE" SERVER_PORT="${port:-0}" \
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$POSTGRES_DB" SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
        AERON_CLUSTER_HOSTNAMES="$AERON_CLUSTER_HOSTNAMES" \
        AERON_EGRESS_HOSTNAME="$AERON_EGRESS_HOSTNAME" \
        SURPRISING_TRADING_ORDER_RISK_LIMIT_PRICE_PROTECTION_ENABLED="${SURPRISING_TRADING_ORDER_RISK_LIMIT_PRICE_PROTECTION_ENABLED:-false}" \
        "$java_bin" "${java_options[@]}" -jar "$jar"
    fi
  done
}

up_internal() {
  local mode="$1"
  assert_ports_free
  [[ "$mode" == fixture ]] || preflight_real_artifacts
  claim_runtime
  trap 'cleanup_after_failed_up' EXIT ERR INT TERM
  compose up -d postgres kafka
  wait_container postgres; mark_ready postgres
  wait_container kafka; mark_ready kafka
  run_migrations
  create_topics
  if [[ "$mode" != fixture ]]; then
    start_host_core
    verify_host_core
  fi
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
  printf 'TOPIC_PREFIX=surprising.%s\n[topics]\n' "$(topic_segment)"; topic_list
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
  line-up)
    up_internal real
    ;;
  scenario)
    scenario_name="${2:-}"
    case "$scenario_name" in
      w4-six-line|w4-faults)
        W4_RUNNER="$SCRIPT_DIR/run.sh" W4_SCENARIO="$scenario_name" \
          bash "$SCRIPT_DIR/scenarios/w4-six-line.sh"
        ;;
      *) fail "UNKNOWN_SCENARIO scenario=$scenario_name" ;;
    esac
    ;;
  *) fail "USAGE command=$command_name expected=up,line-up,status,run,down,dry-run,smoke,scenario" ;;
esac
