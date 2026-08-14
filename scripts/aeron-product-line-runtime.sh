#!/usr/bin/env bash

if [[ -n "${AERON_PRODUCT_LINE_RUNTIME_LOADED:-}" ]]; then
  return 0
fi
AERON_PRODUCT_LINE_RUNTIME_LOADED=true

AERON_RUNTIME_ROOT_DIR="${AERON_RUNTIME_ROOT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
AERON_RUNTIME_JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
AERON_RUNTIME_SERVICE_JAR="${AERON_RUNTIME_ROOT_DIR}/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
AERON_RUNTIME_TOOLS_JAR="${AERON_RUNTIME_ROOT_DIR}/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
AERON_RUNTIME_EXPORTER_JAR="${AERON_RUNTIME_ROOT_DIR}/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar"
AERON_RUNTIME_KAFKA="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
AERON_RUNTIME_DATABASE_URL="${DATABASE_URL:-${SPRING_DATASOURCE_URL:-jdbc:postgresql://127.0.0.1:5432/postgres}}"
AERON_RUNTIME_DATABASE_USER="${DATABASE_USER:-${DB_USER:-postgres}}"
AERON_RUNTIME_DATABASE_PASSWORD="${DATABASE_PASSWORD:-${DB_PASSWORD:-postgres}}"
AERON_RUNTIME_DATABASE_NAME="${DATABASE_NAME:-${DB_NAME:-postgres}}"
AERON_RUNTIME_KEEP="${KEEP_AERON_RUNTIME:-false}"
AERON_RUNTIME_PRODUCT_LINE=""
AERON_RUNTIME_SEGMENT=""
AERON_RUNTIME_EVIDENCE_DIR=""
AERON_RUNTIME_DATA_DIR=""
AERON_RUNTIME_NODE_PIDS=()
AERON_RUNTIME_INPUT_BRIDGE_PID=""
AERON_RUNTIME_EXPORTER_PID=""
AERON_RUNTIME_PROJECTION_PID=""

aeron_runtime_segment() {
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

aeron_runtime_ordinal() {
  case "$1" in
    SPOT) echo 0 ;;
    LINEAR_PERPETUAL) echo 1 ;;
    INVERSE_PERPETUAL) echo 2 ;;
    LINEAR_DELIVERY) echo 3 ;;
    INVERSE_DELIVERY) echo 4 ;;
    OPTION) echo 5 ;;
  esac
}

aeron_runtime_stop_pid() {
  local pid="$1" attempt
  [[ -n "${pid}" ]] || return 0
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill -TERM "${pid}" >/dev/null 2>&1 || true
    for attempt in {1..25}; do
      kill -0 "${pid}" >/dev/null 2>&1 || break
      sleep 0.2
    done
    kill -0 "${pid}" >/dev/null 2>&1 && kill -KILL "${pid}" >/dev/null 2>&1 || true
  fi
  wait "${pid}" >/dev/null 2>&1 || true
}

aeron_runtime_assert_artifacts() {
  local artifact
  [[ -x "${AERON_RUNTIME_JAVA_HOME}/bin/java" ]] || {
    echo "JDK 25 not found: ${AERON_RUNTIME_JAVA_HOME}" >&2
    return 1
  }
  for artifact in "${AERON_RUNTIME_SERVICE_JAR}" "${AERON_RUNTIME_TOOLS_JAR}" \
      "${AERON_RUNTIME_EXPORTER_JAR}"; do
    [[ -s "${artifact}" ]] || { echo "missing Aeron runtime artifact: ${artifact}" >&2; return 1; }
  done
  if pgrep -f 'com\.surprising\.aeron\.exporter\.(InputBridgeMain|ExporterMain|ProjectionMain)' \
      >/dev/null 2>&1; then
    echo "stale Aeron pipeline process exists; stop it before starting a single-product runtime" >&2
    return 1
  fi
}

aeron_runtime_assert_ports_free() {
  local ordinal member offset port
  ordinal="$(aeron_runtime_ordinal "${AERON_RUNTIME_PRODUCT_LINE}")"
  for member in 0 1 2; do
    for offset in 1 2 3 4 5; do
      port=$((20000 + ordinal * 1000 + member * 100 + offset))
      if lsof -nP -iUDP:"${port}" >/dev/null 2>&1; then
        echo "Aeron port ${port}/udp is already in use for ${AERON_RUNTIME_PRODUCT_LINE}" >&2
        return 1
      fi
    done
  done
}

aeron_runtime_probe() {
  "${AERON_RUNTIME_JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="${AERON_RUNTIME_PRODUCT_LINE}" \
    -Dsurprising.aeron.probe-mode=query -cp "${AERON_RUNTIME_TOOLS_JAR}" \
    com.surprising.aeron.tools.ClusterProbeMain
}

aeron_runtime_wait_cluster() {
  local attempt
  for attempt in {1..40}; do
    if aeron_runtime_probe >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Aeron Cluster readiness timed out for ${AERON_RUNTIME_PRODUCT_LINE}" >&2
  return 1
}

