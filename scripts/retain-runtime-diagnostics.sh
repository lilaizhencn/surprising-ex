#!/usr/bin/env bash
set -euo pipefail
# Only operational logs/finished recordings: never Core Archive, checkpoint, snapshot or data stores.
RUNTIME_DIR="${RUNTIME_DIR:-/var/lib/surprising/linear-perpetual/runtime/linear-perpetual-single-node}"
ROTATE_CONFIG="${ROTATE_CONFIG:-/etc/logrotate-surprising-runtime.conf}"
ROTATE_STATE="${ROTATE_STATE:-/var/lib/logrotate/surprising-runtime.status}"
[[ -d "$RUNTIME_DIR/logs" ]] || exit 0
logrotate --state "$ROTATE_STATE" "$ROTATE_CONFIG"
# Enforce 48 hours also when no later rollover occurs. Never unlink a currently open base log.
find "$RUNTIME_DIR/logs" -maxdepth 1 -type f \
  \( -name '*-application.*.log.gz' -o -name '*.log.[0-9]*.gz' -o -name '*-gc.log.[0-9]' \) \
  -mmin +2880 -delete
if [[ -d "$RUNTIME_DIR/jfr" ]]; then
  # Repository chunks in subdirectories belong to running JVMs; only finished top-level dumps are aged.
  find "$RUNTIME_DIR/jfr" -maxdepth 1 -type f -name '*.jfr' -mmin +2880 -delete
fi
