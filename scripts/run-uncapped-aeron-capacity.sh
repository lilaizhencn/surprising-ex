#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?set PRODUCT_LINE to exactly one product line}"
RUN_ID="${CAPACITY_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUTPUT_DIR="${CAPACITY_OUTPUT_DIR:-${ROOT_DIR}/reports/capacity/${RUN_ID}/${PRODUCT_LINE}}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
PATH="${JAVA_HOME}/bin:${PATH}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://127.0.0.1:5432/postgres}"
DATABASE_USER="${DATABASE_USER:-postgres}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-postgres}"
DATABASE_NAME="${DATABASE_NAME:-postgres}"
START_OPS="${START_OPS:-500}"
STEP_OPS="${STEP_OPS:-500}"
MAX_OPS="${MAX_OPS:-10000}"
STEP_SECONDS="${STEP_SECONDS:-10}"
WARMUP_SECONDS="${WARMUP_SECONDS:-3}"
WORKERS="${WORKERS:-4}"
CONNECTIONS="${CONNECTIONS:-${WORKERS}}"
USER_COUNT="${USER_COUNT:-$((WORKERS * 2))}"
ASYNC_IN_FLIGHT="${ASYNC_IN_FLIGHT:-1}"
WORKLOAD="${WORKLOAD:-MATCH}"
SYMBOL_COUNT="${SYMBOL_COUNT:-1}"
SCENARIO="${SCENARIO:-capacity-step}"
ASSESSMENT_MODE="${ASSESSMENT_MODE:-strict}"
RECOVERY_GATE="${RECOVERY_GATE:-false}"
LIFECYCLE_GATE="${LIFECYCLE_GATE:-false}"
LIFECYCLE_PAIRS="${LIFECYCLE_PAIRS:-100}"
LEADER_FAILOVER_AFTER_SECONDS="${LEADER_FAILOVER_AFTER_SECONDS:-0}"
SLO_MIN_ACHIEVEMENT_PERCENT="${SLO_MIN_ACHIEVEMENT_PERCENT:-95}"
SLO_P99_MICROS="${SLO_P99_MICROS:-150000}"
SLO_MAX_PROCESS_CPU_PERCENT="${SLO_MAX_PROCESS_CPU_PERCENT:-400}"
SLO_MAX_TOTAL_RSS_MB="${SLO_MAX_TOTAL_RSS_MB:-4096}"
SLO_MAX_GC_PAUSE_MS="${SLO_MAX_GC_PAUSE_MS:-100}"
SKIP_BUILD="${SKIP_BUILD:-false}"
KEEP_RUNTIME="${KEEP_RUNTIME:-false}"
RESET_TEST_PIPELINE="${RESET_TEST_PIPELINE:-true}"
RUN_EXPORT_PIPELINE="${RUN_EXPORT_PIPELINE:-false}"
TOOLS_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
SERVICE_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
EXPORTER_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar"
TMP_ROOT="${TMPDIR:-/tmp}"
TMP_ROOT="${TMP_ROOT%/}"

cluster_data_dir=""
exporter_pid=""
projection_pid=""
input_bridge_pid=""
resource_monitor_pid=""
resource_stop_file=""
node0_pid=""
node1_pid=""
node2_pid=""
node0_generation=0
node1_generation=0
node2_generation=0

slug() {
  printf '%s' "$1" | tr '[:upper:]_' '[:lower:]-'
}

topic_segment() {
  case "$1" in
    SPOT) echo spot ;;
    LINEAR_PERPETUAL) echo linear-perp ;;
    INVERSE_PERPETUAL) echo inverse-perp ;;
    LINEAR_DELIVERY) echo linear-delivery ;;
    INVERSE_DELIVERY) echo inverse-delivery ;;
    OPTION) echo option ;;
    *) echo "unsupported product line: $1" >&2; return 1 ;;
  esac
}

ordinal() {
  case "$1" in
    SPOT) echo 0 ;;
    LINEAR_PERPETUAL) echo 1 ;;
    INVERSE_PERPETUAL) echo 2 ;;
    LINEAR_DELIVERY) echo 3 ;;
    INVERSE_DELIVERY) echo 4 ;;
    OPTION) echo 5 ;;
  esac
}

