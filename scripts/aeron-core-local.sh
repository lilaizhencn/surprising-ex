#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/surprising-aeron-core/compose.yaml"
PRODUCT_LINE="${PRODUCT_LINE:-SPOT}"
PRODUCT_LINE_SLUG="$(printf '%s' "${PRODUCT_LINE}" | tr '[:upper:]_' '[:lower:]-')"

export PRODUCT_LINE PRODUCT_LINE_SLUG

usage() {
  echo "usage: PRODUCT_LINE=SPOT $0 build|up|down|wait-ready|probe|hash|funds-smoke|funds-verify|logs|ps"
}

wait_ready() {
  local attempt
  for attempt in 1 2 3 4 5 6; do
    if PROBE_MODE=query docker compose -f "${COMPOSE_FILE}" run --rm probe >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Aeron Cluster did not become ready" >&2
  return 1
}

case "${1:-}" in
  build)
    export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    mvn -f "${ROOT_DIR}/pom.xml" -pl :surprising-aeron-service,:surprising-aeron-tools -am -DskipTests package
    docker compose -f "${COMPOSE_FILE}" build
    ;;
  up)
    docker compose -f "${COMPOSE_FILE}" up -d node0 node1 node2
    ;;
  down)
    docker compose -f "${COMPOSE_FILE}" down
    ;;
  wait-ready)
    wait_ready
    ;;
  probe)
    PROBE_MODE=increment docker compose -f "${COMPOSE_FILE}" run --rm probe
    ;;
  hash)
    PROBE_MODE=query docker compose -f "${COMPOSE_FILE}" run --rm probe
    ;;
  funds-smoke)
    wait_ready
    FUNDS_SMOKE_MODE=execute docker compose -f "${COMPOSE_FILE}" run --rm funds-smoke
    ;;
  funds-verify)
    wait_ready
    FUNDS_SMOKE_MODE=verify docker compose -f "${COMPOSE_FILE}" run --rm funds-smoke
    ;;
  logs)
    docker compose -f "${COMPOSE_FILE}" logs --tail=200 node0 node1 node2
    ;;
  ps)
    docker compose -f "${COMPOSE_FILE}" ps
    ;;
  *)
    usage
    exit 2
    ;;
esac
