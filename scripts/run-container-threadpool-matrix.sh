#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
MATRIX_EXECUTE="${MATRIX_EXECUTE:-false}"
MATRIX_REPEATS_EXPLICIT="${MATRIX_REPEATS+x}"
MATRIX_REPEATS="${MATRIX_REPEATS:-1}"
MATRIX_OUTPUT_DIR="${MATRIX_OUTPUT_DIR:-/tmp/surprising-threadpool-matrix-$(date -u +%Y%m%dT%H%M%SZ)}"
THREADPOOL_REQUIRE_TOMCAT="${THREADPOOL_REQUIRE_TOMCAT:-true}"
THREADPOOL_CONFIG_RESULT_FILE="${THREADPOOL_CONFIG_RESULT_FILE:-}"
TOMCAT_CASES="${TOMCAT_CASES:-auto}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect

fail() { echo "THREADPOOL_MATRIX_FAIL: $*" >&2; exit 1; }

case "${MATRIX_EXECUTE}" in true|false) ;; *) fail "MATRIX_EXECUTE must be true or false" ;; esac
case "${THREADPOOL_REQUIRE_TOMCAT}" in true|false) ;; *) fail "THREADPOOL_REQUIRE_TOMCAT must be true or false" ;; esac
case "${PRODUCT_LINE}" in SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) ;; *) fail "unsupported product line ${PRODUCT_LINE}" ;; esac

if [[ -z "${MATRIX_REPEATS_EXPLICIT}" ]]; then
  case "${TEST_PROFILE}" in
    local-low) MATRIX_REPEATS=1 ;;
    local-standard) MATRIX_REPEATS=2 ;;
    cloud-capacity|cloud-production) MATRIX_REPEATS=3 ;;
  esac
fi
[[ "${MATRIX_REPEATS}" =~ ^[1-9][0-9]*$ ]] || fail "MATRIX_REPEATS must be positive"

if [[ "${TOMCAT_CASES}" == "auto" ]]; then
  case "${TEST_PROFILE}" in
    local-low) TOMCAT_CASES="100:20:1000:2000" ;;
    local-standard) TOMCAT_CASES="100:20:1000:2000 200:50:5000:4000 400:100:5000:8000" ;;
    cloud-capacity|cloud-production) TOMCAT_CASES="100:20:1000:4000 200:50:5000:8000 400:100:10000:16000" ;;
  esac
fi

mkdir -p "${MATRIX_OUTPUT_DIR}"
test_profile_write_manifest "${MATRIX_OUTPUT_DIR}/environment-manifest.env"
{
  echo "# Tomcat/thread pool matrix"
  echo
  echo "product_line=${PRODUCT_LINE}"
  echo "profile=${TEST_PROFILE}"
  echo "cases=${TOMCAT_CASES} repeats=${MATRIX_REPEATS} execute=${MATRIX_EXECUTE}"
  echo "threadpool_require_tomcat=${THREADPOOL_REQUIRE_TOMCAT}"
  echo
  echo "| maxThreads | minSpare | acceptCount | maxConnections | repeat | result | evidence |"
  echo "|---:|---:|---:|---:|---:|---|---|"
} >"${MATRIX_OUTPUT_DIR}/index.md"

for tomcat_case in ${TOMCAT_CASES}; do
  IFS=: read -r max_threads min_spare accept_count max_connections <<<"${tomcat_case}"
  for value in "${max_threads}" "${min_spare}" "${accept_count}" "${max_connections}"; do
    [[ "${value}" =~ ^[1-9][0-9]*$ ]] || fail "invalid Tomcat case ${tomcat_case}"
  done
  for ((repeat = 1; repeat <= MATRIX_REPEATS; repeat++)); do
    evidence="${MATRIX_OUTPUT_DIR}/tomcat-${max_threads}-${min_spare}-${accept_count}-${max_connections}-r${repeat}"
    mkdir -p "${evidence}"
    if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
      [[ "${TEST_PROFILE}" != "local-low" || "${ALLOW_SCALED_PERFORMANCE:-false}" == "true" ]] || fail "local-low thread pool comparison requires ALLOW_SCALED_PERFORMANCE=true"
      set +e
      PRODUCT_LINE="${PRODUCT_LINE}" TEST_PROFILE="${TEST_PROFILE}" TEST_MODE=performance EXECUTE=true \
        EVIDENCE_DIR="${evidence}" STRESS_TARGET_TPS="${TEST_TARGET_TPS}" \
        SERVER_TOMCAT_THREADS_MAX="${max_threads}" SERVER_TOMCAT_THREADS_MIN_SPARE="${min_spare}" \
        SERVER_TOMCAT_ACCEPT_COUNT="${accept_count}" SERVER_TOMCAT_MAX_CONNECTIONS="${max_connections}" \
        THREADPOOL_REQUIRE_TOMCAT="${THREADPOOL_REQUIRE_TOMCAT}" \
        THREADPOOL_CONFIG_RESULT_FILE="${THREADPOOL_CONFIG_RESULT_FILE}" \
        "${ROOT_DIR}/scripts/production-performance-gate.sh" >"${evidence}/gate.log" 2>&1
      rc=$?
      set -e
      if ((rc == 0)); then
        echo "| ${max_threads} | ${min_spare} | ${accept_count} | ${max_connections} | ${repeat} | PASS | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
      else
        echo "| ${max_threads} | ${min_spare} | ${accept_count} | ${max_connections} | ${repeat} | FAIL | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
        exit "${rc}"
      fi
    else
      PRODUCT_LINE="${PRODUCT_LINE}" TEST_PROFILE="${TEST_PROFILE}" TEST_MODE=smoke EXECUTE=false \
        EVIDENCE_DIR="${evidence}" STRESS_TARGET_TPS="${TEST_TARGET_TPS}" \
        SERVER_TOMCAT_THREADS_MAX="${max_threads}" SERVER_TOMCAT_THREADS_MIN_SPARE="${min_spare}" \
        SERVER_TOMCAT_ACCEPT_COUNT="${accept_count}" SERVER_TOMCAT_MAX_CONNECTIONS="${max_connections}" \
        THREADPOOL_REQUIRE_TOMCAT="${THREADPOOL_REQUIRE_TOMCAT}" \
        THREADPOOL_CONFIG_RESULT_FILE="${THREADPOOL_CONFIG_RESULT_FILE}" \
        "${ROOT_DIR}/scripts/production-performance-gate.sh" >"${evidence}/gate.log"
      echo "| ${max_threads} | ${min_spare} | ${accept_count} | ${max_connections} | ${repeat} | DRY_RUN | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
    fi
  done
done

if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
  echo "THREADPOOL_MATRIX PASS index=${MATRIX_OUTPUT_DIR}/index.md"
else
  echo "THREADPOOL_MATRIX DRY_RUN index=${MATRIX_OUTPUT_DIR}/index.md"
fi