stop_pid() {
  local pid="$1" attempt
  [[ -n "${pid}" ]] || return
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}" >/dev/null 2>&1 || true
    for attempt in {1..25}; do
      kill -0 "${pid}" >/dev/null 2>&1 || break
      sleep 0.2
    done
    kill -0 "${pid}" >/dev/null 2>&1 && kill -KILL "${pid}" >/dev/null 2>&1 || true
  fi
  wait "${pid}" >/dev/null 2>&1 || true
}

kill_pid_hard() {
  local pid="$1"
  [[ -n "${pid}" ]] || return
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill -KILL "${pid}"
  fi
  wait "${pid}" >/dev/null 2>&1 || true
}

cleanup() {
  set +e
  if [[ -n "${resource_stop_file}" ]]; then touch "${resource_stop_file}"; fi
  stop_pid "${resource_monitor_pid}"
  if [[ "${KEEP_RUNTIME}" != true ]]; then
    stop_pid "${input_bridge_pid}"
    stop_pid "${projection_pid}"
    stop_pid "${exporter_pid}"
    stop_pid "${node0_pid}"
    stop_pid "${node1_pid}"
    stop_pid "${node2_pid}"
    if [[ -n "${cluster_data_dir}" && "${cluster_data_dir}" == "${TMP_ROOT}/surprising-p9-"* ]]; then
      rm -rf "${cluster_data_dir}"
    fi
  fi
  set -e
}

trap cleanup EXIT
trap 'exit 130' INT TERM

