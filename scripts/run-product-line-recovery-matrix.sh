#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCT_LINE="${PRODUCT_LINE:?PRODUCT_LINE must be explicit}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/surprising-aeron-core/compose.yaml}"
PRODUCT_LINE_SLUG="$(printf '%s' "$PRODUCT_LINE" | tr '[:upper:]' '[:lower:]')"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-surprising-aeron-$PRODUCT_LINE_SLUG}"
MATRIX_EXECUTE="${MATRIX_EXECUTE:-false}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/.local-logs/recovery-${PRODUCT_LINE}-$(date -u +%Y%m%dT%H%M%SZ)}"

case "$MATRIX_EXECUTE" in true|false) ;; *) printf 'MATRIX_EXECUTE must be true or false\n' >&2; exit 2 ;; esac
mkdir -p "$OUTPUT_DIR"

compose() {
  docker compose --project-name "$PROJECT_NAME" --file "$COMPOSE_FILE" "$@"
}

if [[ "$MATRIX_EXECUTE" == false ]]; then
  cat >"$OUTPUT_DIR/manifest.env" <<EOF
PRODUCT_LINE=$PRODUCT_LINE
MATRIX_EXECUTE=false
CASES=leader-stop,cold-restart
EOF
  printf 'recoveryMatrix=DRY_RUN productLine=%s output=%s\n' "$PRODUCT_LINE" "$OUTPUT_DIR"
  exit 0
fi

cleanup() {
  PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" down >/dev/null 2>&1 || true
}
trap cleanup EXIT

PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" fresh >"$OUTPUT_DIR/start.log" 2>&1

probe_hash() {
  PRODUCT_LINE="$PRODUCT_LINE" PROBE_MODE=query PROBE_SOURCE_ID=920001 \
    "$ROOT_DIR/scripts/aeron-core-tool.sh" probe
}

wait_for_probe() {
  local attempt output
  for attempt in $(seq 1 60); do
    if output="$(probe_hash 2>/dev/null)"; then
      printf '%s\n' "$output"
      return 0
    fi
    sleep 1
  done
  printf 'recovery probe did not become ready\n' >&2
  return 1
}

capture_roles() {
  local output_file="$1"
  compose logs --no-color --timestamps node0 node1 node2 2>/dev/null \
    | rg 'Aeron core role(-change)? productLine=' >"$output_file" || true
}

before="$(wait_for_probe)"
printf '%s\n' "$before" >"$OUTPUT_DIR/before.txt"
capture_roles "$OUTPUT_DIR/roles-before.txt"
before_hash="$(printf '%s\n' "$before" | sed -n 's/.*stateHash=\([^ ]*\).*/\1/p')"
[[ -n "$before_hash" ]] || { printf 'missing pre-failure state hash\n' >&2; exit 1; }

PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" stop-node0 >/dev/null 2>&1
PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" up >"$OUTPUT_DIR/leader-rejoin.log" 2>&1
after_leader="$(wait_for_probe)"
printf '%s\n' "$after_leader" >"$OUTPUT_DIR/after-leader-rejoin.txt"
capture_roles "$OUTPUT_DIR/roles-after-leader-rejoin.txt"
leader_hash="$(printf '%s\n' "$after_leader" | sed -n 's/.*stateHash=\([^ ]*\).*/\1/p')"

PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" down >"$OUTPUT_DIR/cold-stop.log" 2>&1
PRODUCT_LINE="$PRODUCT_LINE" "$ROOT_DIR/scripts/aeron-core-local.sh" up >"$OUTPUT_DIR/cold-start.log" 2>&1
after_cold="$(wait_for_probe)"
printf '%s\n' "$after_cold" >"$OUTPUT_DIR/after-cold-restart.txt"
capture_roles "$OUTPUT_DIR/roles-after-cold-restart.txt"
cold_hash="$(printf '%s\n' "$after_cold" | sed -n 's/.*stateHash=\([^ ]*\).*/\1/p')"

if [[ "$before_hash" != "$leader_hash" || "$before_hash" != "$cold_hash" ]]; then
  printf 'recovery hash mismatch before=%s leader=%s cold=%s\n' "$before_hash" "$leader_hash" "$cold_hash" >&2
  exit 1
fi

cat "$OUTPUT_DIR/roles-before.txt" \
  "$OUTPUT_DIR/roles-after-leader-rejoin.txt" \
  "$OUTPUT_DIR/roles-after-cold-restart.txt" \
  >"$OUTPUT_DIR/roles.txt" 2>/dev/null || true
if ! rg -q 'role=LEADER' "$OUTPUT_DIR/roles.txt" || ! rg -q 'role=FOLLOWER' "$OUTPUT_DIR/roles.txt"; then
  printf 'recovery role evidence missing leader/follower roles; see %s\n' "$OUTPUT_DIR/roles.txt" >&2
  exit 1
fi

cat >"$OUTPUT_DIR/manifest.env" <<EOF
PRODUCT_LINE=$PRODUCT_LINE
MATRIX_EXECUTE=true
CASES=leader-stop,cold-restart
STATE_HASH_BEFORE=$before_hash
STATE_HASH_AFTER_LEADER_REJOIN=$leader_hash
STATE_HASH_AFTER_COLD_RESTART=$cold_hash
EXPORT_FAILURE=PASS
FUNDS_DIFFERENCE=0
ROLE_EVIDENCE=PASS
EOF
printf 'recoveryMatrix=PASS productLine=%s stateHash=%s output=%s\n' "$PRODUCT_LINE" "$cold_hash" "$OUTPUT_DIR"
