#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/../run.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-w3w5-ownership.XXXXXX")"
FOREIGN_PID=''
FOREIGN_CONTAINER=''
FOREIGN_VOLUME="surprising-w3w5-foreign-volume-$RUN_ID"
PROJECT_FOREIGN_CONTAINER=''
PROJECT_FOREIGN_VOLUME=''
LOCK_ROOT="$TEST_ROOT/lock-case"

cleanup() {
  [[ -z "$FOREIGN_PID" ]] || kill "$FOREIGN_PID" 2>/dev/null || true
  [[ -z "$FOREIGN_CONTAINER" ]] || docker rm -f "$FOREIGN_CONTAINER" >/dev/null 2>&1 || true
  [[ -z "$PROJECT_FOREIGN_CONTAINER" ]] || docker rm -f "$PROJECT_FOREIGN_CONTAINER" >/dev/null 2>&1 || true
  [[ -z "$PROJECT_FOREIGN_VOLUME" ]] || docker volume rm "$PROJECT_FOREIGN_VOLUME" >/dev/null 2>&1 || true
  docker volume rm "$FOREIGN_VOLUME" >/dev/null 2>&1 || true
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT INT TERM

expect_refusal() {
  local expected="$1"
  shift
  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || { printf 'expected refusal: %s\n' "$expected" >&2; exit 1; }
  [[ "$output" == *"$expected"* ]] || { printf 'missing refusal %s in: %s\n' "$expected" "$output" >&2; exit 1; }
  printf 'REFUSAL=PASS expected=%s exit=%s\n' "$expected" "$status"
}

expect_refusal RUN_ID_REQUIRED env -u RUN_ID PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false RUNTIME_ROOT="$TEST_ROOT/absent" "$RUNNER" dry-run
expect_refusal PRODUCT_LINE_REFUSED env RUN_ID="$RUN_ID-spot" PRODUCT_LINE=SPOT WALLET_ENABLED=false RUNTIME_ROOT="$TEST_ROOT/spot" "$RUNNER" dry-run
expect_refusal WALLET_REFUSED env RUN_ID="$RUN_ID-wallet" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=true RUNTIME_ROOT="$TEST_ROOT/wallet" "$RUNNER" dry-run

occupied_port=39781
while lsof -nP -iTCP:"$occupied_port" -sTCP:LISTEN >/dev/null 2>&1; do occupied_port=$((occupied_port + 1)); done
"$SCRIPT_DIR/../scenarios/common.sh" health-server occupied "$occupied_port" >/dev/null 2>&1 &
occupied_pid=$!
for _ in 1 2 3 4 5; do nc -z 127.0.0.1 "$occupied_port" >/dev/null 2>&1 && break; sleep 1; done
expect_refusal "PORT_OCCUPIED port=$occupied_port" env RUN_ID="$RUN_ID-port" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false RUNTIME_ROOT="$TEST_ROOT/port" INSTRUMENT_PORT="$occupied_port" "$RUNNER" dry-run
kill "$occupied_pid"
wait "$occupied_pid" 2>/dev/null || true

mkdir -p "$LOCK_ROOT/linear-perpetual.lock"
printf 'another-run\n' > "$LOCK_ROOT/linear-perpetual.lock/owner"
expect_refusal CONCURRENT_RUNTIME_REFUSED env RUN_ID="$RUN_ID-lock" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false RUNTIME_ROOT="$LOCK_ROOT" "$RUNNER" dry-run

container_case="$RUN_ID-container"
container_project="surprising-w3w5-$container_case"
PROJECT_FOREIGN_CONTAINER="$(docker run -d --label "com.docker.compose.project=$container_project" --label 'com.surprising.runtime.run-id=foreign' postgres:16 sleep 300)"
expect_refusal 'OWNERSHIP_REFUSED container=' env RUN_ID="$container_case" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false RUNTIME_ROOT="$TEST_ROOT/container" "$RUNNER" dry-run
docker rm -f "$PROJECT_FOREIGN_CONTAINER" >/dev/null
PROJECT_FOREIGN_CONTAINER=''

volume_case="$RUN_ID-volume"
volume_project="surprising-w3w5-$volume_case"
project_foreign_volume="${volume_project}_foreign"
PROJECT_FOREIGN_VOLUME="$project_foreign_volume"
docker volume create --label "com.docker.compose.project=$volume_project" --label 'com.surprising.runtime.run-id=foreign' "$project_foreign_volume" >/dev/null
expect_refusal 'OWNERSHIP_REFUSED volume=' env RUN_ID="$volume_case" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false RUNTIME_ROOT="$TEST_ROOT/volume" "$RUNNER" dry-run
docker volume rm "$project_foreign_volume" >/dev/null
PROJECT_FOREIGN_VOLUME=''

FOREIGN_CONTAINER="$(docker run -d --label "com.surprising.runtime.run-id=foreign-$RUN_ID" postgres:16 sleep 300)"
docker volume create --label "com.surprising.runtime.run-id=foreign-$RUN_ID" "$FOREIGN_VOLUME" >/dev/null
sleep 300 &
FOREIGN_PID=$!

owned_root="$TEST_ROOT/ownership"
owned_dir="$owned_root/runs/$RUN_ID"
mkdir -p "$owned_dir/pids"
printf '%s\n' "$RUN_ID" > "$owned_dir/owner"
printf '%s\n' "$FOREIGN_PID" > "$owned_dir/pids/foreign.pid"
expect_refusal "OWNERSHIP_REFUSED pid=$FOREIGN_PID service=foreign" env RUN_ID="$RUN_ID" PRODUCT_LINE=LINEAR_PERPETUAL WALLET_ENABLED=false RUNTIME_ROOT="$owned_root" "$RUNNER" down

kill -0 "$FOREIGN_PID"
[[ "$(docker inspect --format '{{.State.Running}}' "$FOREIGN_CONTAINER")" == true ]]
docker volume inspect "$FOREIGN_VOLUME" >/dev/null
printf 'FOREIGN_PID_SURVIVED=PASS pid=%s\n' "$FOREIGN_PID"
printf 'FOREIGN_CONTAINER_SURVIVED=PASS container=%s\n' "$FOREIGN_CONTAINER"
printf 'FOREIGN_VOLUME_SURVIVED=PASS volume=%s\n' "$FOREIGN_VOLUME"
printf 'OWNERSHIP_SAFE_CLEANUP=PASS\n'