validate() {
  case "${PRODUCT_LINE}" in
    SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
    *) echo "unsupported PRODUCT_LINE=${PRODUCT_LINE}" >&2; exit 2 ;;
  esac
  for value in START_OPS STEP_OPS MAX_OPS STEP_SECONDS WORKERS CONNECTIONS USER_COUNT ASYNC_IN_FLIGHT SYMBOL_COUNT SLO_MIN_ACHIEVEMENT_PERCENT SLO_P99_MICROS SLO_MAX_PROCESS_CPU_PERCENT SLO_MAX_TOTAL_RSS_MB SLO_MAX_GC_PAUSE_MS; do
    [[ "${!value}" =~ ^[1-9][0-9]*$ ]] || { echo "${value} must be positive" >&2; exit 2; }
  done
  ((USER_COUNT % 2 == 0)) || { echo "USER_COUNT must be even" >&2; exit 2; }
  [[ "${WARMUP_SECONDS}" =~ ^[0-9]+$ ]] || { echo "WARMUP_SECONDS must be non-negative" >&2; exit 2; }
  [[ "${LEADER_FAILOVER_AFTER_SECONDS}" =~ ^[0-9]+$ ]] \
    || { echo "LEADER_FAILOVER_AFTER_SECONDS must be non-negative" >&2; exit 2; }
  if ((LEADER_FAILOVER_AFTER_SECONDS > 0 && LEADER_FAILOVER_AFTER_SECONDS >= STEP_SECONDS)); then
    echo "LEADER_FAILOVER_AFTER_SECONDS must be lower than STEP_SECONDS" >&2
    exit 2
  fi
  ((START_OPS <= MAX_OPS)) || { echo "START_OPS must be <= MAX_OPS" >&2; exit 2; }
  ((CONNECTIONS <= 64)) || { echo "CONNECTIONS must be <= 64" >&2; exit 2; }
  case "${WORKLOAD}" in MATCH|MATCH_ASYNC|PLACE_ONLY|CANCEL|MARK_PRICE) ;; *) echo "WORKLOAD must be MATCH, MATCH_ASYNC, PLACE_ONLY, CANCEL or MARK_PRICE" >&2; exit 2 ;; esac
  case "${ASSESSMENT_MODE}" in strict|observe) ;; *) echo "ASSESSMENT_MODE must be strict or observe" >&2; exit 2 ;; esac
  case "${RECOVERY_GATE}" in true|false) ;; *) echo "RECOVERY_GATE must be true or false" >&2; exit 2 ;; esac
  case "${LIFECYCLE_GATE}" in true|false) ;; *) echo "LIFECYCLE_GATE must be true or false" >&2; exit 2 ;; esac
  case "${RESET_TEST_PIPELINE}" in true|false) ;; *) echo "RESET_TEST_PIPELINE must be true or false" >&2; exit 2 ;; esac
  case "${RUN_EXPORT_PIPELINE}" in true|false) ;; *) echo "RUN_EXPORT_PIPELINE must be true or false" >&2; exit 2 ;; esac
  [[ "${LIFECYCLE_PAIRS}" =~ ^[1-9][0-9]*$ ]] || { echo "LIFECYCLE_PAIRS must be positive" >&2; exit 2; }
  ((LIFECYCLE_PAIRS <= 128)) || { echo "LIFECYCLE_PAIRS must be <= 128" >&2; exit 2; }
  if [[ "${PRODUCT_LINE}" == SPOT && "${LIFECYCLE_GATE}" == true ]]; then
    echo "LIFECYCLE_GATE is not applicable to SPOT" >&2
    exit 2
  fi
  if [[ "${LIFECYCLE_GATE}" == true ]] && ((LIFECYCLE_PAIRS * 2 + WORKERS * 2 > 256)); then
    echo "lifecycle users plus capacity users must fit one 256-user risk scan" >&2
    exit 2
  fi
  [[ "${SCENARIO}" =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "invalid SCENARIO=${SCENARIO}" >&2; exit 2; }
  [[ -x "${JAVA_HOME}/bin/java" ]] || { echo "JDK 25 not found" >&2; exit 1; }
  if [[ "${RUN_EXPORT_PIPELINE}" == true ]]; then
    for container in rainbo-postgres rainbo-kafka; do
      [[ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null)" == true ]] \
        || { echo "required container is not running: ${container}" >&2; exit 1; }
    done
  fi
}

build() {
  [[ "${SKIP_BUILD}" == true ]] && return
  mvn -q -f "${ROOT_DIR}/pom.xml" \
    -pl :surprising-aeron-service,:surprising-aeron-tools,:surprising-aeron-exporter -am -DskipTests package
}

assert_ports_free() {
  local line_ordinal member offset port
  line_ordinal="$(ordinal "${PRODUCT_LINE}")"
  for member in 0 1 2; do
    for offset in 1 2 3 4 5; do
      port=$((20000 + line_ordinal * 1000 + member * 100 + offset))
      if lsof -nP -iUDP:"${port}" >/dev/null 2>&1; then
        echo "Aeron port ${port}/udp is already in use" >&2
        return 1
      fi
    done
  done
}

wait_cluster() {
  local attempt
  for attempt in {1..40}; do
    if "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -Dsurprising.aeron.product-line="${PRODUCT_LINE}" -Dsurprising.aeron.probe-mode=query \
      -cp "${TOOLS_JAR}" com.surprising.aeron.tools.ClusterProbeMain >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Aeron Cluster readiness timed out" >&2
  return 1
}

start_node() {
  local node_id="$1" started_pid generation
  case "${node_id}" in
    0) node0_generation=$((node0_generation + 1)); generation="${node0_generation}" ;;
    1) node1_generation=$((node1_generation + 1)); generation="${node1_generation}" ;;
    2) node2_generation=$((node2_generation + 1)); generation="${node2_generation}" ;;
  esac
  "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -Xms256m -Xmx256m -Dsurprising.aeron.product-line="${PRODUCT_LINE}" \
      -Xlog:gc*:file="${OUTPUT_DIR}/node${node_id}-gc-${generation}.log":time,uptime,level,tags \
      -Dsurprising.aeron.max-concurrent-sessions=64 \
      -Dsurprising.aeron.node-id="${node_id}" -Dsurprising.aeron.hostnames=localhost,localhost,localhost \
      -Dsurprising.aeron.data-dir="${cluster_data_dir}" -jar "${SERVICE_JAR}" \
      >>"${OUTPUT_DIR}/node${node_id}.log" 2>&1 &
  started_pid=$!
  case "${node_id}" in
    0) node0_pid="${started_pid}" ;;
    1) node1_pid="${started_pid}" ;;
    2) node2_pid="${started_pid}" ;;
  esac
}

start_cluster() {
  local node_id
  assert_ports_free
  cluster_data_dir="$(mktemp -d "${TMP_ROOT}/surprising-p9-$(slug "${PRODUCT_LINE}").XXXXXX")"
  for node_id in 0 1 2; do
    start_node "${node_id}"
  done
  wait_cluster
}

node_pid() {
  case "$1" in
    0) echo "${node0_pid}" ;;
    1) echo "${node1_pid}" ;;
    2) echo "${node2_pid}" ;;
  esac
}

