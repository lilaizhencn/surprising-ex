#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE is required}"
TEST_MODE="${TEST_MODE:-performance}"
EXECUTE="${EXECUTE:-false}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
EVIDENCE_DIR="${EVIDENCE_DIR:-/tmp/surprising-production-tests/${RUN_ID}/${PRODUCT_LINE}}"
STRESS_TARGET_TPS="${STRESS_TARGET_TPS:-${TEST_TARGET_TPS}}"
STRESS_SYMBOL_COUNT="${STRESS_SYMBOL_COUNT:-${TEST_STRESS_SYMBOL_COUNT}}"
STRESS_USER_COUNT="${STRESS_USER_COUNT:-${TEST_STRESS_USER_COUNT}}"
STRESS_LOAD_CONCURRENCY="${STRESS_LOAD_CONCURRENCY:-${TEST_STRESS_LOAD_CONCURRENCY}}"
STRESS_HOT_SYMBOL_COUNT="${STRESS_HOT_SYMBOL_COUNT:-0}"
STRESS_HOT_TRAFFIC_PERCENT="${STRESS_HOT_TRAFFIC_PERCENT:-80}"
STRESS_SCENARIO="${STRESS_SCENARIO:-trade}"
STRESS_TMP_DIR_FILE="${EVIDENCE_DIR:-/tmp}/provider-tmp-dir"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-2}"
REPORT_FILE="${EVIDENCE_DIR}/stress-report.md"
RUN_LOG="${EVIDENCE_DIR}/run.log"
RESULT_FILE="${EVIDENCE_DIR}/result.env"
STOP_FILE="${EVIDENCE_DIR}/resource-monitor.stop"
MONITOR_PID=""

SLO_ORDER_ACCEPT_P99_MS="${SLO_ORDER_ACCEPT_P99_MS:-80}"
SLO_MATCH_RESULT_P99_MS="${SLO_MATCH_RESULT_P99_MS:-150}"
SLO_SETTLEMENT_P99_MS="${SLO_SETTLEMENT_P99_MS:-300}"
SLO_KAFKA_FINAL_LAG="${SLO_KAFKA_FINAL_LAG:-0}"
SLO_OUTBOX_FINAL_PENDING="${SLO_OUTBOX_FINAL_PENDING:-0}"
TEST_CPU_LIMIT_PCT="${TEST_CPU_LIMIT_PCT:-90}"
THREADPOOL_REQUIRE_TOMCAT="${THREADPOOL_REQUIRE_TOMCAT:-false}"
THREADPOOL_CONFIG_RESULT_FILE="${THREADPOOL_CONFIG_RESULT_FILE:-}"
SLO_TPS_MIN_PERCENT="${SLO_TPS_MIN_PERCENT:-95}"
SLO_GC_P99_SECONDS="${SLO_GC_P99_SECONDS:-0.020}"
SLO_GC_MAX_SECONDS="${SLO_GC_MAX_SECONDS:-0.100}"
TEST_HEAP_LIMIT_RATIO="${TEST_HEAP_LIMIT_RATIO:-0.90}"
TEST_MIN_PROCESS_SAMPLES="${TEST_MIN_PROCESS_SAMPLES:-1}"
TEST_MIN_ACTUATOR_SAMPLES="${TEST_MIN_ACTUATOR_SAMPLES:-1}"

fail() {
  echo "PERFORMANCE_GATE_FAIL: $*" >&2
  exit 1
}

