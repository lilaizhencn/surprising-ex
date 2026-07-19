#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MATRIX_PROFILES="${MATRIX_PROFILES:-baseline scale8 scale16}"
MATRIX_TRAFFIC_MODES="${MATRIX_TRAFFIC_MODES:-uniform hot1 hot3}"
MATRIX_TARGET_TPS_LIST="${MATRIX_TARGET_TPS_LIST:-30 50 80 120}"
MATRIX_REPEATS="${MATRIX_REPEATS:-3}"
MATRIX_DRY_RUN="${MATRIX_DRY_RUN:-true}"
MATRIX_CONTINUE_ON_FAILURE="${MATRIX_CONTINUE_ON_FAILURE:-false}"
MATRIX_OUTPUT_DIR="${MATRIX_OUTPUT_DIR:-${ROOT_DIR}/docs/stress-matrix-$(date -u +%Y%m%dT%H%M%SZ)}"

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${name} must be a positive integer, actual=${value}" >&2
    exit 1
  fi
}

profile_values() {
  case "$1" in
    baseline)
      echo "4 4 2"
      ;;
    scale8)
      echo "8 8 4"
      ;;
    scale16)
      echo "16 8 4"
      ;;
    *)
      echo "Unknown MATRIX_PROFILES entry: $1" >&2
      exit 1
      ;;
  esac
}

traffic_values() {
  case "$1" in
    uniform)
      echo "0 80"
      ;;
    hot1)
      echo "1 80"
      ;;
    hot3)
      echo "3 80"
      ;;
    hot1-only)
      echo "1 100"
      ;;
    *)
      echo "Unknown MATRIX_TRAFFIC_MODES entry: $1" >&2
      exit 1
      ;;
  esac
}

validate() {
  require_positive_integer "MATRIX_REPEATS" "${MATRIX_REPEATS}"
  case "${MATRIX_DRY_RUN}" in
    true|false) ;;
    *)
      echo "MATRIX_DRY_RUN must be true or false" >&2
      exit 1
      ;;
  esac
  case "${MATRIX_CONTINUE_ON_FAILURE}" in
    true|false) ;;
    *)
      echo "MATRIX_CONTINUE_ON_FAILURE must be true or false" >&2
      exit 1
      ;;
  esac
  local target_tps
  for target_tps in ${MATRIX_TARGET_TPS_LIST}; do
    if [[ ! "${target_tps}" =~ ^[0-9]+$ ]]; then
      echo "MATRIX_TARGET_TPS_LIST values must be non-negative integers, actual=${target_tps}" >&2
      exit 1
    fi
  done
  local profile traffic_mode
  for profile in ${MATRIX_PROFILES}; do
    profile_values "${profile}" >/dev/null
  done
  for traffic_mode in ${MATRIX_TRAFFIC_MODES}; do
    traffic_values "${traffic_mode}" >/dev/null
  done
}

print_case() {
  local label="$1"
  local report_file="$2"
  local matching_concurrency="$3"
  local matching_engines="$4"
  local risk_engines="$5"
  local hot_symbols="$6"
  local hot_percent="$7"
  local target_tps="$8"
  printf '%s\n' \
    "PRODUCT_LINES=LINEAR_PERPETUAL MULTI_SYMBOL_STRESS=true RESET_KAFKA=true CREATE_KAFKA_TOPICS=true BUILD_SERVICES=auto STRESS_RUN_LABEL=${label} STRESS_MATCHING_KAFKA_CONCURRENCY=${matching_concurrency} STRESS_MATCHING_ENGINE_SHARDS=${matching_engines} STRESS_MATCHING_RISK_SHARDS=${risk_engines} STRESS_HOT_SYMBOL_COUNT=${hot_symbols} STRESS_HOT_TRAFFIC_PERCENT=${hot_percent} STRESS_TARGET_TPS=${target_tps} STRESS_REPORT_FILE=${report_file} ./scripts/product-line-api-flow-smoke.sh"
}

