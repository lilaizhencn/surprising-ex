#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${ROOT_DIR}/scripts/product-line-websocket-smoke.mjs"

test -s "${SCRIPT}"
node --check "${SCRIPT}"
test "$(node -p 'typeof WebSocket')" = "function"

rg -q 'ws://localhost:9094/ws/v1' "${SCRIPT}"
rg -q 'channel: "depth"' "${SCRIPT}"
rg -q 'channel, symbol: "\*", productLine' "${SCRIPT}"
rg -q 'anonymous-private' "${SCRIPT}"
rg -q 'message\.op === "pong"' "${SCRIPT}"
rg -q 'message\.op === "unsubscribed"' "${SCRIPT}"
rg -q 'private event leaked' "${SCRIPT}"

echo "product-line websocket contract PASS"
