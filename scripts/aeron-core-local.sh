#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/surprising-aeron-core/compose.yaml"
PRODUCT_LINE="${PRODUCT_LINE:-SPOT}"
PRODUCT_LINE_SLUG="$(printf '%s' "${PRODUCT_LINE}" | tr '[:upper:]_' '[:lower:]-')"

export PRODUCT_LINE PRODUCT_LINE_SLUG

usage() {
  echo "usage: PRODUCT_LINE=SPOT $0 build|up|down|probe|hash|logs|ps"
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
  probe)
    PROBE_MODE=increment docker compose -f "${COMPOSE_FILE}" run --rm probe
    ;;
  hash)
    PROBE_MODE=query docker compose -f "${COMPOSE_FILE}" run --rm probe
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
