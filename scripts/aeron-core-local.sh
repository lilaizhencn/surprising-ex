#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/surprising-aeron-core/compose.yaml}"
PRODUCT_LINE="${PRODUCT_LINE:-SPOT}"
PRODUCT_LINE_SLUG="$(printf '%s' "$PRODUCT_LINE" | tr '[:upper:]' '[:lower:]')"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-surprising-aeron-$PRODUCT_LINE_SLUG}"

case "$PRODUCT_LINE" in
  SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
  *) printf 'unsupported PRODUCT_LINE=%s\n' "$PRODUCT_LINE" >&2; exit 2 ;;
esac

if [[ ! -f "$COMPOSE_FILE" ]]; then
  printf 'compose file not found: %s\n' "$COMPOSE_FILE" >&2
  exit 2
fi

compose() {
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

smoke_service="${SMOKE_SERVICE:-}"
if [[ -z "$smoke_service" ]]; then
  case "$PRODUCT_LINE" in
    SPOT) smoke_service=spot-match-smoke ;;
    LINEAR_PERPETUAL|INVERSE_PERPETUAL) smoke_service=derivative-smoke ;;
    LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) smoke_service=product-line-gate ;;
  esac
fi

command_name="${1:-status}"
case "$command_name" in
  build) compose build ;;
  up) compose up -d node0 node1 node2 ;;
  down) compose down ;;
  status) compose ps ;;
  smoke) compose --profile tools run --rm "$smoke_service" ;;
  *) printf 'usage: %s {build|up|down|status|smoke}\n' "${BASH_SOURCE[0]}" >&2; exit 2 ;;
esac
