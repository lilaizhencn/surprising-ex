#!/usr/bin/env bash
set -euo pipefail

PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
SECURITY_EXECUTE="${SECURITY_EXECUTE:-false}"
SECURITY_AUTHORIZED="${SECURITY_AUTHORIZED:-false}"
SECURITY_TARGET="${SECURITY_TARGET:-}"
SECURITY_TARGET_ALLOWLIST="${SECURITY_TARGET_ALLOWLIST:-}"
SECURITY_CONCURRENCY_CASES="${SECURITY_CONCURRENCY_CASES:-idempotent-order cancel-race}"
SECURITY_CONCURRENCY="${SECURITY_CONCURRENCY:-8}"
SECURITY_CONCURRENCY_USER_ID="${SECURITY_CONCURRENCY_USER_ID:-7000000001}"
SECURITY_CONCURRENCY_SYMBOL="${SECURITY_CONCURRENCY_SYMBOL:-BTC-USDT}"
SECURITY_CONCURRENCY_ORDER_ID="${SECURITY_CONCURRENCY_ORDER_ID:-}"
SECURITY_CONCURRENCY_CLIENT_ORDER_ID="${SECURITY_CONCURRENCY_CLIENT_ORDER_ID:-security-idempotent-order}"
SECURITY_CONCURRENCY_PRICE_TICKS="${SECURITY_CONCURRENCY_PRICE_TICKS:-600000}"
SECURITY_CONCURRENCY_QUANTITY_STEPS="${SECURITY_CONCURRENCY_QUANTITY_STEPS:-1}"
SECURITY_CONCURRENCY_RESULT_FILE="${SECURITY_CONCURRENCY_RESULT_FILE:-}"
SECURITY_CONCURRENCY_EVIDENCE_DIR="${SECURITY_CONCURRENCY_EVIDENCE_DIR:-/tmp/surprising-security-concurrency-$(date -u +%Y%m%dT%H%M%SZ)}"

fail() { echo "SECURITY_CONCURRENCY_FAIL: $*" >&2; exit 1; }

case "${PRODUCT_LINE}" in SPOT|LINEAR_PERPETUAL|LINEAR_DELIVERY|OPTION) ;; *) fail "unsupported product line ${PRODUCT_LINE}" ;; esac
case "${SECURITY_EXECUTE}" in true|false) ;; *) fail "SECURITY_EXECUTE must be true or false" ;; esac
case "${SECURITY_AUTHORIZED}" in true|false) ;; *) fail "SECURITY_AUTHORIZED must be true or false" ;; esac
[[ "${SECURITY_CONCURRENCY}" =~ ^[1-9][0-9]*$ ]] || fail "SECURITY_CONCURRENCY must be positive"

origin_of() {
  printf '%s' "$1" | sed -E 's#^(https?://[^/]+).*$#\1#'
}

validate_target() {
  [[ "${SECURITY_AUTHORIZED}" == "true" ]] || fail "SECURITY_AUTHORIZED=true is required"
  [[ -n "${SECURITY_TARGET}" && -n "${SECURITY_TARGET_ALLOWLIST}" ]] || fail "SECURITY_TARGET and SECURITY_TARGET_ALLOWLIST are required"
  [[ "${SECURITY_TARGET}" =~ ^https?://[^[:space:]]+$ ]] || fail "SECURITY_TARGET must be an http(s) URL"
  local forbidden target_origin candidate matched=false
  for forbidden in '*' '?' '$' '`' ';' '|' '&' '(' ')' '\\' '<' '>' "'" '"'; do
    [[ "${SECURITY_TARGET}" != *"${forbidden}"* ]] || fail "SECURITY_TARGET contains forbidden characters"
  done
  target_origin="$(origin_of "${SECURITY_TARGET}")"
  for candidate in ${SECURITY_TARGET_ALLOWLIST}; do
    [[ "${candidate}" == "${target_origin}" ]] && matched=true
  done
  [[ "${matched}" == "true" ]] || fail "SECURITY_TARGET origin is not in SECURITY_TARGET_ALLOWLIST"
}

mkdir -p "${SECURITY_CONCURRENCY_EVIDENCE_DIR}"
{
  echo "product_line=${PRODUCT_LINE}"
  echo "security_execute=${SECURITY_EXECUTE}"
  echo "security_concurrency_cases=${SECURITY_CONCURRENCY_CASES}"
  echo "concurrency=${SECURITY_CONCURRENCY}"
  echo "target_origin=$(origin_of "${SECURITY_TARGET:-not-set}")"
} >"${SECURITY_CONCURRENCY_EVIDENCE_DIR}/manifest.env"

if [[ "${SECURITY_EXECUTE}" == "false" ]]; then
  {
    echo "case\tconcurrency\trequest\texpected invariant"
    for concurrency_case in ${SECURITY_CONCURRENCY_CASES}; do
      case "${concurrency_case}" in
        idempotent-order) echo "idempotent-order\t${SECURITY_CONCURRENCY}\tPOST /api/v1/gateway/trading with same clientOrderId\t<=1 order fact and <=1 freeze" ;;
        cancel-race) echo "cancel-race\t${SECURITY_CONCURRENCY}\tPOST /api/v1/gateway/trading/cancel for same order\tone terminal transition and one unlock" ;;
        *) fail "unsupported concurrency case ${concurrency_case}" ;;
      esac
    done
  } >"${SECURITY_CONCURRENCY_EVIDENCE_DIR}/concurrency-plan.tsv"
  echo "SECURITY_CONCURRENCY DRY_RUN PLAN_ONLY evidence=${SECURITY_CONCURRENCY_EVIDENCE_DIR}"
  exit 0
