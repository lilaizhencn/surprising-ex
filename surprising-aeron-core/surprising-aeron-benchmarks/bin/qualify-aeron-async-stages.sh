#!/usr/bin/env bash
set -euo pipefail

# Real single-member Aeron qualification. Each stage gets a fresh node and a
# separate JFR/NMT capture so CPU saturation is not confused with busy-spin.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BENCH_MODULE="${REPO_ROOT}/surprising-aeron-core/surprising-aeron-benchmarks"
SERVICE_JAR="${REPO_ROOT}/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
BENCHMARK_JAR="${BENCH_MODULE}/target/product-core-benchmarks.jar"
PROFILE="${BENCH_MODULE}/config/owner-commit-profile.jfc"
BASELINE_CONFIG="${ASYNC_BASELINE_CONFIG:-${BENCH_MODULE}/config/aeron-single-node-baseline.env}"
[[ -r "${BASELINE_CONFIG}" ]] || { echo "Baseline config is missing: ${BASELINE_CONFIG}" >&2; exit 2; }
# shellcheck disable=SC1090
source "${BASELINE_CONFIG}"
RUN_ID="${ASYNC_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
ROOT="${ASYNC_ARTIFACT_DIR:-${BENCH_MODULE}/target/aeron-async-stages/${RUN_ID}}"
WINDOWS_CSV="${ASYNC_WINDOWS:-${AERON_BASELINE_WINDOW}}"
WARMUP_SECONDS="${ASYNC_WARMUP_SECONDS:-${AERON_BASELINE_WARMUP_SECONDS}}"
MEASURE_SECONDS="${ASYNC_MEASURE_SECONDS:-${AERON_BASELINE_MEASURE_SECONDS}}"
NODE_XMS="${ASYNC_NODE_XMS:-${AERON_BASELINE_NODE_XMS}}"
NODE_XMX="${ASYNC_NODE_XMX:-${AERON_BASELINE_NODE_XMX}}"
CLIENT_XMS="${ASYNC_CLIENT_XMS:-${AERON_BASELINE_CLIENT_XMS}}"
CLIENT_XMX="${ASYNC_CLIENT_XMX:-${AERON_BASELINE_CLIENT_XMX}}"
ACCOUNT_LANES="${ASYNC_ACCOUNT_LANES:-${AERON_BASELINE_ACCOUNT_LANES}}"
MATCHING_ENGINES="${ASYNC_MATCHING_ENGINES:-${AERON_BASELINE_MATCHING_ENGINES}}"
TRADING_PROFILE="${ASYNC_TRADING_PROFILE:-${AERON_BASELINE_TRADING_PROFILE}}"
BATCH_SIZE="${ASYNC_BATCH_SIZE:-${AERON_BASELINE_BATCH_SIZE}}"
SYMBOLS="${ASYNC_SYMBOLS:-${AERON_BASELINE_SYMBOLS:-128}}"
ISOLATE_STAGE="${ASYNC_ISOLATE_STAGE:-false}"
# Throughput qualification defaults to BUSY_SPIN to match the historical
# overall transaction-link benchmark. Override explicitly when measuring
# blocking/park overhead.
SETTLEMENT_WAIT_STRATEGY="${ASYNC_SETTLEMENT_WAIT_STRATEGY:-${AERON_BASELINE_SETTLEMENT_WAIT_STRATEGY}}"
SETTLEMENT_SPIN_LIMIT="${ASYNC_SETTLEMENT_SPIN_LIMIT:-${AERON_BASELINE_SETTLEMENT_SPIN_LIMIT}}"
MATCHER_WAIT_STRATEGY="${ASYNC_MATCHER_WAIT_STRATEGY:-${AERON_BASELINE_MATCHER_WAIT_STRATEGY}}"
COLLECTOR="${ASYNC_COLLECTOR:-${AERON_BASELINE_GC}}"
ENABLE_JFR="${ASYNC_ENABLE_JFR:-false}"
OWNER_POLL_DIAGNOSTICS="${ASYNC_OWNER_POLL_DIAGNOSTICS:-false}"
LANE_STAGE_LANES="${ASYNC_LANE_STAGE_LANES:-1}"
MATCHER_STAGE_LANES="${ASYNC_MATCHER_STAGE_LANES:-1}"
ONLY_STAGE="${ASYNC_ONLY_STAGE:-}"
SKIP_BUILD="${ASYNC_SKIP_BUILD:-false}"

