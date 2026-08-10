#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINES="${PRODUCT_LINES:-${PRODUCT_LINE:-LINEAR_PERPETUAL}}"
MATRIX_CASES="${MATRIX_CASES:-auto}"
MATRIX_REPEATS_EXPLICIT="${MATRIX_REPEATS+x}"
MATRIX_REPEATS="${MATRIX_REPEATS:-3}"
MATRIX_EXECUTE="${MATRIX_EXECUTE:-false}"
MATRIX_OUTPUT_DIR="${MATRIX_OUTPUT_DIR:-/tmp/surprising-product-line-matrix-$(date -u +%Y%m%dT%H%M%SZ)}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect

if [[ -z "${MATRIX_REPEATS_EXPLICIT}" ]]; then
  case "${TEST_PROFILE}" in
    local-low) MATRIX_REPEATS=1 ;;
    local-standard|cloud-capacity|cloud-production) MATRIX_REPEATS=3 ;;
  esac
fi

fail() { echo "MATRIX_FAIL: $*" >&2; exit 1; }

case "${MATRIX_EXECUTE}" in true|false) ;; *) fail "MATRIX_EXECUTE must be true or false" ;; esac
[[ "${MATRIX_REPEATS}" =~ ^[1-9][0-9]*$ ]] || fail "MATRIX_REPEATS must be positive"

if [[ "${MATRIX_CASES}" == "auto" ]]; then
  case "${TEST_PROFILE}" in
    local-low) MATRIX_CASES="smoke" ;;
    local-standard) MATRIX_CASES="smoke baseline" ;;
    cloud-capacity) MATRIX_CASES="smoke baseline capacity hot1 hot3 burst soak" ;;
    cloud-production) MATRIX_CASES="smoke baseline capacity hot1 hot3 burst soak liquidation" ;;
  esac
fi

case_values() {
  local name="$1"
  case "${name}" in
    smoke) echo "smoke 1 100 1 80 trade 1" ;;
    baseline) echo "performance 20 1000 0 80 trade 1" ;;
    capacity) echo "performance 20 5000 0 80 trade 1" ;;
    hot1) echo "performance 20 5000 1 80 trade 1" ;;
    hot3) echo "performance 20 5000 3 80 trade 1" ;;
    burst) echo "performance 20 5000 1 80 trade 2" ;;
    soak) echo "performance 20 1000 0 80 trade 1" ;;
    liquidation) echo "performance 20 5000 0 80 liquidation 1" ;;
    *) fail "unknown MATRIX_CASE=${name}" ;;
  esac
}

mkdir -p "${MATRIX_OUTPUT_DIR}"
{
  echo "# Product line performance matrix"
  echo
  echo "profile=${TEST_PROFILE}"
  echo "cpu=${TEST_CPU_COUNT} memory_mb=${TEST_MEMORY_MB}"
  echo "execute=${MATRIX_EXECUTE} repeats=${MATRIX_REPEATS}"
  echo
  echo "| product line | case | repeat | result | evidence |"
  echo "|---|---|---:|---|---|"
} >"${MATRIX_OUTPUT_DIR}/index.md"

for product_line in ${PRODUCT_LINES}; do
  for matrix_case in ${MATRIX_CASES}; do
    read -r test_mode symbols users hot_symbols hot_percent scenario tps_multiplier < <(case_values "${matrix_case}")
    case_target_tps=$((TEST_TARGET_TPS * tps_multiplier))
    if [[ "${TEST_PROFILE}" == "local-low" && "${matrix_case}" != "smoke" ]]; then
      fail "${TEST_PROFILE} cannot run ${matrix_case}; use MATRIX_CASES=smoke or a stronger environment"
    fi
    for ((repeat = 1; repeat <= MATRIX_REPEATS; repeat++)); do
      label="${product_line}-${matrix_case}-r${repeat}"
      evidence="${MATRIX_OUTPUT_DIR}/${label}"
      mkdir -p "${evidence}"
      if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
        set +e
        PRODUCT_LINE="${product_line}" TEST_MODE="${test_mode}" EXECUTE=true \
          TEST_PROFILE="${TEST_PROFILE}" EVIDENCE_DIR="${evidence}" \
          STRESS_SYMBOL_COUNT="${symbols}" STRESS_USER_COUNT="${users}" \
          STRESS_HOT_SYMBOL_COUNT="${hot_symbols}" STRESS_HOT_TRAFFIC_PERCENT="${hot_percent}" \
          STRESS_SCENARIO="${scenario}" \
          STRESS_TARGET_TPS="${case_target_tps}" \
          "${ROOT_DIR}/scripts/production-performance-gate.sh" >"${evidence}/gate.log" 2>&1
        status=$?
        set -e
        if ((status == 0)); then
          echo "| ${product_line} | ${matrix_case} | ${repeat} | PASS | ${label} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
        else
          echo "| ${product_line} | ${matrix_case} | ${repeat} | FAIL | ${label} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
          exit "${status}"
        fi
      else
        echo "| ${product_line} | ${matrix_case} | ${repeat} | DRY_RUN | ${label} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
        PRODUCT_LINE="${product_line}" TEST_MODE="${test_mode}" EXECUTE=false \
          TEST_PROFILE="${TEST_PROFILE}" EVIDENCE_DIR="${evidence}" \
          STRESS_SYMBOL_COUNT="${symbols}" STRESS_USER_COUNT="${users}" \
          STRESS_HOT_SYMBOL_COUNT="${hot_symbols}" STRESS_HOT_TRAFFIC_PERCENT="${hot_percent}" \
          STRESS_SCENARIO="${scenario}" \
          STRESS_TARGET_TPS="${case_target_tps}" \
          "${ROOT_DIR}/scripts/production-performance-gate.sh" >"${evidence}/gate.log"
      fi
    done
  done
done

if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
  echo "MATRIX PASS index=${MATRIX_OUTPUT_DIR}/index.md"
else
  echo "MATRIX DRY_RUN index=${MATRIX_OUTPUT_DIR}/index.md"
fi