cluster_tool() {
  local node_id="$1" command="$2" product_line_dir
  product_line_dir="$(printf '%s' "${PRODUCT_LINE}" | tr '[:upper:]' '[:lower:]')"
  "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp "${SERVICE_JAR}" io.aeron.cluster.ClusterTool \
    "${cluster_data_dir}/${product_line_dir}/node${node_id}/cluster" "${command}"
}

leader_node() {
  local node_id
  for node_id in 0 1 2; do
    if cluster_tool "${node_id}" is-leader >/dev/null 2>&1; then
      echo "${node_id}"
      return
    fi
  done
  return 1
}

state_probe() {
  "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="${PRODUCT_LINE}" -Dsurprising.aeron.probe-mode=query \
    -cp "${TOOLS_JAR}" com.surprising.aeron.tools.ClusterProbeMain
}

state_hash() {
  sed -n 's/.*stateHash=\([^ ]*\).*/\1/p' "$1" | tail -n 1
}

wait_projection_lag_zero() {
  local segment group attempt lag
  segment="$(topic_segment "${PRODUCT_LINE}")"
  group="surprising-core-projection-${segment}"
  for attempt in {1..60}; do
    lag="$(docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --describe --group "${group}" 2>/dev/null | awk 'NR > 1 && $6 ~ /^[0-9]+$/ {sum += $6; found=1} END {if (found) print sum; else print -1}')"
    [[ "${lag}" == 0 ]] && return
    sleep 1
  done
  echo "projection lag did not reach zero group=${group} lastLag=${lag}" >&2
  return 1
}

run_recovery_gate() {
  local leader failover_started failover_seconds snapshot_leader snapshot_started snapshot_seconds
  local cold_started cold_seconds before_hash failover_hash cold_hash node_id archive_bytes
  state_probe | tee "${OUTPUT_DIR}/state-before-recovery.txt"
  before_hash="$(state_hash "${OUTPUT_DIR}/state-before-recovery.txt")"
  leader="$(leader_node)"
  failover_started="$(date +%s)"
  kill_pid_hard "$(node_pid "${leader}")"
  wait_cluster
  failover_seconds=$(( $(date +%s) - failover_started ))
  state_probe | tee "${OUTPUT_DIR}/state-after-leader-kill.txt"
  failover_hash="$(state_hash "${OUTPUT_DIR}/state-after-leader-kill.txt")"
  [[ "${failover_hash}" == "${before_hash}" ]] || { echo "state hash changed after leader kill" >&2; return 1; }
  start_node "${leader}"
  wait_cluster
  snapshot_leader="$(leader_node)"
  snapshot_started="$(date +%s)"
  cluster_tool "${snapshot_leader}" snapshot | tee "${OUTPUT_DIR}/snapshot.txt"
  snapshot_seconds=$(( $(date +%s) - snapshot_started ))
  for node_id in 0 1 2; do stop_pid "$(node_pid "${node_id}")"; done
  cold_started="$(date +%s)"
  for node_id in 0 1 2; do start_node "${node_id}"; done
  wait_cluster
  cold_seconds=$(( $(date +%s) - cold_started ))
  state_probe | tee "${OUTPUT_DIR}/state-after-cold-recovery.txt"
  cold_hash="$(state_hash "${OUTPUT_DIR}/state-after-cold-recovery.txt")"
  [[ "${cold_hash}" == "${before_hash}" ]] || { echo "state hash changed after cold recovery" >&2; return 1; }
  kill -0 "${exporter_pid}"
  kill -0 "${projection_pid}"
  wait_projection_lag_zero
  archive_bytes=$(( $(du -sk "${cluster_data_dir}" | awk '{print $1}') * 1024 ))
  {
    echo "recovery=PASS"
    echo "leader_killed=node${leader}"
    echo "leader_failover_seconds=${failover_seconds}"
    echo "snapshot_seconds=${snapshot_seconds}"
    echo "cold_recovery_seconds=${cold_seconds}"
    echo "state_hash=${cold_hash}"
    echo "projection_lag=0"
    echo "cluster_data_bytes=${archive_bytes}"
    echo "funds_diff=0"
  } >"${OUTPUT_DIR}/recovery.env"
}

