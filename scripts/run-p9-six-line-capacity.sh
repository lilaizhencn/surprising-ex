#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="${P9_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUTPUT_DIR="${P9_OUTPUT_DIR:-${ROOT_DIR}/reports/capacity/${RUN_ID}}"
PRODUCT_LINES="${PRODUCT_LINES:-LINEAR_PERPETUAL SPOT INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION}"
START_OPS="${START_OPS:-40}"
STEP_OPS="${STEP_OPS:-20}"
MAX_OPS="${MAX_OPS:-240}"
STEP_SECONDS="${STEP_SECONDS:-30}"
SOAK_SECONDS="${SOAK_SECONDS:-300}"
SOAK_FAILOVER_AFTER_SECONDS="${SOAK_FAILOVER_AFTER_SECONDS:-60}"
WORKERS="${WORKERS:-8}"
CONNECTIONS="${CONNECTIONS:-8}"
SKIP_BUILD="${SKIP_BUILD:-false}"
RUN_END_TO_END="${RUN_END_TO_END:-true}"
RUN_FUNCTIONAL_GATE="${RUN_FUNCTIONAL_GATE:-true}"
RESUME_CAPACITY_CASES="${RESUME_CAPACITY_CASES:-false}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
PATH="${JAVA_HOME}/bin:/opt/homebrew/opt/libpq/bin:${ROOT_DIR}/scripts/docker-kafka-bin:${PATH}"
export JAVA_HOME PATH
SERVICE_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
TOOLS_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
fail() {
  echo "P9_CAPACITY_FAIL: $*" >&2
  exit 1
}
for value in START_OPS STEP_OPS MAX_OPS STEP_SECONDS SOAK_SECONDS SOAK_FAILOVER_AFTER_SECONDS WORKERS CONNECTIONS; do
  [[ "${!value}" =~ ^[1-9][0-9]*$ ]] || fail "${value} must be positive"
done
((SOAK_FAILOVER_AFTER_SECONDS < SOAK_SECONDS)) \
  || fail "SOAK_FAILOVER_AFTER_SECONDS must be lower than SOAK_SECONDS"

case "${SKIP_BUILD}" in true|false) ;; *) fail "SKIP_BUILD must be true or false" ;; esac
case "${RUN_END_TO_END}" in true|false) ;; *) fail "RUN_END_TO_END must be true or false" ;; esac
case "${RUN_FUNCTIONAL_GATE}" in true|false) ;; *) fail "RUN_FUNCTIONAL_GATE must be true or false" ;; esac
case "${RESUME_CAPACITY_CASES}" in true|false) ;; *) fail "RESUME_CAPACITY_CASES must be true or false" ;; esac

"${ROOT_DIR}/scripts/check-aeron-test-architecture.sh"

run_case() {
  local product_line="$1" case_name="$2" workload="$3" symbol_count="$4"
  local start_ops="$5" step_ops="$6" max_ops="$7" seconds="$8" assessment="$9" recovery="${10}"
  local lifecycle="${11:-false}" failover_after="${12:-0}"
  if [[ "${RESUME_CAPACITY_CASES}" == true \
      && -s "${OUTPUT_DIR}/${product_line}/${case_name}/summary.env" ]]; then
    echo "Reusing completed capacity case: ${product_line}/${case_name}"
    return
  fi
  PRODUCT_LINE="${product_line}" CAPACITY_OUTPUT_DIR="${OUTPUT_DIR}/${product_line}/${case_name}" \
    SCENARIO="${case_name}" WORKLOAD="${workload}" SYMBOL_COUNT="${symbol_count}" \
    START_OPS="${start_ops}" STEP_OPS="${step_ops}" MAX_OPS="${max_ops}" STEP_SECONDS="${seconds}" \
    WORKERS="${WORKERS}" CONNECTIONS="${CONNECTIONS}" ASSESSMENT_MODE="${assessment}" \
    RECOVERY_GATE="${recovery}" LIFECYCLE_GATE="${lifecycle}" \
    LEADER_FAILOVER_AFTER_SECONDS="${failover_after}" \
    SKIP_BUILD=true "${ROOT_DIR}/scripts/run-uncapped-aeron-capacity.sh"
}

last_pass() {
  awk -F= '$1 == "stable_last_pass_offered_ops" {print $2; exit}' "$1"
}

run_end_to_end() {
  local product_line="$1" stable="$2" scenario="$3" target users evidence_dir
  target="${stable}"
  users=1000
  if [[ "${scenario}" == liquidation ]]; then
    ((target > 20)) && target=20
    users=100
  fi
  evidence_dir="${OUTPUT_DIR}/${product_line}/end-to-end-${scenario}"
  if ! PRODUCT_LINE="${product_line}" TEST_MODE=performance EXECUTE=true TEST_PROFILE=local-standard \
    DB_USER="${DB_USER:-postgres}" DB_PASSWORD="${DB_PASSWORD:-postgres}" DB_NAME="${DB_NAME:-postgres}" \
    POSTGRES_PORT="${POSTGRES_PORT:-5432}" \
    KAFKA_TOPIC_RESET_TIMEOUT_SECONDS="${KAFKA_TOPIC_RESET_TIMEOUT_SECONDS:-180}" \
    ALLOW_SCALED_PERFORMANCE=true EVIDENCE_DIR="${evidence_dir}" \
    MANAGE_AERON_RUNTIME=true \
    STRESS_TARGET_TPS="${target}" STRESS_SYMBOL_COUNT=20 STRESS_USER_COUNT="${users}" \
    STRESS_LOAD_CONCURRENCY=16 STRESS_HOT_SYMBOL_COUNT=3 STRESS_HOT_TRAFFIC_PERCENT=80 \
    STRESS_SCENARIO="${scenario}" "${ROOT_DIR}/scripts/production-performance-gate.sh"; then
    return 1
  fi
}

