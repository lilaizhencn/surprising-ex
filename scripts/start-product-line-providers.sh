#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${ACTION:-start}"
PRODUCT_LINE="${PRODUCT_LINE:-LINEAR_PERPETUAL}"
PRODUCT_TOPICS_ENABLED="${PRODUCT_TOPICS_ENABLED:-true}"
PORT_OFFSET="${PORT_OFFSET:-0}"
SERVICES="${SERVICES:-candlestick index-price mark-price trading-entry matching account margin-ops edge}"
BUILD_SERVICES="${BUILD_SERVICES:-false}"
WAIT_HEALTH="${WAIT_HEALTH:-true}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
LOG_DIR="${LOG_DIR:-}"
JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:-}"
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/surprising_exchange}"
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-surprising}"
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-surprising}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"

product_slug() {
  echo "$1" | tr '[:upper:]_' '[:lower:]-'
}

validate_product_line() {
  case "$1" in
    SPOT|LINEAR_PERPETUAL|INVERSE_PERPETUAL|LINEAR_DELIVERY|INVERSE_DELIVERY|OPTION) ;;
    *)
      echo "Unsupported PRODUCT_LINE: $1" >&2
      exit 1
      ;;
  esac
}

validate_port_offset() {
  case "${PORT_OFFSET}" in
    ''|*[!0-9]*)
      echo "PORT_OFFSET must be a non-negative integer: ${PORT_OFFSET}" >&2
      exit 1
      ;;
  esac
}

module_for() {
  case "$1" in
    candlestick) echo "surprising-candlestick/surprising-candlestick-provider" ;;
    price) echo "surprising-price/surprising-price-provider" ;;
    index-price) echo "surprising-price/surprising-index-price-provider" ;;
    mark-price) echo "surprising-price/surprising-mark-price-provider" ;;
    trading-entry) echo "surprising-trading/surprising-trading-entry-provider" ;;
    order) echo "surprising-trading/surprising-order-provider" ;;
    matching) echo "surprising-trading/surprising-matching-provider" ;;
    trigger) echo "surprising-trading/surprising-trigger-provider" ;;
    account) echo "surprising-account/surprising-account-provider" ;;
    risk) echo "surprising-margin-ops/surprising-risk-provider" ;;
    margin-ops) echo "surprising-margin-ops/surprising-margin-ops-provider" ;;
    liquidation) echo "surprising-margin-ops/surprising-liquidation-provider" ;;
    funding) echo "surprising-margin-ops/surprising-funding-provider" ;;
    insurance) echo "surprising-margin-ops/surprising-insurance-provider" ;;
    adl) echo "surprising-margin-ops/surprising-adl-provider" ;;
    edge) echo "surprising-edge/surprising-edge-provider" ;;
    websocket) echo "surprising-websocket/surprising-websocket-provider" ;;
    gateway) echo "surprising-gateway/surprising-gateway-provider" ;;
    market-maker) echo "surprising-market-maker/surprising-market-maker-provider" ;;
    *)
      echo "Unknown service: $1" >&2
      exit 1
      ;;
  esac
}

artifact_for() {
  basename "$(module_for "$1")"
}

base_port_for() {
  case "$1" in
    candlestick) echo 9081 ;;
    price) echo 9082 ;;
    index-price) echo 9082 ;;
    mark-price) echo 9083 ;;
    trading-entry) echo 9084 ;;
    order) echo 9084 ;;
    matching) echo 9085 ;;
    account) echo 9086 ;;
    risk) echo 9087 ;;
    margin-ops) echo 9088 ;;
    liquidation) echo 9088 ;;
    funding) echo 9089 ;;
    insurance) echo 9090 ;;
    adl) echo 9091 ;;
    edge) echo 9094 ;;
    websocket) echo 9093 ;;
    gateway) echo 9094 ;;
    trigger) echo 9095 ;;
    market-maker) echo 9096 ;;
    *)
      echo "Unknown service: $1" >&2
      exit 1
      ;;
  esac
}

