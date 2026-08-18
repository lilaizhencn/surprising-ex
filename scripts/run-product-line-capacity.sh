#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
MANAGE_CORE="${MANAGE_CORE:-true}"
KEEP_RUNTIME="${KEEP_RUNTIME:-false}"
RUN_LIFECYCLE="${RUN_LIFECYCLE:-false}"
FRESH="${FRESH:-true}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/.local-logs/capacity-${PRODUCT_LINE}-$(date -u +%Y%m%dT%H%M%SZ)}"

case "$MANAGE_CORE" in true|false) ;; *) printf 'MANAGE_CORE must be true or false\n' >&2; exit 2 ;; esac
case "$KEEP_RUNTIME" in true|false) ;; *) printf 'KEEP_RUNTIME must be true or false\n' >&2; exit 2 ;; esac
case "$RUN_LIFECYCLE" in true|false) ;; *) printf 'RUN_LIFECYCLE must be true or false\n' >&2; exit 2 ;; esac
case "$FRESH" in true|false) ;; *) printf 'FRESH must be true or false\n' >&2; exit 2 ;; esac
mkdir -p "$OUTPUT_DIR"

cleanup() {
  if [[ "$MANAGE_CORE" == true && "$KEEP_RUNTIME" == false ]]; then
    PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" down >/dev/null
  fi
}
trap cleanup EXIT

if [[ "$MANAGE_CORE" == true ]]; then
  if [[ "$FRESH" == true ]]; then
    PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" fresh
  else
    PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" up
  fi
fi
printf 'capacityRun=START productLine=%s output=%s\n' "$PRODUCT_LINE" "$OUTPUT_DIR"
PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-tool.sh" capacity | tee "$OUTPUT_DIR/capacity.log"
if [[ "$RUN_LIFECYCLE" == true ]]; then
  PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-tool.sh" lifecycle-capacity | tee "$OUTPUT_DIR/lifecycle-capacity.log"
fi
cat >"$OUTPUT_DIR/manifest.env" <<EOF
PRODUCT_LINE=$PRODUCT_LINE
RUN_LIFECYCLE=$RUN_LIFECYCLE
FRESH=$FRESH
CAPACITY_DURATION_SECONDS=${CAPACITY_DURATION_SECONDS:-15}
CAPACITY_WARMUP_SECONDS=${CAPACITY_WARMUP_SECONDS:-5}
CAPACITY_WORKERS=${CAPACITY_WORKERS:-4}
CAPACITY_CONNECTIONS=${CAPACITY_CONNECTIONS:-4}
CAPACITY_USER_COUNT=${CAPACITY_USER_COUNT:-100}
RESULT=PASS
EOF
printf 'capacityRun=PASS productLine=%s output=%s\n' "$PRODUCT_LINE" "$OUTPUT_DIR"
