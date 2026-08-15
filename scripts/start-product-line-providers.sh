#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
ACTION="${ACTION:-up}"

case "$ACTION" in
  up|down|fresh|status) ;;
  *) printf 'unsupported ACTION=%s\n' "$ACTION" >&2; exit 2 ;;
esac

PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" "$ACTION"
printf 'productLineProviders=AERON_CORE productLine=%s action=%s\n' "$PRODUCT_LINE" "$ACTION"
