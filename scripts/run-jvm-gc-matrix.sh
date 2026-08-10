#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
GC_VALUES="${GC_VALUES:-g1 zgc}"
MATRIX_REPEATS_EXPLICIT="${MATRIX_REPEATS+x}"
MATRIX_REPEATS="${MATRIX_REPEATS:-3}"
MATRIX_EXECUTE="${MATRIX_EXECUTE:-false}"
MATRIX_OUTPUT_DIR="${MATRIX_OUTPUT_DIR:-/tmp/surprising-jvm-gc-matrix-$(date -u +%Y%m%dT%H%M%SZ)}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect

fail() { echo "GC_MATRIX_FAIL: $*" >&2; exit 1; }

case "${MATRIX_EXECUTE}" in true|false) ;; *) fail "MATRIX_EXECUTE must be true or false" ;; esac
[[ "${MATRIX_REPEATS}" =~ ^[1-9][0-9]*$ ]] || fail "MATRIX_REPEATS must be positive"
case "${PRODUCT_LINE}" in SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) ;; *) fail "unsupported product line ${PRODUCT_LINE}" ;; esac

if [[ -z "${MATRIX_REPEATS_EXPLICIT}" ]]; then
  case "${TEST_PROFILE}" in
    local-low) MATRIX_REPEATS=1 ;;
    local-standard|cloud-capacity|cloud-production) MATRIX_REPEATS=3 ;;
  esac
fi

for gc in ${GC_VALUES}; do
  case "${gc}" in g1|zgc|parallel) ;; *) fail "unsupported GC=${gc}" ;; esac
done

mkdir -p "${MATRIX_OUTPUT_DIR}"
test_profile_write_manifest "${MATRIX_OUTPUT_DIR}/environment-manifest.env"
{
  echo "# JVM GC matrix"
  echo
  echo "product_line=${PRODUCT_LINE}"
  echo "profile=${TEST_PROFILE}"
  echo "cpu=${TEST_CPU_COUNT} memory_mb=${TEST_MEMORY_MB}"
  echo "gc_values=${GC_VALUES} repeats=${MATRIX_REPEATS} execute=${MATRIX_EXECUTE}"
  echo
  echo "| gc | repeat | mode | result | evidence |"
  echo "|---|---:|---|---|---|"
} >"${MATRIX_OUTPUT_DIR}/index.md"

for gc in ${GC_VALUES}; do
  for ((repeat = 1; repeat <= MATRIX_REPEATS; repeat++)); do
    evidence="${MATRIX_OUTPUT_DIR}/${PRODUCT_LINE}-${gc}-r${repeat}"
    mkdir -p "${evidence}/jfr"
    if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
      [[ "${TEST_PROFILE}" != "local-low" || "${ALLOW_SCALED_PERFORMANCE:-false}" == "true" ]] || fail "local-low GC comparison requires ALLOW_SCALED_PERFORMANCE=true"
      set +e
      PRODUCT_LINE="${PRODUCT_LINE}" TEST_PROFILE="${TEST_PROFILE}" TEST_JVM_GC="${gc}" \
        TEST_JFR_DIR="${evidence}/jfr" TEST_MODE=performance EXECUTE=true \
        EVIDENCE_DIR="${evidence}" STRESS_TARGET_TPS="${TEST_TARGET_TPS}" \
        STRESS_SYMBOL_COUNT="${TEST_STRESS_SYMBOL_COUNT}" STRESS_USER_COUNT="${TEST_STRESS_USER_COUNT}" \
        STRESS_LOAD_CONCURRENCY="${TEST_STRESS_LOAD_CONCURRENCY}" \
        "${ROOT_DIR}/scripts/production-performance-gate.sh" >"${evidence}/gate.log" 2>&1
      rc=$?
      set -e
      if ((rc == 0)); then
        echo "| ${gc} | ${repeat} | performance | PASS | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
      else
        echo "| ${gc} | ${repeat} | performance | FAIL | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
        exit "${rc}"
      fi
    else
      mode=performance
      result=DRY_RUN
      if [[ "${TEST_PROFILE}" == "local-low" ]]; then
        mode=smoke-only
        result=PLANNED_ONLY
      fi
      PRODUCT_LINE="${PRODUCT_LINE}" TEST_PROFILE="${TEST_PROFILE}" TEST_JVM_GC="${gc}" \
        TEST_MODE=smoke EXECUTE=false EVIDENCE_DIR="${evidence}" \
        STRESS_TARGET_TPS="${TEST_TARGET_TPS}" "${ROOT_DIR}/scripts/production-performance-gate.sh" >"${evidence}/gate.log"
      echo "| ${gc} | ${repeat} | ${mode} | ${result} | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
    fi
  done
done

if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
  echo "GC_MATRIX PASS index=${MATRIX_OUTPUT_DIR}/index.md"
else
  echo "GC_MATRIX DRY_RUN index=${MATRIX_OUTPUT_DIR}/index.md"
fi