validate() {
  case "${PRODUCT_LINE}" in SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) ;; *) fail "unsupported product line ${PRODUCT_LINE}" ;; esac
  case "${TEST_MODE}" in smoke|performance) ;; *) fail "TEST_MODE must be smoke or performance" ;; esac
  case "${EXECUTE}" in true|false) ;; *) fail "EXECUTE must be true or false" ;; esac
  [[ "${STRESS_TARGET_TPS}" =~ ^[1-9][0-9]*$ ]] || fail "STRESS_TARGET_TPS must be positive"
  [[ "${SAMPLE_SECONDS}" =~ ^[1-9][0-9]*$ ]] || fail "SAMPLE_SECONDS must be positive"
  [[ "${STRESS_HOT_SYMBOL_COUNT}" =~ ^[0-9]+$ ]] || fail "STRESS_HOT_SYMBOL_COUNT must be non-negative"
  [[ "${STRESS_HOT_TRAFFIC_PERCENT}" =~ ^[0-9]+$ ]] || fail "STRESS_HOT_TRAFFIC_PERCENT must be non-negative"
  ((STRESS_HOT_TRAFFIC_PERCENT <= 100)) || fail "STRESS_HOT_TRAFFIC_PERCENT must be <= 100"
  case "${STRESS_SCENARIO}" in trade|liquidation) ;; *) fail "STRESS_SCENARIO must be trade or liquidation" ;; esac
  if [[ "${STRESS_SCENARIO}" == "liquidation" && "${PRODUCT_LINE}" != "LINEAR_PERPETUAL" ]]; then
    fail "liquidation scenario currently supports only LINEAR_PERPETUAL"
  fi
  case "${THREADPOOL_REQUIRE_TOMCAT}" in true|false) ;; *) fail "THREADPOOL_REQUIRE_TOMCAT must be true or false" ;; esac
  [[ "${SLO_TPS_MIN_PERCENT}" =~ ^[1-9][0-9]?$|^100$ ]] || fail "SLO_TPS_MIN_PERCENT must be 1..100"
  [[ "${TEST_MIN_PROCESS_SAMPLES}" =~ ^[1-9][0-9]*$ ]] || fail "TEST_MIN_PROCESS_SAMPLES must be positive"
  [[ "${TEST_MIN_ACTUATOR_SAMPLES}" =~ ^[1-9][0-9]*$ ]] || fail "TEST_MIN_ACTUATOR_SAMPLES must be positive"
  if [[ "${TEST_MODE}" == "performance" && "${TEST_PROFILE}" == "local-low" && "${ALLOW_SCALED_PERFORMANCE:-false}" != "true" ]]; then
    fail "local-low only supports TEST_MODE=smoke; set ALLOW_SCALED_PERFORMANCE=true for non-production scaled performance"
  fi
}

write_manifest() {
  mkdir -p "${EVIDENCE_DIR}"
  test_profile_write_manifest "${EVIDENCE_DIR}/environment-manifest.env"
  {
    echo "run_id=${RUN_ID}"
    echo "product_line=${PRODUCT_LINE}"
    echo "test_mode=${TEST_MODE}"
    echo "execute=${EXECUTE}"
    echo "target_tps=${STRESS_TARGET_TPS}"
    echo "stress_hot_symbol_count=${STRESS_HOT_SYMBOL_COUNT}"
    echo "stress_hot_traffic_percent=${STRESS_HOT_TRAFFIC_PERCENT}"
    echo "stress_scenario=${STRESS_SCENARIO}"
    echo "report_file=${REPORT_FILE}"
    echo "slo_order_accept_p99_ms=${SLO_ORDER_ACCEPT_P99_MS}"
    echo "slo_match_result_p99_ms=${SLO_MATCH_RESULT_P99_MS}"
    echo "slo_settlement_p99_ms=${SLO_SETTLEMENT_P99_MS}"
    echo "slo_kafka_final_lag=${SLO_KAFKA_FINAL_LAG}"
    echo "slo_outbox_final_pending=${SLO_OUTBOX_FINAL_PENDING}"
    echo "cpu_limit_pct=${TEST_CPU_LIMIT_PCT}"
    echo "threadpool_require_tomcat=${THREADPOOL_REQUIRE_TOMCAT}"
    echo "slo_tps_min_percent=${SLO_TPS_MIN_PERCENT}"
    echo "slo_gc_p99_seconds=${SLO_GC_P99_SECONDS}"
    echo "slo_gc_max_seconds=${SLO_GC_MAX_SECONDS}"
    echo "heap_limit_ratio=${TEST_HEAP_LIMIT_RATIO}"
    echo "min_process_samples=${TEST_MIN_PROCESS_SAMPLES}"
    echo "min_actuator_samples=${TEST_MIN_ACTUATOR_SAMPLES}"
  } >"${EVIDENCE_DIR}/manifest.env"
  printf '%q ' "$0" "$@" >"${EVIDENCE_DIR}/command-line.txt"
  printf '\n' >>"${EVIDENCE_DIR}/command-line.txt"
}