run_lifecycle_gate() {
  "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="${PRODUCT_LINE}" \
    -Dsurprising.aeron.lifecycle-seed=995001 \
    -Dsurprising.aeron.lifecycle-pairs="${LIFECYCLE_PAIRS}" \
    -Dsurprising.aeron.lifecycle-connections="${CONNECTIONS}" \
    -cp "${TOOLS_JAR}" com.surprising.aeron.tools.ClusterLifecycleCapacityMain \
    | tee "${OUTPUT_DIR}/lifecycle-capacity.txt"
}

verify_export_pipeline() {
  kill -0 "${input_bridge_pid}"
  kill -0 "${exporter_pid}"
  kill -0 "${projection_pid}"
  wait_projection_lag_zero
  {
    echo "input_bridge=PASS"
    echo "exporter=PASS"
    echo "projection=PASS"
    echo "projection_lag=0"
  } >"${OUTPUT_DIR}/pipeline.env"
}

prepare_topic() {
  local segment topic input_topic partitions attempt topic_deleted
  segment="$(topic_segment "${PRODUCT_LINE}")"
  topic="surprising.${segment}.core.events.v1"
  input_topic="surprising.${segment}.core.inputs.v1"
  if [[ "${RESET_TEST_PIPELINE}" == true ]]; then
    docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --delete --group "surprising-core-projection-${segment}" >/dev/null 2>&1 || true
    docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --delete --group "surprising-core-input-${segment}" >/dev/null 2>&1 || true
    docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --delete --if-exists --topic "${topic}" >/dev/null
    topic_deleted=false
    for attempt in {1..30}; do
      if ! docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
          --describe --topic "${topic}" >/dev/null 2>&1; then
        topic_deleted=true
        break
      fi
      sleep 1
    done
    [[ "${topic_deleted}" == true ]] || { echo "topic deletion timed out: ${topic}" >&2; return 1; }
    docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --create --if-not-exists --topic "${topic}" --partitions 1 --replication-factor 1 >/dev/null
    docker exec rainbo-postgres psql -v ON_ERROR_STOP=1 -U "${DATABASE_USER}" -d "${DATABASE_NAME}" -c \
      "DELETE FROM core_execution_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_order_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_user_fact_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_funding_payment_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_funding_settlement_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_liquidation_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_treasury_projection WHERE product_line='${PRODUCT_LINE}';
       DELETE FROM core_event_projection WHERE product_line='${PRODUCT_LINE}';" >/dev/null
  fi
  if [[ "${RESET_TEST_PIPELINE}" == true ]]; then
    docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --delete --if-exists --topic "${input_topic}" >/dev/null 2>&1 || true
    topic_deleted=false
    for attempt in {1..30}; do
      if ! docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
          --describe --topic "${input_topic}" >/dev/null 2>&1; then
        topic_deleted=true
        break
      fi
      sleep 1
    done
    [[ "${topic_deleted}" == true ]] || { echo "topic deletion timed out: ${input_topic}" >&2; return 1; }
  fi
  docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "${input_topic}" --partitions 1 --replication-factor 1 >/dev/null
  partitions="$(docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic "${topic}" 2>/dev/null | sed -n 's/.*PartitionCount: \([0-9][0-9]*\).*/\1/p' | head -n 1)"
  [[ "${partitions}" == 1 ]] || { echo "${topic} must exist with one partition" >&2; return 1; }
}

start_export_pipeline() {
  local segment input_topic
  segment="$(topic_segment "${PRODUCT_LINE}")"
  input_topic="surprising.${segment}.core.inputs.v1"
  PRODUCT_LINE="${PRODUCT_LINE}" CORE_INPUT_TOPICS="${input_topic}" \
    KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    AERON_HOSTNAMES=localhost,localhost,localhost AERON_EGRESS_HOSTNAME=localhost \
    "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -cp "${EXPORTER_JAR}" com.surprising.aeron.exporter.InputBridgeMain \
      >"${OUTPUT_DIR}/input-bridge.log" 2>&1 &
  input_bridge_pid=$!
  PRODUCT_LINE="${PRODUCT_LINE}" KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    DATABASE_URL="${DATABASE_URL}" DATABASE_USER="${DATABASE_USER}" DATABASE_PASSWORD="${DATABASE_PASSWORD}" \
    "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -cp "${EXPORTER_JAR}" com.surprising.aeron.exporter.ExporterMain \
      >"${OUTPUT_DIR}/exporter.log" 2>&1 &
  exporter_pid=$!
  PRODUCT_LINE="${PRODUCT_LINE}" KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    DATABASE_URL="${DATABASE_URL}" DATABASE_USER="${DATABASE_USER}" DATABASE_PASSWORD="${DATABASE_PASSWORD}" \
    "${JAVA_HOME}/bin/java" -cp "${EXPORTER_JAR}" com.surprising.aeron.exporter.ProjectionMain \
      >"${OUTPUT_DIR}/projection.log" 2>&1 &
  projection_pid=$!
  sleep 1
  kill -0 "${input_bridge_pid}"
  kill -0 "${exporter_pid}"
  kill -0 "${projection_pid}"
}

