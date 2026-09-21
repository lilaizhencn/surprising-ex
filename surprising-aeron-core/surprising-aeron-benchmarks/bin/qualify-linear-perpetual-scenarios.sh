#!/usr/bin/env bash
set -euo pipefail

# Independent LINEAR_PERPETUAL scenarios. This intentionally does not call or
# alter qualify-aeron-async-stages.sh: Cluster-backed cases own a fresh node and
# data directory, while population cases own an isolated JMH fork and result.
# Control-path costs therefore cannot distort the trading-stream capacity baseline.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BENCH_MODULE="${REPO_ROOT}/surprising-aeron-core/surprising-aeron-benchmarks"
SERVICE_JAR="${REPO_ROOT}/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
BENCHMARK_JAR="${BENCH_MODULE}/target/product-core-benchmarks.jar"
BASELINE_CONFIG="${SCENARIO_BASELINE_CONFIG:-${BENCH_MODULE}/config/aeron-single-node-baseline.env}"
RUN_ID="${SCENARIO_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
ROOT="${SCENARIO_ARTIFACT_DIR:-${BENCH_MODULE}/target/linear-perpetual-scenarios/${RUN_ID}}"
MODE="${1:-all}"
SKIP_BUILD="${SCENARIO_SKIP_BUILD:-false}"
LIQUIDATION_PAIRS="${SCENARIO_LIQUIDATION_PAIRS:-100}"
BOOK_PRICE_LEVELS="${SCENARIO_BOOK_PRICE_LEVELS:-200}"
BOOK_ORDERS_PER_LEVEL="${SCENARIO_BOOK_ORDERS_PER_LEVEL:-1250}"
POPULATION_HEAP="${SCENARIO_POPULATION_HEAP:-4g}"
POPULATION_WARMUP_ITERATIONS="${SCENARIO_POPULATION_WARMUP_ITERATIONS:-1}"
POPULATION_MEASUREMENT_ITERATIONS="${SCENARIO_POPULATION_MEASUREMENT_ITERATIONS:-3}"
POPULATION_ITERATION_SECONDS="${SCENARIO_POPULATION_ITERATION_SECONDS:-2}"
POPULATION_PROFILER="${SCENARIO_POPULATION_PROFILER:-}"

[[ -r "${BASELINE_CONFIG}" ]] || { echo "Baseline config is missing: ${BASELINE_CONFIG}" >&2; exit 2; }
# shellcheck disable=SC1090
source "${BASELINE_CONFIG}"

DEFAULT_JAVA_HOME="/Users/atomex/.sdkman/candidates/java/27.0.0-amzn"
JAVA_HOME_SELECTED="${SURPRISING_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "${JAVA_HOME_SELECTED}" && -d "${DEFAULT_JAVA_HOME}" ]]; then
  JAVA_HOME_SELECTED="${DEFAULT_JAVA_HOME}"
fi
[[ -n "${JAVA_HOME_SELECTED}" ]] || { echo "Set SURPRISING_JAVA_HOME to JDK 27 HotSpot." >&2; exit 2; }
JAVA="${JAVA_HOME_SELECTED}/bin/java"
JCMD="${JAVA_HOME_SELECTED}/bin/jcmd"
JAVA_VERSION="$(${JAVA} -version 2>&1)"
if [[ "${JAVA_VERSION}" != *'version "27'* \
    || ( "${JAVA_VERSION}" != *HotSpot* && "${JAVA_VERSION}" != *'OpenJDK 64-Bit Server VM'* ) ]]; then
  echo "Requires HotSpot JDK 27:" >&2
  echo "${JAVA_VERSION}" >&2
  exit 2
fi
[[ "${LIQUIDATION_PAIRS}" =~ ^[1-9][0-9]*$ ]] || { echo "SCENARIO_LIQUIDATION_PAIRS must be positive" >&2; exit 2; }
[[ "${BOOK_PRICE_LEVELS}" =~ ^[1-9][0-9]*$ ]] || { echo "SCENARIO_BOOK_PRICE_LEVELS must be positive" >&2; exit 2; }
[[ "${BOOK_ORDERS_PER_LEVEL}" =~ ^[1-9][0-9]*$ ]] || { echo "SCENARIO_BOOK_ORDERS_PER_LEVEL must be positive" >&2; exit 2; }