cleanup_monitor() {
  if [[ -n "${MONITOR_PID}" ]] && kill -0 "${MONITOR_PID}" >/dev/null 2>&1; then
    touch "${STOP_FILE}"
    wait "${MONITOR_PID}" || true
  fi
}
trap cleanup_monitor EXIT

read_metric() {
  local key="$1"
  awk -F= -v key="${key}" '$1 == key {print $2; exit}' "${RESULT_FILE}"
}

assert_metric_at_most() {
  local name="$1" actual="$2" limit="$3"
  [[ -n "${actual}" ]] || fail "missing ${name}"
  awk -v actual="${actual}" -v limit="${limit}" 'BEGIN {exit !(actual <= limit)}' || fail "${name}=${actual} > ${limit}"
}

assert_metric_equal() {
  local name="$1" actual="$2" expected="$3"
  [[ -n "${actual}" ]] || fail "missing ${name}"
  [[ "${actual}" == "${expected}" ]] || fail "${name}=${actual}, expected ${expected}"
}

assert_metric_at_least() {
  local name="$1" actual="$2" limit="$3"
  [[ -n "${actual}" ]] || fail "missing ${name}"
  awk -v actual="${actual}" -v limit="${limit}" 'BEGIN {exit !(actual >= limit)}' || fail "${name}=${actual} < ${limit}"
}

resource_metric() {
  local name="$1" file="$2"
  awk -F= -v name="${name}" '$1 == name {print $2; exit}' "${file}"
}

scan_runtime_failures() {
  local tmp_dir
  if rg -q 'OutOfMemoryError|Java heap space|GC overhead limit|Full GC' "${RUN_LOG}"; then
    fail "runtime failure marker found in ${RUN_LOG}"
  fi
  if [[ -s "${STRESS_TMP_DIR_FILE}" ]]; then
    tmp_dir="$(head -n 1 "${STRESS_TMP_DIR_FILE}")"
    if [[ -d "${tmp_dir}" ]]; then
      while IFS= read -r -d '' file; do
        if rg -qi 'OutOfMemoryError|Java heap space|GC overhead limit|Full GC' "${file}"; then
          fail "runtime failure marker found in ${file}"
        fi
      done < <(find "${tmp_dir}" -type f -name '*.log' -print0)
    fi
  fi
}