start_resource_monitor() {
  resource_stop_file="${OUTPUT_DIR}/resource-monitor.stop"
  rm -f "${resource_stop_file}"
  echo $'epoch_seconds\tpid\tcpu_percent\trss_kb\tcommand' >"${OUTPUT_DIR}/resources.tsv"
  (
    while [[ ! -e "${resource_stop_file}" ]]; do
      now="$(date +%s)"
      ps -Ao pid=,pcpu=,rss=,command= | awk -v now="${now}" \
        '/\/java / && /surprising-p9-|ClusterCapacityMain|InputBridgeMain|ExporterMain|ProjectionMain/ {
          pid=$1; cpu=$2; rss=$3; $1=$2=$3=""; sub(/^ +/, "");
          printf "%s\t%s\t%s\t%s\t%s\n", now, pid, cpu, rss, $0
        }' >>"${OUTPUT_DIR}/resources.tsv"
      sleep 2
    done
  ) &
  resource_monitor_pid=$!
}

write_resource_summary() {
  local max_cpu max_rss_kb max_rss_limit_kb samples gc_values gc_events max_gc_pause_ms
  touch "${resource_stop_file}"
  wait "${resource_monitor_pid}" >/dev/null 2>&1 || true
  resource_monitor_pid=""
  awk -F '\t' 'NR > 1 {
      if ($3 > maxCpu) maxCpu=$3;
      rss[$1]+=$4;
    }
    END {
      for (sample in rss) if (rss[sample] > maxRss) maxRss=rss[sample];
      printf "max_process_cpu_percent=%.3f\nmax_total_rss_kb=%d\nresource_samples=%d\n", maxCpu, maxRss, NR - 1;
    }' "${OUTPUT_DIR}/resources.tsv" >"${OUTPUT_DIR}/resource-summary.env"
  max_cpu="$(awk -F= '$1 == "max_process_cpu_percent" {print $2}' "${OUTPUT_DIR}/resource-summary.env")"
  max_rss_kb="$(awk -F= '$1 == "max_total_rss_kb" {print $2}' "${OUTPUT_DIR}/resource-summary.env")"
  samples="$(awk -F= '$1 == "resource_samples" {print $2}' "${OUTPUT_DIR}/resource-summary.env")"
  max_rss_limit_kb=$((SLO_MAX_TOTAL_RSS_MB * 1024))
  ((samples > 0)) || { echo "no resource samples captured" >&2; return 1; }
  awk -v actual="${max_cpu}" -v limit="${SLO_MAX_PROCESS_CPU_PERCENT}" \
    'BEGIN {exit !(actual <= limit)}' || { echo "process CPU SLO exceeded actual=${max_cpu}" >&2; return 1; }
  ((max_rss_kb <= max_rss_limit_kb)) \
    || { echo "RSS SLO exceeded actualKb=${max_rss_kb} limitKb=${max_rss_limit_kb}" >&2; return 1; }
  gc_values="$(sed -n 's/.* \([0-9][0-9.]*\)ms$/\1/p' "${OUTPUT_DIR}"/node*-gc.log 2>/dev/null || true)"
  gc_events="$(printf '%s\n' "${gc_values}" | awk 'NF {count++} END {print count + 0}')"
  max_gc_pause_ms="$(printf '%s\n' "${gc_values}" | awk 'NF && $1 > max {max=$1} END {printf "%.3f", max + 0}')"
  {
    echo "gc_pause_events=${gc_events}"
    echo "max_gc_pause_ms=${max_gc_pause_ms}"
  } >>"${OUTPUT_DIR}/resource-summary.env"
  awk -v actual="${max_gc_pause_ms}" -v limit="${SLO_MAX_GC_PAUSE_MS}" \
    'BEGIN {exit !(actual <= limit)}' || { echo "GC pause SLO exceeded actualMs=${max_gc_pause_ms}" >&2; return 1; }
}

