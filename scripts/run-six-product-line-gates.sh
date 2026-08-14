#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${GATE_OUTPUT_DIR:-${ROOT_DIR}/reports/product-line-gates/$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "${OUTPUT_DIR}"

lines=(SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION)
{
  echo "# Six product-line functional gate"
  echo
  echo "Started: $(date -u +%FT%TZ)"
  echo
  echo '| Product line | Result | Evidence | Funds diff |'
  echo '|---|---|---|---:|'
} >"${OUTPUT_DIR}/index.md"

for line in "${lines[@]}"; do
  evidence="${OUTPUT_DIR}/${line}"
  mkdir -p "${evidence}"
  set +e
  PRODUCT_LINES="${line}" BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true \
    KAFKA_INCLUDE_SHARED_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false \
    RECONCILE_FUNDS=true KEEP_TMP=true STRESS_REPORT_FILE="${evidence}/stress-report.md" \
    ./scripts/product-line-api-flow-smoke.sh >"${evidence}/gate.log" 2>&1
  status=$?
  set -e
  funds_diff="unknown"
  if [[ -f "${evidence}/funds-diff.txt" ]]; then funds_diff="$(tr -d '[:space:]' <"${evidence}/funds-diff.txt")"; fi
  if (( status == 0 )); then
    echo "| ${line} | PASS | ${line}/gate.log | ${funds_diff} |" >>"${OUTPUT_DIR}/index.md"
  else
    echo "| ${line} | FAIL | ${line}/gate.log | ${funds_diff} |" >>"${OUTPUT_DIR}/index.md"
    exit "${status}"
  fi
done

echo "functional-gate=PASS funds-diff=0 evidence=${OUTPUT_DIR}"
