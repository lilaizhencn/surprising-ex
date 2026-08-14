#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?set PRODUCT_LINE to exactly one product line}"
OUTPUT_DIR="${CAPACITY_OUTPUT_DIR:-${ROOT_DIR}/reports/capacity/${PRODUCT_LINE}/$(date -u +%Y%m%dT%H%M%SZ)}"
START_TPS="${START_TPS:-100}"
STEP_TPS="${STEP_TPS:-100}"
MAX_TPS="${MAX_TPS:-1000000}"
mkdir -p "${OUTPUT_DIR}"
{
  echo "# Uncapped Aeron capacity: ${PRODUCT_LINE}"
  echo
  echo "No OPS target is used. The run increases load until a gate fails."
  echo '| Offered OPS | Result | Evidence |'
  echo '|---:|---|---|'
} >"${OUTPUT_DIR}/index.md"

last_pass=0
for ((offered = START_TPS; offered <= MAX_TPS; offered += STEP_TPS)); do
  evidence="${OUTPUT_DIR}/offered-${offered}"
  mkdir -p "${evidence}"
  set +e
  PRODUCT_LINE="${PRODUCT_LINE}" TEST_MODE=performance EXECUTE=true \
    TEST_PROFILE=cloud-capacity EVIDENCE_DIR="${evidence}" \
    STRESS_TARGET_TPS="${offered}" STRESS_REPORT_FILE="${evidence}/report.md" \
    ./scripts/production-performance-gate.sh >"${evidence}/gate.log" 2>&1
  status=$?
  set -e
  if (( status != 0 )); then
    echo "| ${offered} | FAIL_STOP | offered-${offered} |" >>"${OUTPUT_DIR}/index.md"
    break
  fi
  last_pass="${offered}"
  echo "| ${offered} | PASS | offered-${offered} |" >>"${OUTPUT_DIR}/index.md"
done

echo "stable_last_pass_ops=${last_pass} report=${OUTPUT_DIR}/index.md"
