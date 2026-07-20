#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if ! command -v rg >/dev/null 2>&1; then
  echo "ripgrep (rg) is required" >&2
  exit 2
fi

TABLE_PATTERN='(account_balances|account_product_balances|account_positions|account_position_margins|account_deficits|account_ledger_entries|account_product_ledger_entries)'

violations="$(
  rg -n -i \
    --glob '!surprising-account/**' \
    --glob '**/src/main/java/**/*.java' \
    "(insert[[:space:]]+into|update|delete[[:space:]]+from)[[:space:]]+${TABLE_PATTERN}" \
    . || true
)"

service_bypasses="$(
  rg -n \
    --glob '**/src/main/java/**/*.java' \
    'accountService\.(adjustBalance|adminAdjustBalance|adjustProductBalance|adminAdjustProductBalance|transfer|adjustPositionMargin|updatePositionMode|processTradeSide|processExpiringPosition)\(' \
    surprising-account/surprising-account-provider/src/main/java \
    | rg -v 'AccountUserCommandProcessor\.java' || true
)"

if [[ -n "${violations}" || -n "${service_bypasses}" ]]; then
  echo "Account single-writer boundary violation detected:" >&2
  [[ -n "${violations}" ]] && echo "${violations}" >&2
  [[ -n "${service_bypasses}" ]] && echo "${service_bypasses}" >&2
  exit 1
fi

echo "Account single-writer boundary OK"