parse_stress_report() {
  local report="$1"
  python3 - "${report}" "${RESULT_FILE}" <<'PY'
import re
import sys

report_path, result_path = sys.argv[1:]
text = open(report_path, encoding="utf-8").read()
result = {}

def values(value):
    return [float(item) for item in re.findall(r"-?\d+(?:\.\d+)?", value)]

latency = next((line for line in text.splitlines() if "Account settlement latency ms" in line), "")
for phase in ("open", "close"):
    match = re.search(rf"{phase}=([^;]+)", latency)
    fields = values(match.group(1)) if match else []
    if len(fields) >= 6:
        result[f"{phase}_settlement_p95_ms"] = fields[3]
        result[f"{phase}_settlement_p99_ms"] = fields[4]
        result[f"{phase}_settlement_max_ms"] = fields[5]

throughput = next((line for line in text.splitlines() if "Phase throughput" in line), "")
for phase in ("open", "close"):
    for stage in ("accepted", "matched", "account"):
        match = re.search(rf"{phase}{stage}=([^;]+)", throughput)
        fields = values(match.group(1)) if match else []
        if len(fields) >= 3:
            result[f"{phase}_{stage}_tps"] = fields[2]

lag = []
table = False
for line in text.splitlines():
    if line.startswith("| group | topic |"):
        table = True
        continue
    if table and line.startswith("|---"):
        continue
    if table and line.startswith("|"):
        fields = [item.strip() for item in line.strip("|").split("|")]
        if len(fields) >= 6 and fields[0] != "group":
            try:
                lag.append(float(fields[5]))
            except ValueError:
                pass
    elif table and line and not line.startswith("|"):
        table = False
if lag:
    result["kafka_final_lag_max"] = max(lag)

pending = []
in_backlog = False
for line in text.splitlines():
    if line.startswith("### Trading Outbox 积压峰值"):
        in_backlog = True
        continue
    if in_backlog and line.startswith("### "):
        in_backlog = False
    if in_backlog and line.startswith("|"):
        fields = [item.strip() for item in line.strip("|").split("|")]
        if len(fields) >= 9 and fields[0] not in ("phase", "---"):
            try:
                pending.append(float(fields[8]))
            except ValueError:
                pass
if pending:
    result["outbox_final_pending_max"] = max(pending)

phase = None
for line in text.splitlines():
    if line.startswith("### Open 链路分段延迟"):
        phase = "open"
        continue
    if line.startswith("### Close 链路分段延迟"):
        phase = "close"
        continue
    if line.startswith("### ") and "链路分段延迟" not in line:
        phase = None
    if phase and line.startswith("|") and not line.startswith("| 阶段") and not line.startswith("|---"):
        fields = [item.strip() for item in line.strip("|").split("|")]
        if len(fields) >= 7 and fields[0] not in ("阶段", "---"):
            try:
                p99 = float(fields[5])
            except ValueError:
                continue
            stage = fields[0]
            if "order created" in stage and "ACCEPTED" in stage:
                result[f"{phase}_order_accept_p99_ms"] = p99
            if "match result published" in stage:
                result[f"{phase}_match_result_p99_ms"] = p99
            if "ACCEPTED" in stage and "bilateral settled" in stage:
                result[f"{phase}_settlement_pipeline_p99_ms"] = p99

with open(result_path, "w", encoding="utf-8") as output:
    for key, value in result.items():
        output.write(f"{key}={value}\n")
PY
}

