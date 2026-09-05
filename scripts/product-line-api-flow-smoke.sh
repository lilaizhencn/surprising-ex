#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
FRESH="${FRESH:-true}"
KEEP_RUNTIME="${KEEP_RUNTIME:-false}"

case "$FRESH" in true|false) ;; *) printf 'FRESH must be true or false\n' >&2; exit 2 ;; esac
case "$KEEP_RUNTIME" in true|false) ;; *) printf 'KEEP_RUNTIME must be true or false\n' >&2; exit 2 ;; esac

cleanup() {
  if [[ "$KEEP_RUNTIME" == false ]]; then
    PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" down >/dev/null
  fi
}
trap cleanup EXIT

if [[ "$FRESH" == true ]]; then
  PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" fresh
else
  PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" up
fi
PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" smoke
