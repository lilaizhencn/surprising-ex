#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
MATRIX_EXECUTE="${MATRIX_EXECUTE:-false}"
MATRIX_OUTPUT_DIR="${MATRIX_OUTPUT_DIR:-/tmp/surprising-recovery-matrix-$(date -u +%Y%m%dT%H%M%SZ)}"
RECOVERY_CASES="${RECOVERY_CASES:-auto}"
RECOVERY_RESULT_FILE="${RECOVERY_RESULT_FILE:-}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect

fail() { echo "RECOVERY_MATRIX_FAIL: $*" >&2; exit 1; }

case "${MATRIX_EXECUTE}" in true|false) ;; *) fail "MATRIX_EXECUTE must be true or false" ;; esac
case "${PRODUCT_LINE}" in SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) ;; *) fail "unsupported product line ${PRODUCT_LINE}" ;; esac

if [[ "${RECOVERY_CASES}" == "auto" ]]; then
  case "${TEST_PROFILE}" in
    local-low) RECOVERY_CASES="account:kill" ;;
    local-standard) RECOVERY_CASES="account:kill matching:kill" ;;
    cloud-capacity) RECOVERY_CASES="account:kill matching:kill margin-ops:kill edge:term" ;;
    cloud-production) RECOVERY_CASES="account:kill matching:kill margin-ops:kill edge:term price:term" ;;
  esac
fi

validate_recovery_result() {
  local evidence="$1" result_file="${RECOVERY_RESULT_FILE}" required rto
  [[ -r "${result_file}" ]] || return 1
  for required in rpo_events=0 kafka_final_lag=0 outbox_final_pending=0 funds_difference=0 state_equivalent=PASS wal_replay=PASS duplicate_facts=0; do
    rg -q "^${required}$" "${result_file}" || return 1
  done
  rto="$(awk -F= '$1 == "recovery_rto_ms" {print $2; exit}' "${result_file}")"
  [[ "${rto}" =~ ^[0-9]+$ ]] && ((rto <= 300000)) || return 1
  [[ -s "${evidence}/recovery-timeline.tsv" ]] || return 1
}

mkdir -p "${MATRIX_OUTPUT_DIR}"
test_profile_write_manifest "${MATRIX_OUTPUT_DIR}/environment-manifest.env"
{
  echo "# Product line recovery matrix"
  echo
  echo "product_line=${PRODUCT_LINE}"
  echo "profile=${TEST_PROFILE}"
  echo "cases=${RECOVERY_CASES} execute=${MATRIX_EXECUTE}"
  echo
  echo "| provider | mode | result | evidence |"
  echo "|---|---|---|---|"
} >"${MATRIX_OUTPUT_DIR}/index.md"

for recovery_case in ${RECOVERY_CASES}; do
  IFS=: read -r provider mode <<<"${recovery_case}"
  case "${provider}" in account|matching|margin-ops|price|trading-entry|edge) ;; *) fail "unsupported recovery provider ${provider}" ;; esac
  case "${mode}" in kill|term) ;; *) fail "unsupported recovery mode ${mode}" ;; esac
  if [[ "${provider}" == "margin-ops" && "${PRODUCT_LINE}" == "SPOT" ]]; then
    echo "| ${provider} | ${mode} | SKIPPED_PRODUCT_LINE | - |" >>"${MATRIX_OUTPUT_DIR}/index.md"
    continue
  fi
  evidence="${MATRIX_OUTPUT_DIR}/${PRODUCT_LINE}-${provider}-${mode}"
  mkdir -p "${evidence}"
  {
    echo "product_line=${PRODUCT_LINE}"
    echo "profile=${TEST_PROFILE}"
    echo "recovery_provider=${provider}"
    echo "recovery_mode=${mode}"
    echo "execute=${MATRIX_EXECUTE}"
    echo "funds_reconcile=true"
    echo "recovery_result_file=${RECOVERY_RESULT_FILE:-required-for-execute}"
  } >"${evidence}/manifest.env"
  if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
    set +e
    PRODUCT_LINES="${PRODUCT_LINE}" TEST_PROFILE="${TEST_PROFILE}" BUILD_SERVICES=auto \
      KEEP_TMP=true RESET_KAFKA=true CREATE_KAFKA_TOPICS=true KAFKA_RESET_SHARED_TOPICS=true \
      KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false RECONCILE_FUNDS=true \
      RECOVERY_PROVIDER="${provider}" RECOVERY_MODE="${mode}" ACCOUNT_RESTART_RECOVERY=false \
      RECOVERY_RESULT_FILE="${RECOVERY_RESULT_FILE}" RECOVERY_TIMELINE_FILE="${evidence}/recovery-timeline.tsv" \
      RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$" \
      "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh" >"${evidence}/recovery.log" 2>&1
    rc=$?
    set -e
    if ((rc == 0)) && validate_recovery_result "${evidence}"; then
      echo "| ${provider} | ${mode} | PASS | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
    else
      echo "| ${provider} | ${mode} | FAIL | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
      failure_rc="${rc}"
      ((failure_rc == 0)) && failure_rc=1
      exit "${failure_rc}"
    fi
  else
    {
      printf '%q ' PRODUCT_LINES="${PRODUCT_LINE}" TEST_PROFILE="${TEST_PROFILE}" BUILD_SERVICES=auto
      printf '%q ' KEEP_TMP=true RESET_KAFKA=true CREATE_KAFKA_TOPICS=true KAFKA_RESET_SHARED_TOPICS=true
      printf '%q ' KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false RECONCILE_FUNDS=true
      printf '%q ' RECOVERY_PROVIDER="${provider}" RECOVERY_MODE="${mode}" ACCOUNT_RESTART_RECOVERY=false
      printf '%q ' RECOVERY_RESULT_FILE="${RECOVERY_RESULT_FILE}" RECOVERY_TIMELINE_FILE="${evidence}/recovery-timeline.tsv"
      printf '%q ' "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh"
      printf '\n'
    } >"${evidence}/command-line.txt"
    echo "| ${provider} | ${mode} | DRY_RUN | ${evidence} |" >>"${MATRIX_OUTPUT_DIR}/index.md"
  fi
done

if [[ "${MATRIX_EXECUTE}" == "true" ]]; then
  echo "RECOVERY_MATRIX PASS index=${MATRIX_OUTPUT_DIR}/index.md"
else
  echo "RECOVERY_MATRIX DRY_RUN index=${MATRIX_OUTPUT_DIR}/index.md"
fi