gate_output() {
  local report="$1" log="$2" resource_summary="$3"
  rg -q "Product line .*multi-symbol stress passed|Product line .* passed" "${log}" || fail "business smoke did not pass"
  rg -q "\[funds-reconcile\] OK" "${log}" || fail "fund reconciliation did not pass"
  if rg -q "API 请求失败|funds reconciliation failed|PERFORMANCE_GATE_FAIL" "${log}"; then
    fail "failure marker found in run log"
  fi
  scan_runtime_failures
  if [[ "${TEST_MODE}" == "performance" ]]; then
    [[ -s "${report}" ]] || fail "stress report missing: ${report}"
    parse_stress_report "${report}"
    assert_metric_at_most open_order_accept_p99_ms "$(read_metric open_order_accept_p99_ms)" "${SLO_ORDER_ACCEPT_P99_MS}"
    assert_metric_at_most close_order_accept_p99_ms "$(read_metric close_order_accept_p99_ms)" "${SLO_ORDER_ACCEPT_P99_MS}"
    assert_metric_at_most open_match_result_p99_ms "$(read_metric open_match_result_p99_ms)" "${SLO_MATCH_RESULT_P99_MS}"
    assert_metric_at_most close_match_result_p99_ms "$(read_metric close_match_result_p99_ms)" "${SLO_MATCH_RESULT_P99_MS}"
    assert_metric_at_most open_settlement_p99_ms "$(read_metric open_settlement_p99_ms)" "${SLO_SETTLEMENT_P99_MS}"
    assert_metric_at_most close_settlement_p99_ms "$(read_metric close_settlement_p99_ms)" "${SLO_SETTLEMENT_P99_MS}"
    local tps_floor
    tps_floor="$(awk -v target="${STRESS_TARGET_TPS}" -v percent="${SLO_TPS_MIN_PERCENT}" 'BEGIN {printf "%.6f", target * percent / 100}')"
    assert_metric_at_least open_accepted_tps "$(read_metric open_accepted_tps)" "${tps_floor}"
    assert_metric_at_least close_accepted_tps "$(read_metric close_accepted_tps)" "${tps_floor}"
    assert_metric_at_least open_matched_tps "$(read_metric open_matched_tps)" "${tps_floor}"
    assert_metric_at_least close_matched_tps "$(read_metric close_matched_tps)" "${tps_floor}"
    assert_metric_at_least open_account_tps "$(read_metric open_account_tps)" "${tps_floor}"
    assert_metric_at_least close_account_tps "$(read_metric close_account_tps)" "${tps_floor}"
    assert_metric_equal kafka_final_lag_max "$(read_metric kafka_final_lag_max)" "${SLO_KAFKA_FINAL_LAG}"
    assert_metric_equal outbox_final_pending_max "$(read_metric outbox_final_pending_max)" "${SLO_OUTBOX_FINAL_PENDING}"
  fi
  [[ -s "${resource_summary}" ]] || fail "resource summary missing"
  local cpu
  cpu="$(read_metric max_process_cpu_pct 2>/dev/null || true)"
  if [[ -z "${cpu}" ]]; then
    cpu="$(awk -F= '$1 == "max_process_cpu_pct" {print $2}' "${resource_summary}")"
  fi
  assert_metric_at_most max_process_cpu_pct "${cpu}" "${TEST_CPU_LIMIT_PCT}"
  local process_samples actuator_samples heap_samples heap_ratio gc_p99_samples gc_p99 gc_max
  process_samples="$(resource_metric process_samples "${resource_summary}")"
  actuator_samples="$(resource_metric actuator_cpu_samples "${resource_summary}")"
  heap_samples="$(resource_metric heap_samples "${resource_summary}")"
  heap_ratio="$(resource_metric max_heap_used_ratio "${resource_summary}")"
  gc_p99_samples="$(resource_metric gc_p99_samples "${resource_summary}")"
  gc_p99="$(resource_metric max_gc_pause_p99_seconds "${resource_summary}")"
  gc_max="$(resource_metric max_gc_pause_seconds "${resource_summary}")"
  assert_metric_at_least process_samples "${process_samples}" "${TEST_MIN_PROCESS_SAMPLES}"
  assert_metric_at_least actuator_cpu_samples "${actuator_samples}" "${TEST_MIN_ACTUATOR_SAMPLES}"
  assert_metric_at_least heap_samples "${heap_samples}" 1
  assert_metric_at_most max_heap_used_ratio "${heap_ratio}" "${TEST_HEAP_LIMIT_RATIO}"
  if [[ "${TEST_MODE}" == "performance" ]]; then
    assert_metric_at_least gc_p99_samples "${gc_p99_samples}" 1
    assert_metric_at_most gc_pause_p99_seconds "${gc_p99}" "${SLO_GC_P99_SECONDS}"
    assert_metric_at_most gc_pause_max_seconds "${gc_max}" "${SLO_GC_MAX_SECONDS}"
  fi
  if [[ "${THREADPOOL_REQUIRE_TOMCAT}" == "true" ]]; then
    local tomcat_samples
    tomcat_samples="$(awk -F= '$1 == "tomcat_samples" {print $2}' "${resource_summary}")"
    assert_metric_at_least tomcat_samples "${tomcat_samples}" 1
    [[ -r "${THREADPOOL_CONFIG_RESULT_FILE}" ]] || fail "THREADPOOL_CONFIG_RESULT_FILE is required"
    rg -q '^tomcat_config_bound=PASS$' "${THREADPOOL_CONFIG_RESULT_FILE}" || fail "Tomcat config binding evidence missing"
    rg -q "^max_threads=${SERVER_TOMCAT_THREADS_MAX:-}$" "${THREADPOOL_CONFIG_RESULT_FILE}" || fail "Tomcat maxThreads binding mismatch"
    rg -q "^min_spare=${SERVER_TOMCAT_THREADS_MIN_SPARE:-}$" "${THREADPOOL_CONFIG_RESULT_FILE}" || fail "Tomcat minSpare binding mismatch"
    rg -q "^accept_count=${SERVER_TOMCAT_ACCEPT_COUNT:-}$" "${THREADPOOL_CONFIG_RESULT_FILE}" || fail "Tomcat acceptCount binding mismatch"
    rg -q "^max_connections=${SERVER_TOMCAT_MAX_CONNECTIONS:-}$" "${THREADPOOL_CONFIG_RESULT_FILE}" || fail "Tomcat maxConnections binding mismatch"
  fi
}

