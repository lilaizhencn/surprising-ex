#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
MANAGE_CORE="${MANAGE_CORE:-false}"
KEEP_RUNTIME="${KEEP_RUNTIME:-true}"

case "$MANAGE_CORE" in true|false) ;; *) printf 'MANAGE_CORE must be true or false\n' >&2; exit 2 ;; esac
case "$KEEP_RUNTIME" in true|false) ;; *) printf 'KEEP_RUNTIME must be true or false\n' >&2; exit 2 ;; esac

cleanup() {
  if [[ "$MANAGE_CORE" == true && "$KEEP_RUNTIME" == false ]]; then
    PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" down >/dev/null
  fi
}
trap cleanup EXIT

if [[ "$MANAGE_CORE" == true ]]; then
  PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" up
fi
PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-tool.sh" probe
PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-tool.sh" export-status