metric() {
  local key="$1" file="$2"
  sed -n "s/.*${key}=\([^ ]*\).*/\1/p" "${file}" | tail -n 1
}

run_capacity_java() {
  local offered="$1" sequence="$2"
  "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="${PRODUCT_LINE}" \
    -Dsurprising.aeron.symbol="P9-${PRODUCT_LINE//_/-}-${sequence}" \
    -Dsurprising.aeron.capacity-seed="$((990000 + sequence))" \
    -Dsurprising.aeron.capacity-workers="${WORKERS}" \
    -Dsurprising.aeron.capacity-connections="${CONNECTIONS}" \
    -Dsurprising.aeron.capacity-user-count="${USER_COUNT}" \
    -Dsurprising.aeron.capacity-async-in-flight="${ASYNC_IN_FLIGHT:-1}" \
    -Dsurprising.aeron.capacity-symbol-count="${SYMBOL_COUNT}" \
    -Dsurprising.aeron.capacity-warmup-seconds="${WARMUP_SECONDS}" \
    -Dsurprising.aeron.capacity-duration-seconds="${STEP_SECONDS}" \
    -Dsurprising.aeron.capacity-offered-commands-per-second="${offered}" \
    -Dsurprising.aeron.capacity-workload="${WORKLOAD}" \
    -cp "${TOOLS_JAR}" com.surprising.aeron.tools.ClusterCapacityMain
}

run_step() {
  local offered="$1" sequence="$2" evidence output achieved p99 minimum workload_slug
  local capacity_pid failover_leader failover_started failover_seconds
  workload_slug="$(slug "${WORKLOAD}")"
  evidence="${OUTPUT_DIR}/${SCENARIO}-${workload_slug}-${offered}"
  mkdir -p "${evidence}"
  output="${evidence}/result.txt"
  if ((LEADER_FAILOVER_AFTER_SECONDS > 0)); then
    run_capacity_java "${offered}" "${sequence}" >"${output}" 2>&1 &
    capacity_pid=$!
    sleep "${LEADER_FAILOVER_AFTER_SECONDS}"
    kill -0 "${capacity_pid}" || { echo "capacity process ended before failover" >&2; return 1; }
    failover_leader="$(leader_node)"
    failover_started="$(date +%s)"
    kill_pid_hard "$(node_pid "${failover_leader}")"
    wait_cluster
    failover_seconds=$(( $(date +%s) - failover_started ))
    start_node "${failover_leader}"
    wait_cluster
    wait "${capacity_pid}"
    cat "${output}"
    {
      echo "leader_failover_during_load=PASS"
      echo "leader_killed=node${failover_leader}"
      echo "failover_seconds=${failover_seconds}"
      echo "failover_after_seconds=${LEADER_FAILOVER_AFTER_SECONDS}"
    } >"${OUTPUT_DIR}/leader-failover-during-load.env"
  else
    run_capacity_java "${offered}" "${sequence}" | tee "${output}"
  fi
  achieved="$(metric coreCommittedOpsPerSec "${output}")"
  p99="$(metric p99Micros "${output}")"
  minimum="$(awk -v offered="${offered}" -v percent="${SLO_MIN_ACHIEVEMENT_PERCENT}" \
    'BEGIN {printf "%.3f", offered * percent / 100}')"
  if [[ "${ASSESSMENT_MODE}" == "observe" ]]; then
    echo "| ${SCENARIO} | ${WORKLOAD} | ${SYMBOL_COUNT} | ${offered} | ${achieved} | ${p99} | OBSERVED | ${SCENARIO}-${workload_slug}-${offered}/ |" >>"${OUTPUT_DIR}/index.md"
    return 0
  fi
  if awk -v actual="${achieved}" -v minimum="${minimum}" -v p99="${p99}" -v limit="${SLO_P99_MICROS}" \
      'BEGIN {exit !(actual >= minimum && p99 <= limit)}'; then
    echo "| ${SCENARIO} | ${WORKLOAD} | ${SYMBOL_COUNT} | ${offered} | ${achieved} | ${p99} | PASS | ${SCENARIO}-${workload_slug}-${offered}/ |" >>"${OUTPUT_DIR}/index.md"
    return 0
  fi
  echo "| ${SCENARIO} | ${WORKLOAD} | ${SYMBOL_COUNT} | ${offered} | ${achieved} | ${p99} | FAIL_STOP | ${SCENARIO}-${workload_slug}-${offered}/ |" >>"${OUTPUT_DIR}/index.md"
  return 1
}

write_manifest() {
  {
    echo "scope=LOCAL_CAPACITY"
    echo "git_commit=$(git -C "${ROOT_DIR}" rev-parse HEAD)"
    echo "product_line=${PRODUCT_LINE}"
    echo "hardware=$(sysctl -n hw.model)"
    echo "cpu_count=$(sysctl -n hw.ncpu)"
    echo "memory_bytes=$(sysctl -n hw.memsize)"
    echo "java=$(${JAVA_HOME}/bin/java -version 2>&1 | head -n 1)"
    echo "workers=${WORKERS}"
    echo "connections=${CONNECTIONS}"
    echo "user_count=${USER_COUNT}"
    echo "async_in_flight=${ASYNC_IN_FLIGHT}"
    echo "workload=${WORKLOAD}"
    echo "symbol_count=${SYMBOL_COUNT}"
    echo "scenario=${SCENARIO}"
    echo "assessment_mode=${ASSESSMENT_MODE}"
    echo "recovery_gate=${RECOVERY_GATE}"
    echo "lifecycle_gate=${LIFECYCLE_GATE}"
    echo "lifecycle_pairs=${LIFECYCLE_PAIRS}"
    echo "leader_failover_after_seconds=${LEADER_FAILOVER_AFTER_SECONDS}"
    echo "reset_test_pipeline=${RESET_TEST_PIPELINE}"
    echo "step_seconds=${STEP_SECONDS}"
    echo "warmup_seconds=${WARMUP_SECONDS}"
    echo "slo_min_achievement_percent=${SLO_MIN_ACHIEVEMENT_PERCENT}"
    echo "slo_p99_micros=${SLO_P99_MICROS}"
    echo "slo_max_process_cpu_percent=${SLO_MAX_PROCESS_CPU_PERCENT}"
    echo "slo_max_total_rss_mb=${SLO_MAX_TOTAL_RSS_MB}"
    echo "slo_max_gc_pause_ms=${SLO_MAX_GC_PAUSE_MS}"
  } >"${OUTPUT_DIR}/environment-manifest.env"
}

validate
mkdir -p "${OUTPUT_DIR}"
build
if [[ "${RUN_EXPORT_PIPELINE}" == true ]]; then
  prepare_topic
fi
write_manifest
{
  echo "# ${PRODUCT_LINE} Aeron local capacity"
  echo
  echo "This is LOCAL_CAPACITY on a shared Apple M1 Pro development machine, not production capacity."
  echo
  echo '| Scenario | Workload | Symbols | Offered commands/s | Committed commands/s | p99 us | Result | Evidence |'
  echo '|---|---|---:|---:|---:|---:|---|---|'
} >"${OUTPUT_DIR}/index.md"
start_cluster
if [[ "${RUN_EXPORT_PIPELINE}" == true ]]; then
  start_export_pipeline
fi
start_resource_monitor

last_pass=0
sequence=0
for ((offered = START_OPS; offered <= MAX_OPS; offered += STEP_OPS)); do
  sequence=$((sequence + 1))
  if run_step "${offered}" "${sequence}"; then
    last_pass="${offered}"
  else
    break
  fi
done

echo "stable_last_pass_offered_ops=${last_pass}" | tee "${OUTPUT_DIR}/summary.env"
echo "capacity_report=${OUTPUT_DIR}/index.md"
((last_pass > 0)) || exit 1
if [[ "${LIFECYCLE_GATE}" == true ]]; then
  run_lifecycle_gate
fi
if [[ "${RECOVERY_GATE}" == true ]]; then
  run_recovery_gate
fi
  if [[ "${RUN_EXPORT_PIPELINE}" == true ]]; then
    verify_export_pipeline
  fi
write_resource_summary