validate
write_manifest "$@"

if [[ "${EXECUTE}" != "true" ]]; then
  if [[ "${TEST_MODE}" == "smoke" ]]; then
    echo "DRY_RUN PLAN_ONLY: ${PRODUCT_LINE} smoke; profile=${TEST_PROFILE}; services=$(test_profile_services "${PRODUCT_LINE}" trade)"
  else
    echo "DRY_RUN PLAN_ONLY: ${PRODUCT_LINE} performance target=${STRESS_TARGET_TPS}tps profile=${TEST_PROFILE}"
  fi
  exit 0
fi

rm -f "${STOP_FILE}"
EVIDENCE_DIR="${EVIDENCE_DIR}" STOP_FILE="${STOP_FILE}" SAMPLE_SECONDS="${SAMPLE_SECONDS}" \
  "${ROOT_DIR}/scripts/production-resource-monitor.sh" >"${EVIDENCE_DIR}/resource-monitor.log" 2>&1 &
MONITOR_PID="$!"

set +e
if [[ "${TEST_MODE}" == "performance" ]]; then
  PRODUCT_LINES="${PRODUCT_LINE}" MULTI_SYMBOL_STRESS=true \
    STRESS_TARGET_TPS="${STRESS_TARGET_TPS}" STRESS_SYMBOL_COUNT="${STRESS_SYMBOL_COUNT}" \
    STRESS_USER_COUNT="${STRESS_USER_COUNT}" STRESS_LOAD_CONCURRENCY="${STRESS_LOAD_CONCURRENCY}" \
    STRESS_HOT_SYMBOL_COUNT="${STRESS_HOT_SYMBOL_COUNT}" STRESS_HOT_TRAFFIC_PERCENT="${STRESS_HOT_TRAFFIC_PERCENT}" \
    STRESS_SCENARIO="${STRESS_SCENARIO}" \
    STRESS_TMP_DIR_FILE="${STRESS_TMP_DIR_FILE}" \
    STRESS_REPORT_FILE="${REPORT_FILE}" STRESS_RUN_LABEL="production-${RUN_ID}" \
    RESET_KAFKA=true CREATE_KAFKA_TOPICS=true KAFKA_RESET_SHARED_TOPICS=true \
    KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false BUILD_SERVICES=auto KEEP_TMP=true RECONCILE_FUNDS=true \
    "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh" 2>&1 | tee "${RUN_LOG}"
else
  PRODUCT_LINES="${PRODUCT_LINE}" MULTI_SYMBOL_STRESS=false \
    STRESS_TMP_DIR_FILE="${STRESS_TMP_DIR_FILE}" \
    RESET_KAFKA=true CREATE_KAFKA_TOPICS=true KAFKA_RESET_SHARED_TOPICS=true \
    KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false BUILD_SERVICES=auto KEEP_TMP=true RECONCILE_FUNDS=true \
    "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh" 2>&1 | tee "${RUN_LOG}"
fi
smoke_status=${PIPESTATUS[0]}
set -e
cleanup_monitor
MONITOR_PID=""
((smoke_status == 0)) || fail "product line smoke exited ${smoke_status}"
gate_output "${REPORT_FILE}" "${RUN_LOG}" "${EVIDENCE_DIR}/resource-summary.env"
echo "PERFORMANCE_GATE PASS evidence=${EVIDENCE_DIR}"