supports_funding() {
  [[ "${PRODUCT_LINE}" == "LINEAR_PERPETUAL" || "${PRODUCT_LINE}" == "INVERSE_PERPETUAL" ]]
}

supports_margin_services() {
  [[ "${PRODUCT_LINE}" != "SPOT" ]]
}

service_requested() {
  local needle="$1"
  local service
  for service in ${SERVICES}; do
    if [[ "${service}" == "${needle}" ]]; then
      return 0
    fi
  done
  return 1
}

find_jar() {
  local module="$1"
  local artifact
  artifact="$(basename "${module}")"
  local candidates=("${ROOT_DIR}/${module}/target/${artifact}-"*.jar)
  local jar
  for jar in "${candidates[@]}"; do
    [[ -f "${jar}" ]] || continue
    [[ "${jar}" == *"-sources.jar" || "${jar}" == *"-javadoc.jar" || "${jar}" == *".original" ]] && continue
    echo "${jar}"
    return
  done
  echo "Missing jar for ${module}; run BUILD_SERVICES=true $0 or mvn -q -pl :${artifact} -am -DskipTests package" >&2
  exit 1
}

build_service() {
  local service="$1"
  local artifact
  artifact="$(artifact_for "${service}")"
  mvn -q -pl ":${artifact}" -am -DskipTests package
}

common_env() {
  local service="$1"
  local port="$2"
  local slug="$3"
  printf '%s\n' \
    "SERVER_PORT=${port}" \
    "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}" \
    "SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME}" \
    "SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD}" \
    "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,prometheus" \
    "INSTANCE_PRODUCT_LINE=${PRODUCT_LINE}" \
    "INSTANCE_PRODUCT_SLUG=${slug}" \
    "INSTANCE_SERVICE=${service}"
}