run_case() {
  local label="$1"
  local report_file="$2"
  local matching_concurrency="$3"
  local matching_engines="$4"
  local risk_engines="$5"
  local hot_symbols="$6"
  local hot_percent="$7"
  local target_tps="$8"
  PRODUCT_LINES=LINEAR_PERPETUAL \
  MULTI_SYMBOL_STRESS=true \
  RESET_KAFKA=true \
  CREATE_KAFKA_TOPICS=true \
  BUILD_SERVICES=auto \
  KEEP_TMP=true \
  STRESS_RUN_LABEL="${label}" \
  STRESS_MATCHING_KAFKA_CONCURRENCY="${matching_concurrency}" \
  STRESS_MATCHING_ENGINE_SHARDS="${matching_engines}" \
  STRESS_MATCHING_RISK_SHARDS="${risk_engines}" \
  STRESS_HOT_SYMBOL_COUNT="${hot_symbols}" \
  STRESS_HOT_TRAFFIC_PERCENT="${hot_percent}" \
  STRESS_TARGET_TPS="${target_tps}" \
  STRESS_REPORT_FILE="${report_file}" \
    "${ROOT_DIR}/scripts/product-line-api-flow-smoke.sh"
}

main() {
  validate
  local profile traffic_mode target_tps repeat
  local matching_concurrency matching_engines risk_engines hot_symbols hot_percent
  local label report_file total=0 failed=0
  for profile in ${MATRIX_PROFILES}; do
    for traffic_mode in ${MATRIX_TRAFFIC_MODES}; do
      for target_tps in ${MATRIX_TARGET_TPS_LIST}; do
        total=$((total + MATRIX_REPEATS))
      done
    done
  done
  echo "LINEAR_PERPETUAL stress matrix cases=${total} dryRun=${MATRIX_DRY_RUN}"
  if [[ "${MATRIX_DRY_RUN}" == "false" ]]; then
    mkdir -p "${MATRIX_OUTPUT_DIR}"
    {
      echo "# LINEAR_PERPETUAL 压测矩阵"
      echo
      echo "| case | profile | traffic | target TPS | repeat | result | report |"
      echo "|---|---|---|---:|---:|---|---|"
    } >"${MATRIX_OUTPUT_DIR}/index.md"
  fi
  for profile in ${MATRIX_PROFILES}; do
    read -r matching_concurrency matching_engines risk_engines < <(profile_values "${profile}")
    for traffic_mode in ${MATRIX_TRAFFIC_MODES}; do
      read -r hot_symbols hot_percent < <(traffic_values "${traffic_mode}")
      for target_tps in ${MATRIX_TARGET_TPS_LIST}; do
        for ((repeat = 1; repeat <= MATRIX_REPEATS; repeat++)); do
          label="${profile}-${traffic_mode}-${target_tps}tps-r${repeat}"
          report_file="${MATRIX_OUTPUT_DIR}/${label}.md"
          if [[ "${MATRIX_DRY_RUN}" == "true" ]]; then
            print_case "${label}" "${report_file}" "${matching_concurrency}" "${matching_engines}" \
              "${risk_engines}" "${hot_symbols}" "${hot_percent}" "${target_tps}"
            continue
          fi
          echo "Running ${label}"
          if run_case "${label}" "${report_file}" "${matching_concurrency}" "${matching_engines}" \
              "${risk_engines}" "${hot_symbols}" "${hot_percent}" "${target_tps}"; then
            echo "| ${label} | ${profile} | ${traffic_mode} | ${target_tps} | ${repeat} | PASS | [report](./${label}.md) |" \
              >>"${MATRIX_OUTPUT_DIR}/index.md"
          else
            failed=$((failed + 1))
            echo "| ${label} | ${profile} | ${traffic_mode} | ${target_tps} | ${repeat} | FAIL | [report](./${label}.md) |" \
              >>"${MATRIX_OUTPUT_DIR}/index.md"
            if [[ "${MATRIX_CONTINUE_ON_FAILURE}" != "true" ]]; then
              exit 1
            fi
          fi
        done
      done
    done
  done
  if ((failed > 0)); then
    echo "Stress matrix completed with failures=${failed}; index=${MATRIX_OUTPUT_DIR}/index.md" >&2
    exit 1
  fi
  if [[ "${MATRIX_DRY_RUN}" == "false" ]]; then
    echo "Stress matrix passed; index=${MATRIX_OUTPUT_DIR}/index.md"
  fi
}

main "$@"
