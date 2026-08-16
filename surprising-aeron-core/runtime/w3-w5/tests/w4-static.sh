#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/../run.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-w4-static.XXXXXX")"
RUN_ID="task16-static-${$}"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT INT TERM

expect_refusal() {
  local expected="$1"
  shift
  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || { printf 'EXPECTED_REFUSAL_MISSING=%s\n' "$expected" >&2; exit 1; }
  [[ "$output" == *"$expected"* ]] || { printf 'EXPECTED_ERROR_MISSING=%s output=%s\n' "$expected" "$output" >&2; exit 1; }
}

bash -n "$RUNNER" "$SCRIPT_DIR/../scenarios/common.sh" "$SCRIPT_DIR/../scenarios/w4-six-line.sh"
expect_refusal WALLET_REFUSED env RUN_ID="$RUN_ID-wallet" PRODUCT_LINE=LINEAR_PERPETUAL \
  WALLET_ENABLED=true W4_STATIC_ONLY=true RUNTIME_ROOT="$TEST_ROOT/wallet" "$RUNNER" scenario w4-six-line
expect_refusal PRODUCT_LINES_REFUSED env RUN_ID="$RUN_ID-order" PRODUCT_LINE=LINEAR_PERPETUAL \
  PRODUCT_LINES=OPTION,SPOT W4_STATIC_ONLY=true RUNTIME_ROOT="$TEST_ROOT/order" "$RUNNER" scenario w4-six-line
expect_refusal LIFECYCLE_AUTHORITY_REFUSED env RUN_ID="$RUN_ID-authority" PRODUCT_LINE=LINEAR_PERPETUAL \
  W4_LIFECYCLE_AUTHORITY=PROVIDER W4_STATIC_ONLY=true RUNTIME_ROOT="$TEST_ROOT/authority" "$RUNNER" scenario w4-six-line
expect_refusal PROJECTION_AUTHORITY_REFUSED env RUN_ID="$RUN_ID-projection" PRODUCT_LINE=LINEAR_PERPETUAL \
  W4_PROJECTION_AUTHORITY=PROVIDER W4_STATIC_ONLY=true RUNTIME_ROOT="$TEST_ROOT/projection" "$RUNNER" scenario w4-six-line
expect_refusal PG_SELECTED_REFUSED env RUN_ID="$RUN_ID-pg" PRODUCT_LINE=LINEAR_PERPETUAL \
  PG_SELECTED=true W4_STATIC_ONLY=true RUNTIME_ROOT="$TEST_ROOT/pg" "$RUNNER" scenario w4-six-line

W4_STATIC_ONLY=true RUN_ID="$RUN_ID" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false \
  RUNTIME_ROOT="$TEST_ROOT/run" "$RUNNER" scenario w4-six-line > "$TEST_ROOT/output.txt"
