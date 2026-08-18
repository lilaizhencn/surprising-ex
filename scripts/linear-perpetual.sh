#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PRODUCT_LINE=LINEAR_PERPETUAL
readonly RUN_ID=linear-perpetual-fixed
readonly TEST_RUN_ID=linear-perpetual-fixed-test
readonly START_ORDER='instrument,core-node0,core-node1,core-node2,exporter,projector,price,account,trading,market-data,derivatives-lifecycle,funding,gateway,maker'
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home

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