DEFAULT_JAVA_HOME="/Users/atomex/.sdkman/candidates/java/27.0.0-amzn"
JAVA_HOME_SELECTED="${SURPRISING_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "${JAVA_HOME_SELECTED}" && -d "${DEFAULT_JAVA_HOME}" ]]; then JAVA_HOME_SELECTED="${DEFAULT_JAVA_HOME}"; fi
if [[ -z "${JAVA_HOME_SELECTED}" ]]; then echo "Set SURPRISING_JAVA_HOME to JDK 27 HotSpot." >&2; exit 2; fi
JAVA="${JAVA_HOME_SELECTED}/bin/java"; JFR="${JAVA_HOME_SELECTED}/bin/jfr"; JCMD="${JAVA_HOME_SELECTED}/bin/jcmd"
JAVA_VERSION="$(${JAVA} -version 2>&1)"
if [[ "${JAVA_VERSION}" != *'version "27'* \
    || ( "${JAVA_VERSION}" != *HotSpot* && "${JAVA_VERSION}" != *'OpenJDK 64-Bit Server VM'* ) ]]; then
  echo "Requires HotSpot JDK 27:" >&2; echo "${JAVA_VERSION}" >&2; exit 2;
fi
[[ -x "${JFR}" && -x "${JCMD}" ]] || { echo "JFR/jcmd unavailable under ${JAVA_HOME_SELECTED}" >&2; exit 2; }
case "${COLLECTOR}" in
  ZGC) GC_FLAG=(-XX:+UseZGC) ;;
  G1|G1GC) COLLECTOR="G1"; GC_FLAG=(-XX:+UseG1GC) ;;
  *) echo "ASYNC_COLLECTOR must be ZGC or G1" >&2; exit 2 ;;
esac
mkdir -p "${ROOT}"
if [[ "${SKIP_BUILD}" != true ]]; then
  JAVA_HOME="${JAVA_HOME_SELECTED}" mvn -f "${REPO_ROOT}/pom.xml" -pl surprising-aeron-core/surprising-aeron-benchmarks -am -DskipTests package > "${ROOT}.build.log"
fi
[[ -s "${SERVICE_JAR}" && -s "${BENCHMARK_JAR}" ]] || { echo "Build artifacts are missing" >&2; exit 2; }
printf '%s\n' "${JAVA_VERSION}" > "${ROOT}/java-version.txt"
printf 'windows=%s\nwarmupSeconds=%s\nmeasureSeconds=%s\nnodeXms=%s\nnodeXmx=%s\nclientXms=%s\nclientXmx=%s\ncollector=%s\naccountLanes=%s\nmatchingEngines=%s\nbatchSize=%s\nsymbols=%s\ntradingProfile=%s\nisolateStage=%s\nenableJfr=%s\nownerPollDiagnostics=%s\nmatcherWaitStrategy=%s\nsettlementWaitStrategy=%s\nsettlementSpinLimit=%s\n' \
  "${WINDOWS_CSV}" "${WARMUP_SECONDS}" "${MEASURE_SECONDS}" "${NODE_XMS}" "${NODE_XMX}" "${CLIENT_XMS}" "${CLIENT_XMX}" "${COLLECTOR}" "${ACCOUNT_LANES}" "${MATCHING_ENGINES}" "${BATCH_SIZE}" "${SYMBOLS}" "${TRADING_PROFILE}" "${ISOLATE_STAGE}" "${ENABLE_JFR}" "${OWNER_POLL_DIAGNOSTICS}" "${MATCHER_WAIT_STRATEGY}" "${SETTLEMENT_WAIT_STRATEGY}" "${SETTLEMENT_SPIN_LIMIT}" > "${ROOT}/strategy.txt"

NODE_PID=""
NODE_LANES="${ACCOUNT_LANES}"
NODE_MATCHERS="${MATCHING_ENGINES}"
stop_node() {
  if [[ -n "${NODE_PID}" ]] && kill -0 "${NODE_PID}" 2>/dev/null; then
    kill -TERM "${NODE_PID}" 2>/dev/null || true
    for _ in {1..20}; do kill -0 "${NODE_PID}" 2>/dev/null || break; sleep 0.25; done
    kill -KILL "${NODE_PID}" 2>/dev/null || true
    wait "${NODE_PID}" 2>/dev/null || true
  fi
  NODE_PID=""
}
trap stop_node EXIT INT TERM

