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

W4_STATIC_ONLY=true RUN_ID="$RUN_ID" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false \
  RUNTIME_ROOT="$TEST_ROOT/run" "$RUNNER" scenario w4-six-line > "$TEST_ROOT/output.txt"
grep -q '^W4_SIX_LINE=PASS ' "$TEST_ROOT/output.txt"
manifest_root="$TEST_ROOT/run/runs/$RUN_ID/w4-six-line/manifests"
[[ "$(find "$manifest_root" -type f -name '*.manifest' | wc -l | tr -d ' ')" == 6 ]]
expected='1-SPOT.manifest 2-LINEAR_PERPETUAL.manifest 3-INVERSE_PERPETUAL.manifest 4-LINEAR_DELIVERY.manifest 5-INVERSE_DELIVERY.manifest 6-OPTION.manifest'
actual="$(find "$manifest_root" -type f -name '*.manifest' -exec basename {} \; | sort | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
[[ "$actual" == "$expected" ]]
grep -q '^FUNDS_DIFFERENCE=0$' "$manifest_root/6-OPTION.manifest"
grep -q '^wallet=ABSENT$' "$manifest_root/6-OPTION.manifest"
grep -q '^maker=REQUIRED$' "$manifest_root/6-OPTION.manifest"
printf 'W4_STATIC=PASS manifests=6 order=required wallet=absent maker=required\n'
