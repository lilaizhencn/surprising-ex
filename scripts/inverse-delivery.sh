#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PRODUCT_LINE=INVERSE_DELIVERY
readonly RUN_ID=inverse-delivery-fixed
readonly TEST_RUN_ID=inverse-delivery-fixed-test
readonly START_ORDER='core-node0,core-node1,core-node2,gateway,price,market-data,derivatives-lifecycle,maker'
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/27.0.0-amzn}"

runtime() {
  local run_id="$1" action="$2"
  env RUN_ID="$run_id" PRODUCT_LINE="$PRODUCT_LINE" ACTION="$action" \
    "$ROOT_DIR/scripts/start-product-line-providers.sh"
}

printf 'PRODUCT_LINE=%s\nRUN_ID=%s\nSTART_ORDER=%s\n' "$PRODUCT_LINE" "$RUN_ID" "$START_ORDER"

case "${1:-test}" in
  start) runtime "$RUN_ID" up ;;
  test) runtime "$TEST_RUN_ID" test ;;
  stop) runtime "$RUN_ID" down ;;
  *) printf 'USAGE: %s [start|test|stop]\n' "$0" >&2; exit 2 ;;
esac