service_env() {
  local service="$1"
  local slug="$2"
  case "${service}" in
    candlestick)
      printf '%s\n' \
        "SURPRISING_CANDLESTICK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_CANDLESTICK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_CANDLESTICK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_CANDLESTICK_KAFKA_APPLICATION_ID=surprising-candlestick-${slug}-v1" \
        "SURPRISING_CANDLESTICK_STREAM_STATE_DIR=${ROOT_DIR}/data/kafka-streams/${slug}/candlestick"
      ;;
    index-price)
      printf '%s\n' \
        "SURPRISING_PRICE_INDEX_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_PRICE_INDEX_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_PRICE_INDEX_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_PRICE_INDEX_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-index"
      ;;
    price)
      printf '%s\n' \
        "SURPRISING_PRICE_INDEX_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_PRICE_INDEX_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_PRICE_INDEX_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_PRICE_INDEX_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-index" \
        "SURPRISING_PRICE_MARK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_PRICE_MARK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_PRICE_MARK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_PRICE_MARK_KAFKA_GROUP_ID=surprising-mark-price-${slug}-v1" \
        "SURPRISING_PRICE_MARK_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-mark"
      ;;
    mark-price)
      printf '%s\n' \
        "SURPRISING_PRICE_MARK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_PRICE_MARK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_PRICE_MARK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_PRICE_MARK_KAFKA_GROUP_ID=surprising-mark-price-${slug}-v1" \
        "SURPRISING_PRICE_MARK_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-mark"
      ;;
    trading-entry)
      local entry_port
      entry_port=$(( $(base_port_for trading-entry) + PORT_OFFSET ))
      printf '%s\n' \
        "SURPRISING_CLIENTS_ORDER_BASE_URL=http://localhost:${entry_port}" \
        "SURPRISING_CLIENTS_TRIGGER_BASE_URL=http://localhost:${entry_port}" \
        "SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_TRADING_ORDER_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_TRADING_ORDER_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_GROUP_ID=surprising-trigger-${slug}-v1"
      ;;
    order)
      printf '%s\n' \
        "SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_TRADING_ORDER_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_TRADING_ORDER_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}"
      ;;
    matching)
      printf '%s\n' \
        "SURPRISING_TRADING_MATCHING_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_TRADING_MATCHING_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_TRADING_MATCHING_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_TRADING_MATCHING_KAFKA_GROUP_ID=surprising-matching-${slug}-v1" \
        "SURPRISING_TRADING_MATCHING_KAFKA_CLIENT_ID=surprising-matching-${slug}-${HOSTNAME:-local}-$$" \
        "SURPRISING_TRADING_MATCHING_ENGINE_EXCHANGE_ID=surprising-${slug}"
      ;;
    trigger)
      printf '%s\n' \
        "SURPRISING_TRADING_TRIGGER_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_TRADING_TRIGGER_KAFKA_GROUP_ID=surprising-trigger-${slug}-v1"
      ;;
    account)
      printf '%s\n' \
        "SURPRISING_ACCOUNT_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_ACCOUNT_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_ACCOUNT_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_ACCOUNT_KAFKA_GROUP_ID=surprising-account-${slug}-v1" \
        "SURPRISING_ACCOUNT_KAFKA_CLIENT_ID=surprising-account-${slug}-${HOSTNAME:-local}-$$"
      ;;
    risk)
      printf '%s\n' \
        "SURPRISING_RISK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_RISK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_RISK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_RISK_KAFKA_GROUP_ID=surprising-risk-${slug}-v1" \
        "SURPRISING_RISK_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-risk"
      ;;
    margin-ops)
      local funding_enabled=false
      if supports_funding; then
        funding_enabled=true
      fi
      local margin_ops_port
      margin_ops_port=$(( $(base_port_for margin-ops) + PORT_OFFSET ))
      printf '%s\n' \
        "SURPRISING_CLIENTS_RISK_BASE_URL=http://localhost:${margin_ops_port}" \
        "SURPRISING_RISK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_RISK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_RISK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_RISK_KAFKA_GROUP_ID=surprising-risk-${slug}-v1" \
        "SURPRISING_RISK_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-risk" \
        "SURPRISING_LIQUIDATION_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_LIQUIDATION_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_LIQUIDATION_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_LIQUIDATION_KAFKA_GROUP_ID=surprising-liquidation-${slug}-v1" \
        "SURPRISING_FUNDING_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_FUNDING_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_FUNDING_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_FUNDING_CALCULATION_ENABLED=${funding_enabled}" \
        "SURPRISING_FUNDING_SETTLEMENT_ENABLED=${funding_enabled}" \
        "SURPRISING_FUNDING_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-funding" \
        "SURPRISING_INSURANCE_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_INSURANCE_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_INSURANCE_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_INSURANCE_KAFKA_GROUP_ID=surprising-insurance-${slug}-v1" \
        "SURPRISING_ADL_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_ADL_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}"
      ;;
    liquidation)
      printf '%s\n' \
        "SURPRISING_LIQUIDATION_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_LIQUIDATION_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_LIQUIDATION_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_LIQUIDATION_KAFKA_GROUP_ID=surprising-liquidation-${slug}-v1"
      ;;
    funding)
      printf '%s\n' \
        "SURPRISING_FUNDING_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_FUNDING_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_FUNDING_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_FUNDING_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-funding"
      ;;
    insurance)
      printf '%s\n' \
        "SURPRISING_INSURANCE_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_INSURANCE_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_INSURANCE_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_INSURANCE_KAFKA_GROUP_ID=surprising-insurance-${slug}-v1"
      ;;
    adl)
      printf '%s\n' \
        "SURPRISING_ADL_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_ADL_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}"
      ;;
    websocket)
      printf '%s\n' \
        "SURPRISING_WEBSOCKET_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_WEBSOCKET_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_WEBSOCKET_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_WEBSOCKET_KAFKA_GROUP_ID=surprising-websocket-${slug}-${HOSTNAME:-local}-$$"
      ;;
    edge)
      printf '%s\n' \
        "SURPRISING_WEBSOCKET_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_WEBSOCKET_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_WEBSOCKET_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_WEBSOCKET_KAFKA_GROUP_ID=surprising-edge-websocket-${slug}-${HOSTNAME:-local}-$$"
      ;;
    market-maker)
      local mark_price_port=9083
      if service_requested price && ! service_requested mark-price; then
        mark_price_port=9082
      fi
      mark_price_port=$((mark_price_port + PORT_OFFSET))
      printf '%s\n' \
        "SURPRISING_MARKET_MAKER_ENGINE_NODE_ID=${HOSTNAME:-local}-${slug}-market-maker" \
        "SURPRISING_CLIENTS_MARK_PRICE_BASE_URL=http://localhost:${mark_price_port}"
      ;;
  esac
}

