#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$SCRIPT_DIR/run.sh"
readonly PRODUCT_LINE=LINEAR_PERPETUAL
readonly RUN_ID=linear-perpetual-fixed
readonly TEST_RUN_ID=linear-perpetual-fixed-test
readonly POSTGRES_PORT=25432
readonly KAFKA_PORT=29092
readonly START_COMMAND=up
readonly START_ORDER='postgres,kafka,migrations,core-node0,core-node1,core-node2,exporter,projector,instrument,price,account,order,matching,trigger,risk,funding,liquidation,insurance,adl,gateway,maker'
readonly MAIN_WORKTREE="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
readonly TOOL_JAR="$MAIN_WORKTREE/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
readonly TEST_ROOT="${TMPDIR:-/tmp}/surprising-product-line-tests/linear-perpetual"
export JAVA_HOME=/opt/homebrew/opt/openjdk@25

runtime() {
  local run_id="$1" fresh="$2" command="$3"
  RUN_ID="$run_id" PRODUCT_LINE="$PRODUCT_LINE" WALLET_ENABLED=false TASK_RUN_FRESH="$fresh" \
    POSTGRES_PORT="$POSTGRES_PORT" KAFKA_PORT="$KAFKA_PORT" RUNTIME_MAIN_WORKTREE="$MAIN_WORKTREE" "$RUNNER" "$command"
}

run_test() {
  local manifest="$TEST_ROOT/manifest.env" status=0
  mkdir -p "$TEST_ROOT"
  runtime "$TEST_RUN_ID" true "$START_COMMAND"
  trap 'status=$?; runtime "$TEST_RUN_ID" false down || status=1; exit "$status"' EXIT
  [[ -f "$TOOL_JAR" ]] || { printf 'ERROR=TOOLS_JAR_MISSING path=%s\n' "$TOOL_JAR" >&2; return 1; }
  rm -f "$manifest"
  PRODUCT_LINE="$PRODUCT_LINE" WALLET_ENABLED=false "$JAVA_HOME/bin/java" \
    -Dsurprising.aeron.product-line="$PRODUCT_LINE" \
    -Dsurprising.aeron.lifecycle-manifest="$manifest" \
    -Dsurprising.aeron.lifecycle-seed=16002 \
    -cp "$TOOL_JAR" com.surprising.aeron.tools.ProductLineLifecycleQaMain
  grep -q '^TEST_STATUS=PASS$' "$manifest"
  grep -q '^FUNDS_DIFFERENCE=0$' "$manifest"
  runtime "$TEST_RUN_ID" false down
  trap - EXIT
  printf 'PRODUCT_LINE_TEST=PASS productLine=%s manifest=%s\n' "$PRODUCT_LINE" "$manifest"
}

printf 'PRODUCT_LINE=%s\nRUN_ID=%s\nPOSTGRES_PORT=%s\nKAFKA_PORT=%s\nSTART_ORDER=%s\n' \
  "$PRODUCT_LINE" "$RUN_ID" "$POSTGRES_PORT" "$KAFKA_PORT" "$START_ORDER"

case "${1:-test}" in
  start) runtime "$RUN_ID" false "$START_COMMAND" ;;
  test) run_test ;;
  stop) runtime "$RUN_ID" false down ;;
  *) printf 'USAGE: %s [start|test|stop]\n' "$0" >&2; exit 2 ;;
esac
