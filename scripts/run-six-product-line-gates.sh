#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/surprising-aeron-core/compose.yaml"
OUTPUT_DIR="${GATE_OUTPUT_DIR:-${ROOT_DIR}/reports/product-line-gates/$(date -u +%Y%m%dT%H%M%SZ)}"
KEEP_TEST_VOLUMES="${KEEP_TEST_VOLUMES:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
PATH="${JAVA_HOME}/bin:${PATH}"
export JAVA_HOME PATH

if [[ -n "${PRODUCT_LINES:-}" ]]; then
  read -r -a lines <<<"${PRODUCT_LINES}"
else
  lines=(SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION)
fi
RESUME="${RESUME:-false}"
mkdir -p "${OUTPUT_DIR}"

slug() {
  printf '%s' "$1" | tr '[:upper:]_' '[:lower:]-'
}

seed_for() {
  case "$1" in
    SPOT) echo 80000 ;;
    LINEAR_PERPETUAL) echo 80100 ;;
    INVERSE_PERPETUAL) echo 80200 ;;
    LINEAR_DELIVERY) echo 80300 ;;
    INVERSE_DELIVERY) echo 80400 ;;
    OPTION) echo 80500 ;;
    *) echo "unsupported product line: $1" >&2; return 1 ;;
  esac
}

