#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
ACTION="${ACTION:-up}"
CORE_ONLY="${CORE_ONLY:-true}"

if [[ "$CORE_ONLY" != true ]]; then
  printf 'only the Aeron Core runtime is managed by this entrypoint\n' >&2
  exit 2
fi

case "$ACTION" in
  up|down|fresh|status) ;;
  *) printf 'unsupported ACTION=%s\n' "$ACTION" >&2; exit 2 ;;
esac

PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" "$ACTION"
printf 'productLineProviders=CORE_ONLY productLine=%s action=%s\n' "$PRODUCT_LINE" "$ACTION"