fi

validate_target
validate_business_result() {
  [[ -r "${SECURITY_CONCURRENCY_RESULT_FILE}" ]] || fail "SECURITY_CONCURRENCY_RESULT_FILE is required for business oracle"
  local required
  for required in duplicate_order_facts=0 duplicate_freeze_facts=0 duplicate_ledger_entries=0 funds_reconcile=PASS positions_reconcile=PASS cross_user_leaks=0; do
    rg -q "^${required}$" "${SECURITY_CONCURRENCY_RESULT_FILE}" || fail "missing concurrency oracle ${required}"
  done
  if [[ " ${SECURITY_CONCURRENCY_CASES} " == *" idempotent-order "* ]]; then
    rg -q '^idempotent_order_facts=1$' "${SECURITY_CONCURRENCY_RESULT_FILE}" || fail "missing concurrency oracle idempotent_order_facts=1"
  fi
  if [[ " ${SECURITY_CONCURRENCY_CASES} " == *" cancel-race "* ]]; then
    rg -q '^cancel_terminal_transitions=1$' "${SECURITY_CONCURRENCY_RESULT_FILE}" || fail "missing concurrency oracle cancel_terminal_transitions=1"
  fi
}
for concurrency_case in ${SECURITY_CONCURRENCY_CASES}; do
  case "${concurrency_case}" in idempotent-order|cancel-race) ;; *) fail "unsupported concurrency case ${concurrency_case}" ;; esac
done

request_dir="${SECURITY_CONCURRENCY_EVIDENCE_DIR}/requests"
mkdir -p "${request_dir}"
api_url="${SECURITY_TARGET%/}/api/v1/gateway/trading"
cancel_url="${SECURITY_TARGET%/}/api/v1/gateway/trading/cancel"

run_parallel() {
  local case_name="$1" url="$2" payload="$3"
  local pids=() index
  for ((index = 1; index <= SECURITY_CONCURRENCY; index++)); do
    (
      curl --connect-timeout 3 --max-time 30 -sS -o "${request_dir}/${case_name}-${index}.body" \
        -w '%{http_code}\n' -X POST "${url}" \
        -H 'Content-Type: application/json' \
        -H "X-User-Id: ${SECURITY_CONCURRENCY_USER_ID}" \
        -H "X-Product-Line: ${PRODUCT_LINE}" \
        -H "X-Trace-Id: security-concurrency-${case_name}-${index}" \
        -d "${payload}" >"${request_dir}/${case_name}-${index}.status" 2>"${request_dir}/${case_name}-${index}.error"
    ) &
    pids+=("$!")
  done
  local pid
  for pid in "${pids[@]}"; do
    wait "${pid}" || true
  done
}

for concurrency_case in ${SECURITY_CONCURRENCY_CASES}; do
  case "${concurrency_case}" in
    idempotent-order)
      payload="{\"userId\":${SECURITY_CONCURRENCY_USER_ID},\"clientOrderId\":\"${SECURITY_CONCURRENCY_CLIENT_ORDER_ID}\",\"symbol\":\"${SECURITY_CONCURRENCY_SYMBOL}\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"priceTicks\":${SECURITY_CONCURRENCY_PRICE_TICKS},\"quantitySteps\":${SECURITY_CONCURRENCY_QUANTITY_STEPS},\"marginMode\":\"CROSS\",\"positionSide\":\"NET\",\"reduceOnly\":false,\"postOnly\":false}"
      run_parallel "${concurrency_case}" "${api_url}" "${payload}"
      success_count="$(awk '$1 ~ /^2/ {count++} END {print count+0}' "${request_dir}"/${concurrency_case}-*.status)"
      failure_count="$(awk '$1 ~ /^5/ {count++} END {print count+0}' "${request_dir}"/${concurrency_case}-*.status)"
      ((success_count >= 1 && failure_count == 0)) || fail "idempotent-order HTTP results success=${success_count} server_errors=${failure_count}"
      echo "idempotent-order HTTP invariant PASS" >"${SECURITY_CONCURRENCY_EVIDENCE_DIR}/idempotent-order.result"
      ;;
    cancel-race)
      [[ "${SECURITY_CONCURRENCY_ORDER_ID}" =~ ^[1-9][0-9]*$ ]] || fail "SECURITY_CONCURRENCY_ORDER_ID is required for cancel-race"
      run_parallel "${concurrency_case}" "${cancel_url}" "{\"userId\":${SECURITY_CONCURRENCY_USER_ID},\"orderId\":${SECURITY_CONCURRENCY_ORDER_ID}}"
      awk '{counts[$1]++} END {for (code in counts) print code, counts[code]}' "${request_dir}"/${concurrency_case}-*.status | sort -n >"${SECURITY_CONCURRENCY_EVIDENCE_DIR}/cancel-race.http-counts"
      failure_count="$(awk '$1 ~ /^5/ {count++} END {print count+0}' "${request_dir}"/${concurrency_case}-*.status)"
      ((failure_count == 0)) || fail "cancel-race returned ${failure_count} server errors"
      echo "cancel-race HTTP results captured; database/order/funds reconciliation required" >"${SECURITY_CONCURRENCY_EVIDENCE_DIR}/cancel-race.result"
      ;;
  esac
done
validate_business_result

echo "SECURITY_CONCURRENCY COMPLETE evidence=${SECURITY_CONCURRENCY_EVIDENCE_DIR}"
