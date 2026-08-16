#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCENARIO="$SCRIPT_DIR/../scenarios/w5-export-projection.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/surprising-w3w5-gateway-readiness.XXXXXX")"

cleanup() {
  local pid_file pid
  while IFS= read -r pid_file; do
    [[ -f "$pid_file" ]] || continue
    pid="$(<"$pid_file")"
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || continue
    kill "$pid" 2>/dev/null || true
  done < <(find "$TEST_ROOT" -name '*.pid' -type f 2>/dev/null)
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT INT TERM

extract_function() {
  awk '
    /^start_owned_process\(\) \{/ { in_function=1 }
    in_function { print }
    in_function && /^}$/ { exit }
  ' "$SCENARIO"
}

eval "$(extract_function)"

run_process_case() (
  set -euo pipefail
  local service="$1" port="$2" ready_second="$3" command="$4"
  RUN_ID="gateway-readiness-$service"
  RUN_DIR="$TEST_ROOT/$service-$ready_second"
  WRAPPER_SCRIPT='exec "$@"'
  mkdir -p "$RUN_DIR/pids" "$RUN_DIR/logs"
  SECONDS=0

  fail() { printf 'ERROR=%s\n' "$*" >&2; exit 2; }
  mark_ready() { printf 'READY=%s elapsed=%s\n' "$1" "$SECONDS"; }
  port_ready() { (( SECONDS >= ready_second )); }
  sleep() {
    SECONDS=$((SECONDS + $1))
    /bin/sleep 0.002
  }

  start_owned_process "$service" "$port" "$command" 300
)

set +e
gateway_output="$(run_process_case gateway 9094 50 /bin/sleep 2>&1)"
gateway_status=$?
set -e
if [[ "$gateway_status" != 0 || "$gateway_output" != *'READY=gateway elapsed=50'* ]]; then
  printf 'FAIL Gateway cold-start readiness must have a bounded 90s budget and accept a healthy port at 50s: status=%s output=%s\n' \
    "$gateway_status" "$gateway_output" >&2
  exit 1
fi
printf 'PASS Gateway cold-start readiness accepts a healthy port at 50s within its 90s budget\n'

set +e
timeout_output="$(run_process_case gateway 9094 999 /bin/sleep 2>&1)"
timeout_status=$?
set -e
if [[ "$timeout_status" == 0 || "$timeout_output" != *'ERROR=READINESS_TIMEOUT service=gateway port=9094 timeout=90s'* ]]; then
  printf 'FAIL Gateway cold-start readiness budget must remain explicitly bounded at 90s: status=%s output=%s\n' \
    "$timeout_status" "$timeout_output" >&2
  exit 1
fi
printf 'PASS Gateway cold-start readiness budget remains explicitly bounded at 90s\n'

for service in exporter projector; do
  service_output="$(run_process_case "$service" '' 999 /bin/sleep 2>&1)"
  if [[ "$service_output" != *"READY=$service elapsed=1"* ]]; then
    printf 'FAIL no-port process readiness must remain bounded to one liveness tick: service=%s output=%s\n' \
      "$service" "$service_output" >&2
    exit 1
  fi
  printf 'PASS no-port process readiness remains bounded: service=%s elapsed=1\n' "$service"
done

set +e
exit_output="$(run_process_case gateway 9094 999 /usr/bin/false 2>&1)"
exit_status=$?
set -e
if [[ "$exit_status" == 0 || "$exit_output" != *'ERROR=PROCESS_EXITED service=gateway'* || \
      "$exit_output" == *'READINESS_TIMEOUT'* ]]; then
  printf 'FAIL Gateway process exit must surface before readiness timeout: status=%s output=%s\n' \
    "$exit_status" "$exit_output" >&2
  exit 1
fi
printf 'PASS Gateway process exit remains visible before readiness timeout\n'
printf 'GATEWAY_COLD_START_READINESS=PASS gatewayBudget=90s defaultBudget=45s\n'