grep -q '^W4_STATIC_PLAN=STATIC_PREP ' "$TEST_ROOT/output.txt"
grep -q '^W4_SIX_LINE=STATIC_PREP ' "$TEST_ROOT/output.txt"
! grep -q '^W4_SIX_LINE=PASS ' "$TEST_ROOT/output.txt"
manifest_root="$TEST_ROOT/run/runs/$RUN_ID/w4-six-line/manifests"
[[ "$(find "$manifest_root" -type f -name '*.manifest' | wc -l | tr -d ' ')" == 6 ]]
expected='1-SPOT.manifest 2-LINEAR_PERPETUAL.manifest 3-INVERSE_PERPETUAL.manifest 4-LINEAR_DELIVERY.manifest 5-INVERSE_DELIVERY.manifest 6-OPTION.manifest'
actual="$(find "$manifest_root" -type f -name '*.manifest' -exec basename {} \; | sort | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
[[ "$actual" == "$expected" ]]
for manifest in "$manifest_root"/*.manifest; do
  grep -q '^mode=STATIC_PREP$' "$manifest"
  grep -q '^W4_STATUS=STATIC_PREP$' "$manifest"
  grep -q '^fundsReconciliation=NOT_RUN$' "$manifest"
  ! grep -q '^FUNDS_DIFFERENCE=' "$manifest"
done
grep -q '^projectionAuthority=CORE$' "$manifest_root/1-SPOT.manifest"
! grep -q '^FUNDS_DIFFERENCE=' "$TEST_ROOT/run/runs/$RUN_ID/w4-six-line-static.manifest"
for runtime_log in "$TEST_ROOT/run/runs/$RUN_ID/w4-six-line"/*.runtime.log; do
  grep -q '^LINE_START=STATIC_PREP ' "$runtime_log"
  grep -q '^LINE_STOP=STATIC_PREP .*cleanup=STATIC_PREP ' "$runtime_log"
  ! grep -q '^CLEANUP=PASS ' "$runtime_log"
done
grep -q '^rows=SPOT:CONSERVATION,SPOT:CONTROL_GUARD$' "$manifest_root/1-SPOT.manifest"
grep -q '^rows=LINEAR_PERPETUAL:CROSS,LINEAR_PERPETUAL:ISOLATED,FUNDING_POSITIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL$' \
  "$manifest_root/2-LINEAR_PERPETUAL.manifest"
grep -q '^rows=INVERSE_PERPETUAL:CROSS,INVERSE_PERPETUAL:ISOLATED,FUNDING_POSITIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL$' \
  "$manifest_root/3-INVERSE_PERPETUAL.manifest"
grep -q '^rows=LINEAR_DELIVERY:CROSS,LINEAR_DELIVERY:ISOLATED,SETTLEMENT,CURSOR$' \
  "$manifest_root/4-LINEAR_DELIVERY.manifest"
grep -q '^rows=INVERSE_DELIVERY:CROSS,INVERSE_DELIVERY:ISOLATED,SETTLEMENT,CURSOR$' \
  "$manifest_root/5-INVERSE_DELIVERY.manifest"
grep -q '^rows=OPTION:CALL:ITM,OPTION:CALL:ATM,OPTION:CALL:OTM,OPTION:PUT:ITM,OPTION:PUT:ATM,OPTION:PUT:OTM$' \
  "$manifest_root/6-OPTION.manifest"
grep -q '^maker=REQUIRED$' "$manifest_root/6-OPTION.manifest"

W4_STATIC_ONLY=true RUN_ID="$RUN_ID-faults" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false \
  RUNTIME_ROOT="$TEST_ROOT/faults" "$RUNNER" scenario w4-faults > "$TEST_ROOT/faults.txt"
grep -q '^W4_FAULTS=STATIC_PREP ' "$TEST_ROOT/faults.txt"
! grep -q '^W4_FAULTS=PASS ' "$TEST_ROOT/faults.txt"
fault_manifest="$TEST_ROOT/faults/runs/$RUN_ID-faults/w4-faults/manifests/1-LINEAR_PERPETUAL-faults.manifest"
grep -q '^faults=NOT_EXERCISED$' "$fault_manifest"
! grep -q '^FUNDS_DIFFERENCE=' "$fault_manifest"

bad_manifest="$TEST_ROOT/bad.manifest"
cp "$manifest_root/1-SPOT.manifest" "$bad_manifest"
sed -i.bak 's/^rows=.*/rows=SPOT:CONSERVATION/' "$bad_manifest"
set +e
bad_output="$(W4_STATIC_ONLY=true bash -c "source '$SCRIPT_DIR/../scenarios/w4-six-line.sh'; verify_manifest SPOT '$bad_manifest'" 2>&1)"
bad_status=$?
set -e
[[ "$bad_status" -ne 0 ]]
[[ "$bad_output" == *'ROWS_MISMATCH productLine=SPOT'* ]]

zero_manifest="$TEST_ROOT/zero.manifest"
cp "$manifest_root/6-OPTION.manifest" "$zero_manifest"
printf 'FUNDS_DIFFERENCE=0\n' >> "$zero_manifest"
set +e
zero_output="$(W4_STATIC_ONLY=true bash -c "source '$SCRIPT_DIR/../scenarios/w4-six-line.sh'; verify_manifest OPTION '$zero_manifest'" 2>&1)"
zero_status=$?
set -e
[[ "$zero_status" -ne 0 ]]
[[ "$zero_output" == *'STATIC_FUNDS_DIFFERENCE_FORBIDDEN productLine=OPTION'* ]]

printf 'W4_STATIC_PREP=PASS manifests=6 order=required wallet=absent maker=required faults=not-exercised checker=fail-closed\n'