start_node() {
  local dir="$1"; mkdir -p "${dir}/data" "${dir}/tmp" "${dir}/aeron"
  local -a args=(
    --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
    --add-opens=java.base/java.util.zip=ALL-UNNAMED
    --enable-native-access=ALL-UNNAMED
    "-Xms${NODE_XMS}" "-Xmx${NODE_XMX}" "${GC_FLAG[@]}" -XX:+AlwaysPreTouch -XX:+DisableExplicitGC
    -XX:NativeMemoryTracking=summary
    -Dsurprising.aeron.product-line=LINEAR_PERPETUAL -Dsurprising.aeron.hostnames=127.0.0.1
    -Dsurprising.aeron.egress-hostname=127.0.0.1 -Dsurprising.aeron.node-id=0
    "-Dsurprising.owner.poll-diagnostics=${OWNER_POLL_DIAGNOSTICS}"
    "-Dsurprising.aeron.account-lanes=${NODE_LANES}" "-Dsurprising.aeron.matching-engines=${NODE_MATCHERS}"
    "-Dsurprising.aeron.owner-command-window=${window}" "-Dsurprising.aeron.matcher-wait-strategy=${MATCHER_WAIT_STRATEGY}"
    "-Dsurprising.aeron.settlement-wait-strategy=${SETTLEMENT_WAIT_STRATEGY}"
    "-Dsurprising.aeron.settlement-spin-limit=${SETTLEMENT_SPIN_LIMIT}"
    -Dsurprising.aeron.core.threading-mode=SHARED_NETWORK -Dsurprising.aeron.service.idle-strategy=YIELDING
    "-Dsurprising.aeron.data-dir=${dir}/data" "-Daeron.dir=${dir}/aeron" "-Djava.io.tmpdir=${dir}/tmp")
  if [[ "${ENABLE_JFR}" == true ]]; then
    args+=(-Dcore.settlementLatencyDiagnostics=true "-XX:StartFlightRecording=settings=${PROFILE},filename=${dir}/node.jfr,maxsize=256m,dumponexit=true")
  fi
  args+=(-cp "${SERVICE_JAR}" com.surprising.aeron.service.SurprisingCoreBootstrap)
  printf '%q ' "${JAVA}" "${args[@]}" > "${dir}/node.command"; printf '\n' >> "${dir}/node.command"
  (cd "${dir}" && exec "${JAVA}" "${args[@]}") > "${dir}/node.log" 2>&1 & NODE_PID=$!
  for _ in {1..40}; do
    kill -0 "${NODE_PID}" 2>/dev/null || { tail -80 "${dir}/node.log" >&2; return 1; }
    if (( _ >= 8 )); then sleep 2; return 0; fi
    sleep 1
  done
}

