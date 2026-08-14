#!/usr/bin/env bash
set -euo pipefail

PRODUCT_LINE="${1:?product line is required}"
USER_ID="${2:?user id is required}"
ACCOUNT_TYPE="${3:?account type is required}"
ASSET="${4:?asset is required}"
AMOUNT_UNITS="${5:?amount units is required}"
REFERENCE_ID="${6:?reference id is required}"
ACCOUNT_URL="${ACCOUNT_URL:-http://localhost:9086}"
INTERNAL_SECRET="${ACCOUNT_INTERNAL_SERVICE_SECRET:-product-line-smoke-internal-secret}"
REASON="${BALANCE_SEED_REASON:-PRODUCT_LINE_MULTI_SYMBOL_STRESS}"
TIMESTAMP="$(date +%s)"

length_prefixed() {
  printf '%s:%s' "${#1}" "$1"
}

canonical="$(length_prefixed surprising-gateway)$(length_prefixed /api/v1/accounts/admin/product-balance-adjustments)$(length_prefixed "${TIMESTAMP}")$(length_prefixed "${USER_ID}")$(length_prefixed "${ACCOUNT_TYPE}")$(length_prefixed "${ASSET}")$(length_prefixed "${AMOUNT_UNITS}")$(length_prefixed "${REFERENCE_ID}")$(length_prefixed "${REASON}")"
signature="v1=$(printf '%s' "${canonical}" | openssl dgst -sha256 -hmac "${INTERNAL_SECRET}" -binary \
  | openssl base64 -A | tr '+/' '-_' | tr -d '=')"

curl --retry 20 --retry-delay 1 --retry-max-time 90 --retry-all-errors -fsS -X POST \
  "${ACCOUNT_URL}/api/v1/accounts/admin/product-balance-adjustments" \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Service: surprising-gateway' \
  -H "X-Internal-Timestamp: ${TIMESTAMP}" \
  -H "X-Internal-Signature: ${signature}" \
  -H 'X-Internal-Audience: /api/v1/accounts/admin/product-balance-adjustments' \
  -d "{\"userId\":${USER_ID},\"accountType\":\"${ACCOUNT_TYPE}\",\"asset\":\"${ASSET}\",\"amountUnits\":${AMOUNT_UNITS},\"referenceId\":\"${REFERENCE_ID}\",\"reason\":\"${REASON}\"}" \
  >/dev/null