wait_ready() {
  local attempt
  for attempt in {1..20}; do
    if PROBE_MODE=query docker compose -f "${COMPOSE_FILE}" run --rm probe >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

cluster_tool() {
  local node="$1" command="$2"
  docker compose -f "${COMPOSE_FILE}" exec -T "node${node}" java \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp /opt/surprising/service.jar io.aeron.cluster.ClusterTool \
    "/var/lib/surprising/aeron/${PRODUCT_LINE_STATE_DIR}/node${node}/cluster" "${command}"
}

leader_node() {
  local node
  for node in 0 1 2; do
    if cluster_tool "${node}" is-leader >/dev/null 2>&1; then
      printf '%s' "${node}"
      return 0
    fi
  done
  return 1
}

apply_exporter_migrations() {
  local migration
  for migration in "${ROOT_DIR}"/surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/V*.sql; do
    docker exec -i rainbo-postgres psql -v ON_ERROR_STOP=1 -U postgres -d postgres <"${migration}" >/dev/null
  done
}

write_manifest() {
  local evidence="$1"
  {
    echo "timestamp_utc=$(date -u +%FT%TZ)"
    echo "git_commit=$(git -C "${ROOT_DIR}" rev-parse HEAD)"
    echo "product_line=${PRODUCT_LINE}"
    echo "product_line_slug=${PRODUCT_LINE_SLUG}"
    echo "java=$(${JAVA_HOME}/bin/java -version 2>&1 | head -n 1)"
    echo "maven=$(mvn -version | head -n 1)"
    echo "docker=$(docker version --format '{{.Server.Version}}')"
    echo "host_arch=$(uname -m)"
    echo "host_os=$(sw_vers -productVersion 2>/dev/null || uname -sr)"
    echo "aeron_image=$(docker image inspect surprising/aeron-core:local --format '{{.Id}}')"
    echo "heap=${AERON_CORE_HEAP:-512m}"
    echo "authority=Aeron Cluster Log/Archive/Snapshot"
  } >"${evidence}/environment-manifest.env"
}

if [[ "${SKIP_BUILD}" != "true" ]]; then
  mvn -q -f "${ROOT_DIR}/pom.xml" -pl :surprising-aeron-service,:surprising-aeron-tools -am -DskipTests package
  docker compose -f "${COMPOSE_FILE}" build -q
fi
apply_exporter_migrations

if [[ "${RESUME}" != "true" || ! -s "${OUTPUT_DIR}/index.md" ]]; then
  {
    echo "# P7 six product-line Aeron gates"
    echo
    echo "Started: $(date -u +%FT%TZ)"
    echo
    echo '| Product line | Functional | Funds | Leader failover | Cold recovery | Export/PG | Evidence |'
    echo '|---|---|---:|---|---|---|---|'
  } >"${OUTPUT_DIR}/index.md"
fi

for index in "${!lines[@]}"; do
  export PRODUCT_LINE="${lines[${index}]}"
  export PRODUCT_LINE_SLUG="$(slug "${PRODUCT_LINE}")"
  export PRODUCT_LINE_STATE_DIR="$(printf '%s' "${PRODUCT_LINE}" | tr '[:upper:]' '[:lower:]')"
  export PRODUCT_LINE_GATE_SEED="$(seed_for "${PRODUCT_LINE}")"
  evidence="${OUTPUT_DIR}/${PRODUCT_LINE}"
  mkdir -p "${evidence}"
  write_manifest "${evidence}"
  log="${evidence}/gate.log"

  docker compose -f "${COMPOSE_FILE}" down -v >>"${log}" 2>&1 || true
  docker compose -f "${COMPOSE_FILE}" up -d node0 node1 node2 >>"${log}" 2>&1
  wait_ready

  PRODUCT_LINE_GATE_MODE=execute docker compose -f "${COMPOSE_FILE}" run --rm product-line-gate \
    | tee -a "${log}"
  PROBE_MODE=query docker compose -f "${COMPOSE_FILE}" run --rm probe \
    | tee "${evidence}/state-before-recovery.txt" | tee -a "${log}"
  EXPORT_SMOKE_MODE=fail docker compose -f "${COMPOSE_FILE}" run --rm export-smoke \
    | tee "${evidence}/export-failure.txt" | tee -a "${log}"
  EXPORT_SMOKE_MODE=drain docker compose -f "${COMPOSE_FILE}" run --rm export-smoke \
    | tee "${evidence}/export-drain.txt" | tee -a "${log}"

  leader="$(leader_node)"
  echo "leader_before_kill=node${leader}" | tee "${evidence}/leader-failover.txt" | tee -a "${log}"
  docker compose -f "${COMPOSE_FILE}" stop "node${leader}" >>"${log}" 2>&1
  wait_ready
  PRODUCT_LINE_GATE_MODE=verify docker compose -f "${COMPOSE_FILE}" run --rm product-line-gate \
    | tee -a "${evidence}/leader-failover.txt" | tee -a "${log}"
  docker compose -f "${COMPOSE_FILE}" start "node${leader}" >>"${log}" 2>&1
  wait_ready

  snapshot_leader="$(leader_node)"
  cluster_tool "${snapshot_leader}" snapshot | tee "${evidence}/snapshot.txt" | tee -a "${log}"
  docker compose -f "${COMPOSE_FILE}" stop node0 node1 node2 >>"${log}" 2>&1
  docker compose -f "${COMPOSE_FILE}" start node0 node1 node2 >>"${log}" 2>&1
  wait_ready
  PRODUCT_LINE_GATE_MODE=verify docker compose -f "${COMPOSE_FILE}" run --rm product-line-gate \
    | tee "${evidence}/cold-recovery.txt" | tee -a "${log}"
  PROBE_MODE=query docker compose -f "${COMPOSE_FILE}" run --rm probe \
    | tee "${evidence}/state-after-recovery.txt" | tee -a "${log}"

  PRODUCT_LINE="${PRODUCT_LINE}" KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
    DATABASE_URL=jdbc:postgresql://localhost:5432/postgres DATABASE_USER=postgres DATABASE_PASSWORD=postgres \
    "${JAVA_HOME}/bin/java" -cp \
    "${ROOT_DIR}/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar" \
    com.surprising.aeron.exporter.ExporterInfrastructureSmokeMain \
    | tee "${evidence}/export-infrastructure.txt" | tee -a "${log}"

  if docker compose -f "${COMPOSE_FILE}" logs node0 node1 node2 \
      | rg -q 'Aeron clustered-service failure|OutOfMemoryError|AssertionError'; then
    echo "runtime failure marker found" >&2
    exit 1
  fi
  rg -q 'productLineGate=PASS mode=execute.*fundsDiff=0' "${log}"
  rg -q 'productLineGate=PASS mode=verify.*fundsDiff=0' "${evidence}/leader-failover.txt"
  rg -q 'productLineGate=PASS mode=verify.*fundsDiff=0' "${evidence}/cold-recovery.txt"
  rg -q 'exportFailure=PASS' "${evidence}/export-failure.txt"
  rg -q 'exportDrain=PASS.*pending=0' "${evidence}/export-drain.txt"
  rg -q 'exportInfrastructure=PASS.*pgRows=1' "${evidence}/export-infrastructure.txt"
  printf '0\n' >"${evidence}/funds-diff.txt"
  (cd "${evidence}" && shasum -a 256 ./*.txt ./*.env >SHA256SUMS)
  echo "| ${PRODUCT_LINE} | PASS | 0 | PASS | PASS | PASS | ${PRODUCT_LINE}/ |" >>"${OUTPUT_DIR}/index.md"

  if [[ "${KEEP_TEST_VOLUMES}" != "true" ]]; then
    docker compose -f "${COMPOSE_FILE}" down -v >>"${log}" 2>&1
  else
    docker compose -f "${COMPOSE_FILE}" stop node0 node1 node2 >>"${log}" 2>&1
  fi
done

(cd "${OUTPUT_DIR}" && shasum -a 256 index.md */SHA256SUMS >SHA256SUMS)
echo "functional-gate=PASS funds-diff=0 evidence=${OUTPUT_DIR}"
