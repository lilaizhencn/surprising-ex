#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PRODUCT_LINE=INVERSE_DELIVERY
readonly RUN_ID=inverse-delivery-fixed
readonly TEST_RUN_ID=inverse-delivery-fixed-test
readonly START_ORDER='core-node0,core-node1,core-node2'
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home

runtime() {
  local run_id="$1" command="$2"
  COMPOSE_PROJECT_NAME="surprising-aeron-$run_id" PRODUCT_LINE="$PRODUCT_LINE" \
    "$ROOT_DIR/scripts/aeron-core-local.sh" "$command"
}

run_test() {
  COMPOSE_PROJECT_NAME="surprising-aeron-$TEST_RUN_ID" PRODUCT_LINE="$PRODUCT_LINE" FRESH=true \
    "$ROOT_DIR/scripts/integration-smoke.sh"
  printf 'PRODUCT_LINE_TEST=PASS productLine=%s runId=%s scope=CORE_ONLY\n' \
    "$PRODUCT_LINE" "$TEST_RUN_ID"
}

printf 'PRODUCT_LINE=%s\nRUN_ID=%s\nSTART_ORDER=%s\n' "$PRODUCT_LINE" "$RUN_ID" "$START_ORDER"

case "${1:-test}" in
  start) runtime "$RUN_ID" up ;;
  test) run_test ;;
  stop) runtime "$RUN_ID" down ;;
  *) printf 'USAGE: %s [start|test|stop]\n' "$0" >&2; exit 2 ;;
esac