mkdir -p "${OUTPUT_DIR}"
if [[ ! -s "${OUTPUT_DIR}/index.md" ]]; then
  {
    echo "# P9 six-line local capacity"
    echo
    echo "LOCAL_CAPACITY only; shared Apple development machine, not production capacity."
    echo
    echo '| Product line | Stable offered commands/s | MATCH | hot3 | CANCEL | 2x burst | 5m soak | Recovery | Funds diff |'
    echo '|---|---:|---|---|---|---|---|---|---:|'
  } >"${OUTPUT_DIR}/index.md"
fi

if [[ "${SKIP_BUILD}" != true ]]; then
  mvn -q -f "${ROOT_DIR}/pom.xml" \
    -pl :surprising-aeron-service,:surprising-aeron-tools,:surprising-aeron-exporter -am -DskipTests package
fi

for product_line in ${PRODUCT_LINES}; do
  line_dir="${OUTPUT_DIR}/${product_line}"
  if [[ -s "${line_dir}/PASS.env" ]]; then
    continue
  fi
  mkdir -p "${line_dir}"

  if [[ "${RUN_FUNCTIONAL_GATE}" == true ]]; then
    PRODUCT_LINES="${product_line}" ACCEPTANCE_OUTPUT_DIR="${line_dir}/functional-acceptance" \
      SKIP_BUILD=true "${ROOT_DIR}/scripts/run-six-product-line-acceptance.sh"
  fi
  if [[ "${RUN_END_TO_END}" == true ]]; then
    run_end_to_end "${product_line}" "${START_OPS}" trade
  fi

  run_case "${product_line}" capacity-step MATCH 1 \
    "${START_OPS}" "${STEP_OPS}" "${MAX_OPS}" "${STEP_SECONDS}" strict false
  match_stable="$(last_pass "${line_dir}/capacity-step/summary.env")"

  run_case "${product_line}" hot3 MATCH 3 \
    "${START_OPS}" "${STEP_OPS}" "${match_stable}" "${STEP_SECONDS}" strict false
  hot3_stable="$(last_pass "${line_dir}/hot3/summary.env")"

  run_case "${product_line}" cancel-heavy CANCEL 1 \
    "${START_OPS}" "${STEP_OPS}" "${hot3_stable}" "${STEP_SECONDS}" strict false
  stable="$(last_pass "${line_dir}/cancel-heavy/summary.env")"
  burst=$((stable * 2))

  run_case "${product_line}" burst MATCH 1 \
    "${burst}" "${burst}" "${burst}" "${STEP_SECONDS}" observe false
  lifecycle=false
  [[ "${product_line}" != SPOT ]] && lifecycle=true
  run_case "${product_line}" soak MATCH 3 \
    "${stable}" "${stable}" "${stable}" "${SOAK_SECONDS}" strict true "${lifecycle}" \
    "${SOAK_FAILOVER_AFTER_SECONDS}"

  [[ -s "${line_dir}/soak/recovery.env" ]] || fail "missing recovery evidence for ${product_line}"
  rg -q '^recovery=PASS$' "${line_dir}/soak/recovery.env" || fail "recovery failed for ${product_line}"
  rg -q '^projection_lag=0$' "${line_dir}/soak/recovery.env" || fail "projection lag for ${product_line}"
  rg -q '^funds_diff=0$' "${line_dir}/soak/recovery.env" || fail "funds mismatch for ${product_line}"
  rg -q '^leader_failover_during_load=PASS$' "${line_dir}/soak/leader-failover-during-load.env" \
    || fail "in-load leader failover failed for ${product_line}"
  if [[ "${product_line}" != SPOT ]]; then
    rg -q 'lifecycleCapacity=PASS.*fundsDiff=0' "${line_dir}/soak/lifecycle-capacity.txt" \
      || fail "lifecycle capacity failed for ${product_line}"
  fi
  {
    echo "product_line=${product_line}"
    echo "scope=LOCAL_CAPACITY"
    echo "stable_offered_commands_per_second=${stable}"
    echo "funds_diff=0"
    echo "recovery=PASS"
    echo "end_to_end=${RUN_END_TO_END}"
  } >"${line_dir}/PASS.env"
  echo "| ${product_line} | ${stable} | PASS | PASS | PASS | OBSERVED | PASS | PASS | 0 |" >>"${OUTPUT_DIR}/index.md"
done

(cd "${OUTPUT_DIR}" && find . -type f \( -name '*.md' -o -name '*.env' -o -name '*.txt' \) \
  ! -name SHA256SUMS | LC_ALL=C sort | xargs shasum -a 256 >SHA256SUMS)
echo "p9-capacity=PASS scope=LOCAL_CAPACITY funds-diff=0 evidence=${OUTPUT_DIR}"
