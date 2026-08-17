#!/usr/bin/env bash
set -euo pipefail

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
