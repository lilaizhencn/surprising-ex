#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$SCRIPT_DIR/run.sh"
readonly TEST_PRODUCT_LINE=SPOT
readonly SCENARIO_PRODUCT_LINE=LINEAR_PERPETUAL
readonly FIXED_RUN_ID=w4-spot-fixed
readonly FIXED_POSTGRES_PORT=25432
readonly FIXED_KAFKA_PORT=29092
readonly SERVICE_ORDER='postgres,kafka,migrations,core-node0,core-node1,core-node2,exporter,projector,instrument,price,account,order,matching,trigger,risk,gateway,maker'
readonly MAIN_WORKTREE=/private/tmp/surprising-w4-current.RDN7eY
export JAVA_HOME=/opt/homebrew/opt/openjdk@25

printf 'PRODUCT_LINE=%s\nRUN_ID=%s\nPOSTGRES_PORT=%s\nKAFKA_PORT=%s\nSTART_ORDER=%s\n' \
  "$TEST_PRODUCT_LINE" "$FIXED_RUN_ID" "$FIXED_POSTGRES_PORT" "$FIXED_KAFKA_PORT" "$SERVICE_ORDER"

case "${1:-test}" in
  start)
    RUN_ID="$FIXED_RUN_ID" PRODUCT_LINE="$TEST_PRODUCT_LINE" WALLET_ENABLED=false TASK_RUN_FRESH=false \
      POSTGRES_PORT="$FIXED_POSTGRES_PORT" KAFKA_PORT="$FIXED_KAFKA_PORT" W4_MAIN_WORKTREE="$MAIN_WORKTREE" "$RUNNER" line-up
    ;;
  test)
    RUN_ID="$FIXED_RUN_ID" PRODUCT_LINE="$SCENARIO_PRODUCT_LINE" PRODUCT_LINES="$TEST_PRODUCT_LINE" \
      WALLET_ENABLED=false TASK_RUN_FRESH=true POSTGRES_PORT="$FIXED_POSTGRES_PORT" KAFKA_PORT="$FIXED_KAFKA_PORT" \
      W4_MAIN_WORKTREE="$MAIN_WORKTREE" "$RUNNER" scenario w4-six-line
    ;;
  stop)
    RUN_ID="$FIXED_RUN_ID" PRODUCT_LINE="$TEST_PRODUCT_LINE" WALLET_ENABLED=false TASK_RUN_FRESH=false \
      POSTGRES_PORT="$FIXED_POSTGRES_PORT" KAFKA_PORT="$FIXED_KAFKA_PORT" W4_MAIN_WORKTREE="$MAIN_WORKTREE" "$RUNNER" down
    ;;
  *) printf 'USAGE: %s [start|test|stop]\n' "$0" >&2; exit 2 ;;
esac