mkdir -p "${ROOT}"
ROOT="$(cd "${ROOT}" && pwd)"
if [[ "${SKIP_BUILD}" != true ]]; then
  JAVA_HOME="${JAVA_HOME_SELECTED}" mvn -f "${REPO_ROOT}/pom.xml" \
    -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package \
    > "${ROOT}/maven-package.log"
fi
[[ -s "${SERVICE_JAR}" && -s "${BENCHMARK_JAR}" ]] || { echo "Build artifacts are missing" >&2; exit 2; }
printf '%s\n' "${JAVA_VERSION}" > "${ROOT}/java-version.txt"
printf 'mode=%s\nliquidationPairs=%s\nbookPriceLevels=%s\nbookOrdersPerLevel=%s\npopulationHeap=%s\npopulationProfiler=%s\n' \
  "${MODE}" "${LIQUIDATION_PAIRS}" \
  "${BOOK_PRICE_LEVELS}" "${BOOK_ORDERS_PER_LEVEL}" "${POPULATION_HEAP}" "${POPULATION_PROFILER}" \
  > "${ROOT}/scenario-config.txt"

NODE_PID=""
stop_node() {
  if [[ -n "${NODE_PID}" ]] && kill -0 "${NODE_PID}" 2>/dev/null; then
    kill -TERM "${NODE_PID}" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 "${NODE_PID}" 2>/dev/null || break
      sleep 0.25
    done
    kill -KILL "${NODE_PID}" 2>/dev/null || true
    wait "${NODE_PID}" 2>/dev/null || true
  fi
  NODE_PID=""
}
trap stop_node EXIT INT TERM

start_node() {
  local case_dir="$1"
  mkdir -p "${case_dir}/data" "${case_dir}/tmp" "${case_dir}/aeron"
  local -a args=(
    --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
    --add-opens=java.base/java.util.zip=ALL-UNNAMED
    --enable-native-access=ALL-UNNAMED
    "-Xms${AERON_BASELINE_NODE_XMS}" "-Xmx${AERON_BASELINE_NODE_XMX}"
    -XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC
    -XX:NativeMemoryTracking=summary
    -Dsurprising.aeron.product-line=LINEAR_PERPETUAL
    -Dsurprising.aeron.hostnames=127.0.0.1
    -Dsurprising.aeron.egress-hostname=127.0.0.1
    -Dsurprising.aeron.node-id=0
    "-Dsurprising.aeron.account-lanes=${AERON_BASELINE_ACCOUNT_LANES}"
    "-Dsurprising.aeron.matching-engines=${AERON_BASELINE_MATCHING_ENGINES}"
    "-Dsurprising.aeron.owner-command-window=${AERON_BASELINE_WINDOW}"
    -Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN
    -Dsurprising.aeron.matcher-pipeline-wait-strategy=BUSY_SPIN
    -Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN
    -Dsurprising.aeron.settlement-spin-limit=0
    -Dsurprising.aeron.owner-wait-strategy=BUSY_SPIN
    -Dsurprising.aeron.owner-input-batch-size=64
    -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK
    -Dsurprising.aeron.service.idle-strategy=YIELDING
    "-Dsurprising.aeron.data-dir=${case_dir}/data"
    "-Daeron.dir=${case_dir}/aeron"
    "-Djava.io.tmpdir=${case_dir}/tmp"
    -jar "${SERVICE_JAR}"
  )
  printf '%q ' "${JAVA}" "${args[@]}" > "${case_dir}/node.command"
  printf '\n' >> "${case_dir}/node.command"
  (cd "${case_dir}" && exec "${JAVA}" "${args[@]}") > "${case_dir}/node.log" 2>&1 &
  NODE_PID=$!
  for attempt in {1..40}; do
    kill -0 "${NODE_PID}" 2>/dev/null || { tail -80 "${case_dir}/node.log" >&2; return 1; }
    if (( attempt >= 8 )); then
      sleep 2
      "${JCMD}" "${NODE_PID}" VM.native_memory baseline > "${case_dir}/nmt-baseline.txt" 2>&1 || true
      return 0
    fi
    sleep 1
  done
}