run_stage() {
  local stage="$1" profile="$2" batch="$3" window="$4"; local dir="${ROOT}/window-${window}/${stage}"
  mkdir -p "${dir}"; echo "[stage] window=${window} target=${stage} profile=${profile} batch=${batch} dir=${dir}"
  NODE_LANES="${ACCOUNT_LANES}"; NODE_MATCHERS="${MATCHING_ENGINES}"
  if [[ "${ISOLATE_STAGE}" == true && ( "${stage}" == lane || "${stage}" == matcher ) ]]; then NODE_LANES="${LANE_STAGE_LANES}"; fi
  if [[ "${ISOLATE_STAGE}" == true && "${stage}" == matcher ]]; then NODE_LANES="${MATCHER_STAGE_LANES}"; fi
  printf 'target=%s\nnodeLanes=%s\nnodeMatchers=%s\nrequestedWindow=%s\ntradingProfile=%s\nbatchSize=%s\nenableJfr=%s\n' "${stage}" "${NODE_LANES}" "${NODE_MATCHERS}" "${window}" "${profile}" "${batch}" "${ENABLE_JFR}" > "${dir}/stage-config.txt"
  start_node "${dir}"
  "${JCMD}" "${NODE_PID}" VM.native_memory baseline > "${dir}/nmt-baseline.txt" 2>&1 || true
  local -a client_args=(
    --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens=java.base/java.util.zip=ALL-UNNAMED --enable-native-access=ALL-UNNAMED "${GC_FLAG[@]}" "-Xms${CLIENT_XMS}" "-Xmx${CLIENT_XMX}"
    -Dsurprising.aeron.hostnames=127.0.0.1 -Dsurprising.aeron.egress-hostname=127.0.0.1
    -Dsurprising.aeron.product-line=LINEAR_PERPETUAL "-Daeron.dir=${dir}/client-aeron"
    "-Dsurprising.aeron.capacity-warmup-seconds=${WARMUP_SECONDS}" "-Dsurprising.aeron.capacity-duration-seconds=${MEASURE_SECONDS}"
    "-Dsurprising.aeron.capacity-seed=$((window * 100 + batch))" -Dsurprising.aeron.mixed-trading-stream=true
    "-Dsurprising.aeron.capacity-symbols=${SYMBOLS}"
    -Dsurprising.aeron.mixed-operational=false "-Dsurprising.aeron.capacity-async-in-flight=${window}"
    "-Dsurprising.aeron.capacity-session-in-flight=${window}")
  if [[ "${ENABLE_JFR}" == true ]]; then
    # JMH launches a runner and a forked benchmark JVM.  A single filename
    # lets both JVMs write the same recording and produces an invalid JFR.
    client_args+=("-XX:StartFlightRecording=settings=${PROFILE},filename=${dir}/client-%p.jfr,maxsize=256m,dumponexit=true")
  fi
  printf '%q ' "${JAVA}" "${client_args[@]}" -jar "${BENCHMARK_JAR}" org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations \
    -p controlPageSize=0 -p inFlightWindow="${window}" -p tradingProfile="${profile}" -p batchSize="${batch}" \
    -wi 0 -i 1 -f 1 -t 1 -to "$((WARMUP_SECONDS + MEASURE_SECONDS + 90))s" -rf json -rff "${dir}/jmh.json" > "${dir}/client.command"
  set +e
  "${JAVA}" "${client_args[@]}" -jar "${BENCHMARK_JAR}" org.openjdk.jmh.Main ClusterOperationalBenchmark.continuousOperations \
    -p controlPageSize=0 -p inFlightWindow="${window}" -p tradingProfile="${profile}" -p batchSize="${batch}" \
    -wi 0 -i 1 -f 1 -t 1 -to "$((WARMUP_SECONDS + MEASURE_SECONDS + 90))s" -rf json -rff "${dir}/jmh.json" > "${dir}/client.log" 2>&1
  local client_status=$?; set -e; printf '%s\n' "${client_status}" > "${dir}/client.exit"
  if kill -0 "${NODE_PID}" 2>/dev/null; then
    "${JCMD}" "${NODE_PID}" Thread.print -l > "${dir}/threads.txt" 2>&1 || true
    "${JCMD}" "${NODE_PID}" VM.native_memory summary.diff > "${dir}/nmt-summary.diff.txt" 2>&1 || true
    "${JCMD}" "${NODE_PID}" VM.native_memory summary > "${dir}/nmt-summary.txt" 2>&1 || true
  fi
  stop_node
  if [[ -s "${dir}/node.jfr" ]]; then
    "${JFR}" summary "${dir}/node.jfr" > "${dir}/jfr-summary.txt" 2>&1 || true
    for view in thread-cpu-load hot-methods allocation-by-class allocation-by-site allocation-by-thread contention-by-thread latencies-by-type gc safepoints; do
      "${JFR}" view --width 220 "${view}" "${dir}/node.jfr" > "${dir}/${view}.txt" 2>&1 || true
    done
  fi
  while IFS= read -r -d '' client_jfr; do
    client_prefix="${client_jfr%.jfr}"
    "${JFR}" summary "${client_jfr}" > "${client_prefix}-summary.txt" 2>&1 || true
    for view in thread-cpu-load hot-methods allocation-by-class allocation-by-site allocation-by-thread contention-by-thread latencies-by-type gc safepoints; do
      "${JFR}" view --width 220 "${view}" "${client_jfr}" > "${client_prefix}-${view}.txt" 2>&1 || true
    done
  done < <(find "${dir}" -maxdepth 1 -type f -name 'client-*.jfr' -print0)
  python3 "${SCRIPT_DIR}/summarize-aeron-async-stage.py" "${dir}" --stage "${stage}" --window "${window}" > "${dir}/metrics.pretty.json"
  if (( client_status != 0 )); then echo "stage client failed: ${dir}" >&2; return "${client_status}"; fi
}

IFS=',' read -r -a WINDOWS <<< "${WINDOWS_CSV}"
for window in "${WINDOWS[@]}"; do
  [[ "${window}" =~ ^[0-9]+$ ]] || { echo "Invalid window: ${window}" >&2; exit 2; }
  if [[ -z "${ONLY_STAGE}" || "${ONLY_STAGE}" == owner ]]; then run_stage owner "${TRADING_PROFILE}" "${BATCH_SIZE}" "${window}"; fi
  if [[ -z "${ONLY_STAGE}" || "${ONLY_STAGE}" == matcher ]]; then run_stage matcher "${TRADING_PROFILE}" "${BATCH_SIZE}" "${window}"; fi
  if [[ -z "${ONLY_STAGE}" || "${ONLY_STAGE}" == lane ]]; then run_stage lane "${TRADING_PROFILE}" "${BATCH_SIZE}" "${window}"; fi
  if [[ -z "${ONLY_STAGE}" || "${ONLY_STAGE}" == end_to_end ]]; then run_stage end_to_end "${TRADING_PROFILE}" "${BATCH_SIZE}" "${window}"; fi
done
python3 - "${ROOT}" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1]); rows = [json.loads(p.read_text()) for p in sorted(root.glob('window-*/**/metrics.json'))]
(root / 'summary.json').write_text(json.dumps({'run': str(root), 'stages': rows}, indent=2, allow_nan=False) + '\n')
print(json.dumps({'run': str(root), 'stages': len(rows), 'summary': str(root / 'summary.json')}, indent=2))
PY
printf 'Artifacts: %s\n' "${ROOT}"