aeron_runtime_start_cluster() {
  local member slug
  aeron_runtime_assert_ports_free
  slug="$(printf '%s' "${AERON_RUNTIME_PRODUCT_LINE}" | tr '[:upper:]_' '[:lower:]-')"
  AERON_RUNTIME_DATA_DIR="$(mktemp -d "${TMPDIR:-/tmp}/surprising-aeron-runtime-${slug}.XXXXXX")"
  mkdir -p "${AERON_RUNTIME_EVIDENCE_DIR}/aeron-cluster"
  for member in 0 1 2; do
    "${AERON_RUNTIME_JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -Xms256m -Xmx256m -Dsurprising.aeron.product-line="${AERON_RUNTIME_PRODUCT_LINE}" \
      -Dsurprising.aeron.max-concurrent-sessions=64 -Dsurprising.aeron.node-id="${member}" \
      -Dsurprising.aeron.hostnames=localhost,localhost,localhost \
      -Dsurprising.aeron.data-dir="${AERON_RUNTIME_DATA_DIR}" -jar "${AERON_RUNTIME_SERVICE_JAR}" \
      >"${AERON_RUNTIME_EVIDENCE_DIR}/aeron-cluster/node${member}.log" 2>&1 &
    AERON_RUNTIME_NODE_PIDS+=("$!")
  done
  aeron_runtime_wait_cluster
}

aeron_runtime_prepare_pipeline() {
  local input_topic export_topic group topic attempt deleted
  input_topic="surprising.${AERON_RUNTIME_SEGMENT}.core.inputs.v1"
  export_topic="surprising.${AERON_RUNTIME_SEGMENT}.core.events.v1"
  for topic in "${input_topic}" "${export_topic}"; do
    docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --delete --if-exists --topic "${topic}" >/dev/null 2>&1 || true
    deleted=false
    for attempt in {1..30}; do
      if ! docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
          --describe --topic "${topic}" >/dev/null 2>&1; then
        deleted=true
        break
      fi
      sleep 1
    done
    [[ "${deleted}" == true ]] || { echo "Kafka topic deletion timed out: ${topic}" >&2; return 1; }
    docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
      --create --if-not-exists --topic "${topic}" --partitions 1 --replication-factor 1 >/dev/null
  done
  for group in "surprising-core-input-${AERON_RUNTIME_SEGMENT}" \
      "surprising-core-projection-${AERON_RUNTIME_SEGMENT}"; do
    docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
      --delete --group "${group}" >/dev/null 2>&1 || true
  done
}

aeron_runtime_start_pipeline() {
  local common_env input_topic
  input_topic="surprising.${AERON_RUNTIME_SEGMENT}.core.inputs.v1"
  mkdir -p "${AERON_RUNTIME_EVIDENCE_DIR}/aeron-pipeline"
  PRODUCT_LINE="${AERON_RUNTIME_PRODUCT_LINE}" CORE_INPUT_TOPICS="${input_topic}" \
    KAFKA_BOOTSTRAP_SERVERS="${AERON_RUNTIME_KAFKA}" AERON_HOSTNAMES=localhost,localhost,localhost \
    AERON_EGRESS_HOSTNAME=localhost "${AERON_RUNTIME_JAVA_HOME}/bin/java" \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -cp "${AERON_RUNTIME_EXPORTER_JAR}" \
    com.surprising.aeron.exporter.InputBridgeMain \
    >"${AERON_RUNTIME_EVIDENCE_DIR}/aeron-pipeline/input-bridge.log" 2>&1 &
  AERON_RUNTIME_INPUT_BRIDGE_PID=$!
  PRODUCT_LINE="${AERON_RUNTIME_PRODUCT_LINE}" KAFKA_BOOTSTRAP_SERVERS="${AERON_RUNTIME_KAFKA}" \
    AERON_HOSTNAMES=localhost,localhost,localhost AERON_EGRESS_HOSTNAME=localhost \
    "${AERON_RUNTIME_JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp "${AERON_RUNTIME_EXPORTER_JAR}" com.surprising.aeron.exporter.ExporterMain \
    >"${AERON_RUNTIME_EVIDENCE_DIR}/aeron-pipeline/exporter.log" 2>&1 &
  AERON_RUNTIME_EXPORTER_PID=$!
  PRODUCT_LINE="${AERON_RUNTIME_PRODUCT_LINE}" KAFKA_BOOTSTRAP_SERVERS="${AERON_RUNTIME_KAFKA}" \
    DATABASE_URL="${AERON_RUNTIME_DATABASE_URL}" DATABASE_USER="${AERON_RUNTIME_DATABASE_USER}" \
    DATABASE_PASSWORD="${AERON_RUNTIME_DATABASE_PASSWORD}" "${AERON_RUNTIME_JAVA_HOME}/bin/java" \
    -cp "${AERON_RUNTIME_EXPORTER_JAR}" com.surprising.aeron.exporter.ProjectionMain \
    >"${AERON_RUNTIME_EVIDENCE_DIR}/aeron-pipeline/projection.log" 2>&1 &
  AERON_RUNTIME_PROJECTION_PID=$!
  sleep 2
  kill -0 "${AERON_RUNTIME_INPUT_BRIDGE_PID}"
  kill -0 "${AERON_RUNTIME_EXPORTER_PID}"
  kill -0 "${AERON_RUNTIME_PROJECTION_PID}"
}