finish_node() {
  local case_dir="$1"
  if [[ -n "${NODE_PID}" ]] && kill -0 "${NODE_PID}" 2>/dev/null; then
    "${JCMD}" "${NODE_PID}" VM.native_memory summary.diff > "${case_dir}/nmt-summary.diff.txt" 2>&1 || true
  fi
  stop_node
}

client_jvm_args() {
  local case_dir="$1"
  printf '%s' "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens=java.base/java.util.zip=ALL-UNNAMED \
--enable-native-access=ALL-UNNAMED \
-Xms${AERON_BASELINE_CLIENT_XMS} -Xmx${AERON_BASELINE_CLIENT_XMX} -XX:+UseG1GC \
-Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1 \
-Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Daeron.dir=${case_dir}/client-aeron"
}

run_jmh_case() {
  local case_id="$1" benchmark="$2" pass_pattern="$3"
  local case_dir="${ROOT}/${case_id}"
  echo "[scenario] ${case_id}"
  start_node "${case_dir}"
  local jvm_args
  jvm_args="$(client_jvm_args "${case_dir}")"
  printf '%q ' "${JAVA}" -jar "${BENCHMARK_JAR}" "^${benchmark}$" \
    -wi 0 -i 1 -f 1 -t 1 -to 300s -jvmArgsAppend "${jvm_args}" \
    -rf json -rff "${case_dir}/result.json" > "${case_dir}/client.command"
  printf '\n' >> "${case_dir}/client.command"
  set +e
  "${JAVA}" -jar "${BENCHMARK_JAR}" "^${benchmark}$" \
    -wi 0 -i 1 -f 1 -t 1 -to 300s -jvmArgsAppend "${jvm_args}" \
    -rf json -rff "${case_dir}/result.json" > "${case_dir}/client.log" 2>&1
  local status=$?
  set -e
  printf '%s\n' "${status}" > "${case_dir}/client.exit"
  finish_node "${case_dir}"
  (( status == 0 )) || { tail -120 "${case_dir}/client.log" >&2; return "${status}"; }
  grep -Eq "${pass_pattern}" "${case_dir}/client.log" || {
    echo "Missing correctness marker for ${case_id}: ${pass_pattern}" >&2
    return 1
  }
}

run_main_case() {
  local case_id="$1" main_class="$2" pass_pattern="$3"
  shift 3
  local case_dir="${ROOT}/${case_id}"
  echo "[scenario] ${case_id}"
  start_node "${case_dir}"
  local -a args=(
    --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens=java.base/java.util.zip=ALL-UNNAMED
    --enable-native-access=ALL-UNNAMED
    "-Xms${AERON_BASELINE_CLIENT_XMS}" "-Xmx${AERON_BASELINE_CLIENT_XMX}" -XX:+UseG1GC
    -Dsurprising.aeron.hostnames=127.0.0.1
    -Dsurprising.aeron.egress-hostname=127.0.0.1
    -Dsurprising.aeron.product-line=LINEAR_PERPETUAL
    "-Daeron.dir=${case_dir}/client-aeron"
    "$@"
    -cp "${BENCHMARK_JAR}" "${main_class}"
  )
  printf '%q ' "${JAVA}" "${args[@]}" > "${case_dir}/client.command"
  printf '\n' >> "${case_dir}/client.command"
  set +e
  "${JAVA}" "${args[@]}" > "${case_dir}/client.log" 2>&1
  local status=$?
  set -e
  printf '%s\n' "${status}" > "${case_dir}/client.exit"
  finish_node "${case_dir}"
  (( status == 0 )) || { tail -120 "${case_dir}/client.log" >&2; return "${status}"; }
  grep -Eq "${pass_pattern}" "${case_dir}/client.log" || {
    echo "Missing correctness marker for ${case_id}: ${pass_pattern}" >&2
    return 1
  }
}