pid_file() {
  echo "${LOG_DIR}/$1.pid"
}

stop_service() {
  local service="$1"
  local file
  file="$(pid_file "${service}")"
  if [[ ! -f "${file}" ]]; then
    echo "${service}: no pid file"
    return
  fi
  local pid
  pid="$(cat "${file}")"
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}"
    echo "${service}: stopped pid ${pid}"
  fi
  rm -f "${file}"
}

wait_health() {
  local service="$1"
  local port="$2"
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  until curl -fsS "http://localhost:${port}/actuator/health" | grep -q '"status":"UP"'; do
    if ((SECONDS >= deadline)); then
      echo "${service}: health check timed out on port ${port}; see ${LOG_DIR}/${service}.log" >&2
      tail -n 80 "${LOG_DIR}/${service}.log" >&2 || true
      exit 1
    fi
    sleep 1
  done
}

start_service() {
  local service="$1"
  if [[ "${service}" =~ ^(price|index-price|mark-price|risk|margin-ops|liquidation|insurance|adl)$ ]] && ! supports_margin_services; then
    echo "${service}: skipped for ${PRODUCT_LINE}"
    return
  fi
  if [[ "${service}" == "funding" ]] && ! supports_funding; then
    echo "${service}: skipped for ${PRODUCT_LINE}"
    return
  fi
  local slug module jar port
  slug="$(product_slug "${PRODUCT_LINE}")"
  module="$(module_for "${service}")"
  port=$(( $(base_port_for "${service}") + PORT_OFFSET ))
  if [[ "${BUILD_SERVICES}" == "true" ]]; then
    build_service "${service}"
  fi
  jar="$(find_jar "${module}")"
  local env_values=()
  local env_value
  while IFS= read -r env_value; do
    env_values+=("${env_value}")
  done < <(common_env "${service}" "${port}" "${slug}"; service_env "${service}" "${slug}")
  (
    cd "${ROOT_DIR}"
    # shellcheck disable=SC2086
    env "${env_values[@]}" "${JAVA_BIN}" ${JAVA_OPTS} -jar "${jar}"
  ) >"${LOG_DIR}/${service}.log" 2>&1 &
  local pid=$!
  echo "${pid}" >"$(pid_file "${service}")"
  echo "${service}: started pid ${pid} port ${port} productLine ${PRODUCT_LINE}"
  if [[ "${WAIT_HEALTH}" == "true" ]]; then
    wait_health "${service}" "${port}"
  fi
}

validate_product_line "${PRODUCT_LINE}"
validate_port_offset

PRODUCT_SLUG="$(product_slug "${PRODUCT_LINE}")"
if [[ -z "${LOG_DIR}" ]]; then
  LOG_DIR="${ROOT_DIR}/.local-logs/product-lines/${PRODUCT_SLUG}"
fi
mkdir -p "${LOG_DIR}"

case "${ACTION}" in
  start)
    for service in ${SERVICES}; do
      start_service "${service}"
    done
    ;;
  stop)
    for service in ${SERVICES}; do
      stop_service "${service}"
    done
    ;;
  *)
    echo "Unsupported ACTION: ${ACTION}; use start or stop" >&2
    exit 1
    ;;
esac