aeron_runtime_seed_instruments() {
  PRODUCT_LINE="${AERON_RUNTIME_PRODUCT_LINE}" DATABASE_URL="${AERON_RUNTIME_DATABASE_URL}" \
    DATABASE_USER="${AERON_RUNTIME_DATABASE_USER}" DATABASE_PASSWORD="${AERON_RUNTIME_DATABASE_PASSWORD}" \
    AERON_HOSTNAMES=localhost,localhost,localhost AERON_EGRESS_HOSTNAME=localhost \
    "${AERON_RUNTIME_JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp "${AERON_RUNTIME_TOOLS_JAR}" com.surprising.aeron.tools.ClusterInstrumentSeedMain \
    | tee "${AERON_RUNTIME_EVIDENCE_DIR}/aeron-pipeline/instrument-seed.txt"
}

aeron_runtime_wait_projection_lag_zero() {
  local group attempt lag
  group="surprising-core-projection-${AERON_RUNTIME_SEGMENT}"
  for attempt in {1..120}; do
    lag="$(docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 --describe --group "${group}" 2>/dev/null \
      | awk 'NR > 1 && $6 ~ /^[0-9]+$/ {sum += $6; found=1} END {if (found) print sum; else print -1}')"
    [[ "${lag}" == 0 ]] && return 0
    sleep 1
  done
  echo "Aeron projection lag did not reach zero: group=${group} lag=${lag}" >&2
  return 1
}

aeron_runtime_start() {
  local product_line="$1" evidence_dir="$2"
  [[ -z "${AERON_RUNTIME_PRODUCT_LINE}" ]] || {
    echo "Aeron runtime already active for ${AERON_RUNTIME_PRODUCT_LINE}" >&2
    return 1
  }
  AERON_RUNTIME_PRODUCT_LINE="${product_line}"
  AERON_RUNTIME_SEGMENT="$(aeron_runtime_segment "${product_line}")"
  AERON_RUNTIME_EVIDENCE_DIR="${evidence_dir}"
  AERON_RUNTIME_KAFKA="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
  AERON_RUNTIME_DATABASE_URL="${DATABASE_URL:-${SPRING_DATASOURCE_URL:-jdbc:postgresql://127.0.0.1:${POSTGRES_PORT:-5432}/${DB_NAME:-postgres}}}"
  AERON_RUNTIME_DATABASE_USER="${DATABASE_USER:-${DB_USER:-postgres}}"
  AERON_RUNTIME_DATABASE_PASSWORD="${DATABASE_PASSWORD:-${DB_PASSWORD:-postgres}}"
  AERON_RUNTIME_DATABASE_NAME="${DATABASE_NAME:-${DB_NAME:-postgres}}"
  mkdir -p "${AERON_RUNTIME_EVIDENCE_DIR}"
  aeron_runtime_assert_artifacts
  aeron_runtime_start_cluster
  aeron_runtime_prepare_pipeline
  aeron_runtime_start_pipeline
}

aeron_runtime_stop() {
  local pid
  [[ -n "${AERON_RUNTIME_PRODUCT_LINE}" ]] || return 0
  if [[ "${AERON_RUNTIME_KEEP}" != true ]]; then
    aeron_runtime_stop_pid "${AERON_RUNTIME_INPUT_BRIDGE_PID}"
    aeron_runtime_stop_pid "${AERON_RUNTIME_EXPORTER_PID}"
    aeron_runtime_stop_pid "${AERON_RUNTIME_PROJECTION_PID}"
    for pid in "${AERON_RUNTIME_NODE_PIDS[@]}"; do
      aeron_runtime_stop_pid "${pid}"
    done
    if [[ -n "${AERON_RUNTIME_DATA_DIR}" \
        && "${AERON_RUNTIME_DATA_DIR}" == "${TMPDIR:-/tmp}/surprising-aeron-runtime-"* ]]; then
      rm -rf "${AERON_RUNTIME_DATA_DIR}"
    fi
  fi
  AERON_RUNTIME_PRODUCT_LINE=""
  AERON_RUNTIME_SEGMENT=""
  AERON_RUNTIME_EVIDENCE_DIR=""
  AERON_RUNTIME_DATA_DIR=""
  AERON_RUNTIME_NODE_PIDS=()
  AERON_RUNTIME_INPUT_BRIDGE_PID=""
  AERON_RUNTIME_EXPORTER_PID=""
  AERON_RUNTIME_PROJECTION_PID=""
}