run_local_jmh_case() {
  local case_id="$1" benchmark="$2"
  shift 2
  local case_dir="${ROOT}/${case_id}"
  mkdir -p "${case_dir}"
  echo "[population] ${case_id}"
  local jvm_args="--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens=java.base/java.util.zip=ALL-UNNAMED \
--enable-native-access=ALL-UNNAMED -Xms${POPULATION_HEAP} -Xmx${POPULATION_HEAP} \
-XX:+UseG1GC -XX:+AlwaysPreTouch -XX:+DisableExplicitGC \
-Dsurprising.aeron.account-lanes=${AERON_BASELINE_ACCOUNT_LANES} \
-Dsurprising.aeron.matching-engines=${AERON_BASELINE_MATCHING_ENGINES} \
-Dsurprising.aeron.matcher-wait-strategy=BUSY_SPIN \
-Dsurprising.aeron.matcher-pipeline-wait-strategy=BUSY_SPIN \
-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN \
-Dsurprising.aeron.owner-wait-strategy=BUSY_SPIN"
  local -a jmh_args=("^${benchmark}$" "$@"
    -wi "${POPULATION_WARMUP_ITERATIONS}" -w "${POPULATION_ITERATION_SECONDS}s"
    -i "${POPULATION_MEASUREMENT_ITERATIONS}" -r "${POPULATION_ITERATION_SECONDS}s"
    -f 1 -t 1 -foe true -to 1800s)
  if [[ -n "${POPULATION_PROFILER}" ]]; then
    jmh_args+=(-prof "${POPULATION_PROFILER}")
  fi
  jmh_args+=(-jvmArgsAppend "${jvm_args}" -rf json -rff "${case_dir}/result.json")
  printf '%q ' "${JAVA}" -jar "${BENCHMARK_JAR}" "${jmh_args[@]}" \
    > "${case_dir}/client.command"
  printf '\n' >> "${case_dir}/client.command"
  set +e
  "${JAVA}" -jar "${BENCHMARK_JAR}" "${jmh_args[@]}" > "${case_dir}/client.log" 2>&1
  local status=$?
  set -e
  printf '%s\n' "${status}" > "${case_dir}/client.exit"
  (( status == 0 )) || { tail -160 "${case_dir}/client.log" >&2; return "${status}"; }
  jq -e 'length == 1 and .[0].primaryMetric.score > 0 and
    ((.[0].secondaryMetrics.acceptedBusinessOperations // {score: 1}).score ==
     (.[0].secondaryMetrics.terminalBusinessOperations // {score: 1}).score) and
    ((.[0].secondaryMetrics.unfinishedBusinessOperations // {score: 0}).score == 0)' \
    "${case_dir}/result.json" > /dev/null
}

run_order_lifecycle() {
  run_jmh_case order-lifecycle \
    com.surprising.aeron.benchmarks.workload.ClusterTriggerBoundaryBenchmark.amendCancelCycleOnOwningLane \
    'amendCycle=.*PASS fundsDiff=0 positions=0 reservations=0'
}

run_triggers() {
  run_jmh_case trigger-direct \
    com.surprising.aeron.benchmarks.workload.ClusterTriggerBoundaryBenchmark.triggerOnOwningLane \
    'triggerCase=.*PASS.*fundsDiff=0.*ocoCanceled=true'
  run_jmh_case trigger-scan \
    com.surprising.aeron.benchmarks.workload.ClusterTriggerBoundaryBenchmark.scannedTriggerOnOwningLane \
    'triggerCase=.*PASS.*fundsDiff=0.*ocoCanceled=true'
  run_jmh_case trigger-reject \
    com.surprising.aeron.benchmarks.workload.ClusterTriggerBoundaryBenchmark.rejectedTriggerOnOwningLane \
    'triggerReject=.*PASS|triggerCase=.*PASS'
}

run_account_controls() {
  run_jmh_case account-controls \
    com.surprising.aeron.benchmarks.workload.ClusterAccountControlBenchmark.accountControls \
    'accountControlVerify=PASS.*fundsDiff=0.*netPosition=0'
}

run_funding() {
  run_main_case funding-boundary \
    com.surprising.aeron.benchmarks.workload.ClusterDerivativeSmokeMain \
    'derivativeSmoke=PASS.*fundingNet=0' \
    -Dsurprising.aeron.smoke-seed=51001
}

run_liquidation() {
  run_main_case liquidation-storm \
    com.surprising.aeron.benchmarks.workload.ClusterLifecycleCapacityMain \
    'lifecycleCapacity=PASS.*scenario=LIQUIDATION_STORM.*fundsDiff=0' \
    -Dsurprising.aeron.lifecycle-seed=995001 \
    "-Dsurprising.aeron.lifecycle-pairs=${LIQUIDATION_PAIRS}" \
    -Dsurprising.aeron.lifecycle-connections=8
}

run_adl() {
  run_jmh_case insurance-adl \
    com.surprising.aeron.benchmarks.workload.ClusterAdlBoundaryBenchmark.insuranceAndAdlOnOwningLanes \
    'mixedVerify=PASS fundsDiff=0.*loss=true'
}

run_dense_book() {
  # Build the population once. The timed path only places and cancels at a dense
  # level, preserving the resident order count without snapshot restore noise.
  run_local_jmh_case dense-book-population \
    com.surprising.aeron.service.orchestration.LinearPerpetualCoreBenchmark.denseResidentBookPlaceCancel \
    -p accountLanes=4 -p priceLevels="${BOOK_PRICE_LEVELS}" \
    -p ordersPerLevel="${BOOK_ORDERS_PER_LEVEL}"
  run_local_jmh_case dense-level-fill \
    com.surprising.aeron.service.orchestration.LinearPerpetualCoreBenchmark.deepFillBurst256 \
    -p accountLanes=4 -p makerDepth=128 -p maxInFlight=256
}

run_stable_positions() {
  # Every account keeps a live position. The mark moves only from 100 to 99;
  # the correctness gate requires that the complete scan produces no liquidation.
  run_local_jmh_case stable-position-risk-scan \
    com.surprising.aeron.service.orchestration.LinearPerpetualCoreBenchmark.stablePositionRiskScan \
    -p accountLanes=4 -p positionUsers=10000
}

run_risk_storm() {
  run_local_jmh_case risk-scan-10000 \
    com.surprising.aeron.service.orchestration.LinearPerpetualCoreBenchmark.riskScanLanePublishedCommit \
    -p accountLanes=4 -p riskUsers=10000
  run_local_jmh_case liquidation-batch-1000 \
    com.surprising.aeron.service.orchestration.LinearPerpetualCoreBenchmark.liquidationBurst1000 \
    -p accountLanes=4
}

case "${MODE}" in
  orders) run_order_lifecycle ;;
  triggers) run_triggers ;;
  account) run_account_controls ;;
  funding) run_funding ;;
  liquidation) run_liquidation ;;
  adl) run_adl ;;
  book) run_dense_book ;;
  positions) run_stable_positions ;;
  risk-storm) run_risk_storm ;;
  population)
    run_dense_book
    run_stable_positions
    run_risk_storm
    ;;
  all)
    run_order_lifecycle
    run_triggers
    run_account_controls
    run_funding
    run_liquidation
    run_adl
    run_dense_book
    run_stable_positions
    run_risk_storm
    ;;
  *)
    echo "Usage: $0 [orders|triggers|account|funding|liquidation|adl|book|positions|risk-storm|population|all]" >&2
    exit 2
    ;;
esac

python3 - "${ROOT}" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
rows = []
for exit_file in sorted(root.glob("*/client.exit")):
    case_dir = exit_file.parent
    rows.append({
        "scenario": case_dir.name,
        "exitCode": int(exit_file.read_text().strip()),
        "result": str(case_dir / "result.json") if (case_dir / "result.json").exists() else None,
        "log": str(case_dir / "client.log"),
    })
summary = {"run": str(root), "status": "PASS" if rows and all(row["exitCode"] == 0 for row in rows) else "FAIL", "scenarios": rows}
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
PY

echo "Scenario artifacts: ${ROOT}"
