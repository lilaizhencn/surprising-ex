#!/usr/bin/env bash
set -euo pipefail

start_line_subset() {
  local line="${1:-}"
  [[ "$line" =~ ^(SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION)$ ]] \
    || { printf 'ERROR=PRODUCT_LINE_REFUSED line=%s\n' "$line" >&2; return 2; }
  [[ -z "${W4_ACTIVE_LINE:-}" ]] || { printf 'ERROR=LINE_ALREADY_ACTIVE line=%s\n' "$W4_ACTIVE_LINE" >&2; return 2; }
  local run_id="${RUN_ID}-${W4_LINE_INDEX}-${line}"
  run_id="${run_id//_/-}"
  W4_ACTIVE_LINE="$line"
  W4_ACTIVE_RUN_ID="$run_id"
  W4_LINE_LOG="${W4_RUN_DIR}/${W4_LINE_INDEX}-${line}.runtime.log"
  mkdir -p "$W4_RUN_DIR"
  if [[ "${W4_STATIC_ONLY:-false}" == true ]]; then
    printf 'LINE_START=STATIC productLine=%s runId=%s maker=REQUIRED wallet=ABSENT\n' "$line" "$run_id" \
      > "$W4_LINE_LOG"
    return 0
  fi
  local command_name=up
  [[ "$line" == LINEAR_PERPETUAL ]] || command_name=line-up
  if ! RUN_ID="$run_id" PRODUCT_LINE="$line" WALLET_ENABLED=false \
      TASK_RUN_FRESH="${TASK_RUN_FRESH:-false}" "$W4_RUNNER" "$command_name" >"$W4_LINE_LOG" 2>&1; then
    return 1
  fi
  grep -q '^UP=PASS ' "$W4_LINE_LOG" || { printf 'ERROR=LINE_START_RECEIPT_MISSING line=%s\n' "$line" >&2; return 1; }
  grep -q 'READY=maker' "$W4_LINE_LOG" || { printf 'ERROR=MAKER_NOT_LAST line=%s\n' "$line" >&2; return 1; }
}

stop_line_subset() {
  local line="${1:-}"
  [[ "${W4_ACTIVE_LINE:-}" == "$line" ]] || {
    printf 'ERROR=LINE_STOP_MISMATCH expected=%s actual=%s\n' "$line" "${W4_ACTIVE_LINE:-unset}" >&2
    return 2
  }
  if [[ "${W4_STATIC_ONLY:-false}" == true ]]; then
    printf 'LINE_STOP=STATIC productLine=%s cleanup=PASS mainWorktree=PROTECTED\n' "$line" >> "$W4_LINE_LOG"
  else
    if ! RUN_ID="$W4_ACTIVE_RUN_ID" PRODUCT_LINE="$line" WALLET_ENABLED=false \
        TASK_RUN_FRESH="${TASK_RUN_FRESH:-false}" "$W4_RUNNER" down >>"$W4_LINE_LOG" 2>&1; then
      return 1
    fi
    grep -q '^CLEANUP=PASS ' "$W4_LINE_LOG" || { printf 'ERROR=CLEANUP_RECEIPT_MISSING line=%s\n' "$line" >&2; return 1; }
    grep -q '^MAIN_WORKTREE_PROTECTED=PASS' "$W4_LINE_LOG" || {
      printf 'ERROR=MAIN_WORKTREE_RECEIPT_MISSING line=%s\n' "$line" >&2; return 1;
    }
  fi
  W4_ACTIVE_LINE=''
  W4_ACTIVE_RUN_ID=''
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  command_name="${1:-}"
  shift || true
  case "$command_name" in
    health-server)
      service_name="${1:?service name is required}"
      port="${2:?port is required}"
      while true; do
        printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nREADY service=%s' \
          "$service_name" | nc -l 127.0.0.1 "$port"
      done
      ;;
    idle)
      while true; do sleep 30; done
      ;;
    *)
      printf 'usage: %s {health-server <name> <port>|idle}\n' "${BASH_SOURCE[0]}" >&2
      exit 2
      ;;
  esac
fi
