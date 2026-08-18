#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/surprising-aeron-core/compose.yaml}"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
PRODUCT_LINE_SLUG="$(printf '%s' "$PRODUCT_LINE" | tr '[:upper:]' '[:lower:]')"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-surprising-aeron-$PRODUCT_LINE_SLUG}"
HOSTNAMES="${AERON_HOSTNAMES:-node0,node1,node2}"
EGRESS_HOSTNAME="${AERON_EGRESS_HOSTNAME:-probe}"

case "$PRODUCT_LINE" in
  SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
  *) printf 'unsupported PRODUCT_LINE=%s\n' "$PRODUCT_LINE" >&2; exit 2 ;;
esac

compose() {
  PRODUCT_LINE="$PRODUCT_LINE" PRODUCT_LINE_SLUG="$PRODUCT_LINE_SLUG" \
    docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

run_java_tool() {
  local main_class="$1"
  shift
  compose --profile tools run --rm --no-deps \
    -e "PRODUCT_LINE=$PRODUCT_LINE" \
    -e "AERON_HOSTNAMES=$HOSTNAMES" \
    -e "AERON_EGRESS_HOSTNAME=$EGRESS_HOSTNAME" \
    --entrypoint java probe \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="$PRODUCT_LINE" \
    -Dsurprising.aeron.hostnames="$HOSTNAMES" \
    -Dsurprising.aeron.egress-hostname="$EGRESS_HOSTNAME" \
    "$@" -cp /opt/surprising/tools.jar "$main_class"
}

tool="${1:-}"
case "$tool" in
  probe)
    shift
    run_java_tool com.surprising.aeron.tools.ClusterProbeMain \
      -Dsurprising.aeron.probe-mode="${PROBE_MODE:-query}" \
      -Dsurprising.aeron.source-id="${PROBE_SOURCE_ID:-910001}" \
      "$@"
    ;;
  funds-reconcile)
    shift
    : "${RECONCILE_USER_RANGES:?RECONCILE_USER_RANGES is required, e.g. 6100005001:6100005003}"
    : "${RECONCILE_ASSET_TOTALS:?RECONCILE_ASSET_TOTALS is required, e.g. USDT:2000}"
    compose --profile tools run --rm --no-deps \
      -e "PRODUCT_LINE=$PRODUCT_LINE" \
      -e "AERON_HOSTNAMES=$HOSTNAMES" \
      -e "AERON_EGRESS_HOSTNAME=$EGRESS_HOSTNAME" \
      -e "RECONCILE_USER_RANGES=$RECONCILE_USER_RANGES" \
      -e "RECONCILE_ASSET_TOTALS=$RECONCILE_ASSET_TOTALS" \
      --entrypoint java probe \
      --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -cp /opt/surprising/tools.jar com.surprising.aeron.tools.ClusterFundsReconcileMain "$@"
    ;;
  capacity)
    shift
    run_java_tool com.surprising.aeron.tools.ClusterCapacityMain \
      -Dsurprising.aeron.capacity-mode="${CAPACITY_MODE:-run}" \
      -Dsurprising.aeron.capacity-workload="${CAPACITY_WORKLOAD:-MATCH}" \
      -Dsurprising.aeron.capacity-duration-seconds="${CAPACITY_DURATION_SECONDS:-15}" \
      -Dsurprising.aeron.capacity-warmup-seconds="${CAPACITY_WARMUP_SECONDS:-5}" \
      -Dsurprising.aeron.capacity-workers="${CAPACITY_WORKERS:-4}" \
      -Dsurprising.aeron.capacity-connections="${CAPACITY_CONNECTIONS:-4}" \
      -Dsurprising.aeron.capacity-user-count="${CAPACITY_USER_COUNT:-100}" \
      -Dsurprising.aeron.capacity-async-in-flight="${CAPACITY_ASYNC_IN_FLIGHT:-1}" \
      -Dsurprising.aeron.capacity-offered-commands-per-second="${CAPACITY_OFFERED_COMMANDS_PER_SECOND:-0}" \
      "$@"
    ;;
  lifecycle-capacity)
    shift
    run_java_tool com.surprising.aeron.tools.ClusterLifecycleCapacityMain \
      -Dsurprising.aeron.lifecycle-pairs="${LIFECYCLE_PAIRS:-32}" \
      -Dsurprising.aeron.lifecycle-connections="${LIFECYCLE_CONNECTIONS:-8}" \
      "$@"
    ;;
  export-status|export-drain|export-fail)
    case "$tool" in
      export-status) EXPORT_SMOKE_MODE=status ;;
      export-drain) EXPORT_SMOKE_MODE=drain ;;
      export-fail) EXPORT_SMOKE_MODE=fail ;;
    esac
    export PRODUCT_LINE PRODUCT_LINE_SLUG EXPORT_SMOKE_MODE
    compose --profile tools run --rm --no-deps export-smoke
    ;;
  *)
    printf 'usage: %s {probe|funds-reconcile|capacity|lifecycle-capacity|export-status|export-drain|export-fail}\n' \
      "${BASH_SOURCE[0]}" >&2
    exit 2
    ;;
esac
