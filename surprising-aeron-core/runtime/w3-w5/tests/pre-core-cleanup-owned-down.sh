#!/usr/bin/env bash
set -euo pipefail

if [[ "${FAKE_RUNNER_MODE:-false}" == true && "${1:-}" == down ]]; then
  printf 'called\n' > "$DOWN_CALLED_FILE"
  printf 'CLEANUP=PASS runId=%s\n' "$RUN_ID"
  exit 0
fi

SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCENARIO="$SCRIPT_DIR/../scenarios/w5-export-projection.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-w3w5-pre-core-cleanup.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT INT TERM

extract_function() {
  local name="$1"
  awk -v name="$name" '
    $0 ~ "^" name "\\(\\) \\{" { in_function=1 }
    in_function { print }
    in_function && /^}$/ { exit }
  ' "$SCENARIO"
}

eval "$(extract_function drain_owned_core_wrappers)"
eval "$(extract_function cleanup)"

run_cleanup_case() (
  set -euo pipefail
  local mode="$1"
  RUN_ID="pre-core-$mode"
  RUN_DIR="$TEST_ROOT/$mode/run"
  ATTEMPT_DIR="$TEST_ROOT/$mode/attempt"
  LOCK_DIR="$TEST_ROOT/$mode/lock"
  JARS_DIR="$TEST_ROOT/$mode/jars"
  BEFORE_UNRELATED="$TEST_ROOT/$mode/unrelated.before"
  AFTER_UNRELATED="$TEST_ROOT/$mode/unrelated.after"
  DOWN_CALLED_FILE="$TEST_ROOT/$mode/down.called"
  COMPOSE_DOWN_CALLED_FILE="$TEST_ROOT/$mode/compose-down.called"
  COMPOSE_PROJECT_NAME="surprising-w3w5-pre-core-$mode"
  CORE_SERVICES=(core-node0 core-node1 core-node2)
  CORE_DRAIN_TIMEOUT_SECONDS=1
  CLEANUP_DONE=0
  BOOT_JARS_PREPARED=0
  mkdir -p "$RUN_DIR/pids" "$ATTEMPT_DIR" "$JARS_DIR" "$LOCK_DIR"
  printf '%s\n' "$RUN_ID" > "$RUN_DIR/owner"
  printf '%s\n' "$RUN_ID" > "$LOCK_DIR/owner"
  [[ "$mode" != partial ]] || printf '999999\n' > "$RUN_DIR/pids/core-node0.pid"
  printf 'stable\n' > "$BEFORE_UNRELATED"

  export FAKE_RUNNER_MODE=true DOWN_CALLED_FILE RUN_ID
  RUNNER="$SCRIPT_PATH"

  docker() {
    if [[ "${1:-}" == ps && ! -f "$DOWN_CALLED_FILE" ]]; then
      printf 'owned-container\n'
    fi
  }
  compose() {
    [[ "${1:-}" != down ]] || printf 'called\n' > "$COMPOSE_DOWN_CALLED_FILE"
  }
  record_inventory() { :; }
  restore_boot_jars() { :; }
  verify_task17_cleanup() { :; }
  remove_core_data() { :; }
  unrelated_snapshot() { printf 'stable\n'; }

  cleanup
)

set +e
absent_output="$(run_cleanup_case absent 2>&1)"
absent_status=$?
set -e
if [[ "$absent_status" != 0 || ! -f "$TEST_ROOT/absent/down.called" || \
      "$absent_output" != *'CORE_DRAIN=SKIPPED reason=ALL_CORE_PID_FILES_ABSENT'* || \
      "$absent_output" != *'CLEANUP=PASS runId=pre-core-absent'* ]]; then
  printf 'FAIL cleanup before Core startup must skip an entirely absent Core PID set and call owned run.sh down: status=%s downCalled=%s output=%s\n' \
    "$absent_status" "$(if [[ -f "$TEST_ROOT/absent/down.called" ]]; then printf yes; else printf no; fi)" "$absent_output" >&2
  exit 1
fi
printf 'PASS pre-Core cleanup skips all-absent Core PID set and calls owned run.sh down\n'

set +e
partial_output="$(run_cleanup_case partial 2>&1)"
partial_status=$?
set -e
if [[ "$partial_status" == 0 || -f "$TEST_ROOT/partial/down.called" || -f "$TEST_ROOT/partial/compose-down.called" || \
      "$partial_output" != *'ERROR=CORE_DRAIN_PID_SET_PARTIAL expected=3 actual=1'* || \
      "$partial_output" != *'CLEANUP_FAIL_CLOSED=PASS reason=CORE_DRAIN_FAILED'* ]]; then
  printf 'FAIL partial Core PID set must fail closed without calling owned run.sh down: status=%s downCalled=%s output=%s\n' \
    "$partial_status" "$(if [[ -f "$TEST_ROOT/partial/down.called" ]]; then printf yes; else printf no; fi)" "$partial_output" >&2
  exit 1
fi
printf 'PASS partial Core PID set fails closed without calling owned run.sh down\n'
printf 'PRE_CORE_CLEANUP_OWNED_DOWN=PASS\n'
