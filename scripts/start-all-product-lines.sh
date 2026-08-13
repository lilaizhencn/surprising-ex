#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER_SCRIPT="${ROOT_DIR}/scripts/start-product-line-providers.sh"
ACTION="${ACTION:-start}"
WAIT_HEALTH="${WAIT_HEALTH:-true}"
DRY_RUN="${DRY_RUN:-false}"
TEST_PROFILE="${TEST_PROFILE:-local-low}"
PORT_BLOCK_SIZE="${PORT_BLOCK_SIZE:-100}"
SERVICES="${SERVICES:-instrument candlestick index-price mark-price order matching account risk liquidation funding insurance adl trigger websocket market-maker}"

declare -a PRODUCT_LINES=(
  SPOT
  LINEAR_PERPETUAL
  INVERSE_PERPETUAL
  LINEAR_DELIVERY
  INVERSE_DELIVERY
  OPTION
)

declare -a route_names=(
  instrument candlestick price-index price-mark trading trading-leverage trading-market
  trading-trades trading-trigger account risk liquidation funding insurance adl market-maker
)

route_env_name() {
  local route="$1"
  route="${route//-/_}"
  printf '%s' "${route}" | tr '[:lower:]' '[:upper:]'
}

port_for_route() {
  local route="$1"
  local offset="$2"
  case "${route}" in
    instrument) echo $((9080 + offset)) ;;
    candlestick) echo $((9081 + offset)) ;;
    price-index) echo $((9082 + offset)) ;;
    price-mark) echo $((9083 + offset)) ;;
    trading|trading-leverage) echo $((9084 + offset)) ;;
    trading-market|trading-trades) echo $((9085 + offset)) ;;
    trading-trigger) echo $((9095 + offset)) ;;
    account) echo $((9086 + offset)) ;;
    risk) echo $((9087 + offset)) ;;
    liquidation) echo $((9088 + offset)) ;;
    funding) echo $((9089 + offset)) ;;
    insurance) echo $((9090 + offset)) ;;
    adl) echo $((9091 + offset)) ;;
    market-maker) echo $((9096 + offset)) ;;
    *) return 1 ;;
  esac
}

declare -a gateway_routes=()
for index in "${!PRODUCT_LINES[@]}"; do
  product_line="${PRODUCT_LINES[index]}"
  offset=$((index * PORT_BLOCK_SIZE))
  for route in "${route_names[@]}"; do
    gateway_routes+=(
      "GATEWAY_ROUTE_$(route_env_name "${route}")_${product_line}_BASE_URL=http://localhost:$(port_for_route "${route}" "${offset}")"
    )
  done
done

run_line() {
  local product_line="$1"
  local index="$2"
  local offset=$((index * PORT_BLOCK_SIZE))
  local line_services="${SERVICES}"
  if [[ "${product_line}" == "SPOT" ]]; then
    line_services="${line_services} gateway"
  fi
  if [[ "${DRY_RUN}" == "true" ]]; then
    printf 'PRODUCT_LINE=%s PORT_OFFSET=%s SERVICES=%s\n' "${product_line}" "${offset}" "${line_services}"
    return
  fi
  env "${gateway_routes[@]}" \
    ACTION="${ACTION}" \
    PRODUCT_LINE="${product_line}" \
    PORT_OFFSET="${offset}" \
    SERVICES="${line_services}" \
    WAIT_HEALTH="${WAIT_HEALTH}" \
    TEST_PROFILE="${TEST_PROFILE}" \
    "${PROVIDER_SCRIPT}"
}

for index in "${!PRODUCT_LINES[@]}"; do
  run_line "${PRODUCT_LINES[index]}" "${index}"
done
