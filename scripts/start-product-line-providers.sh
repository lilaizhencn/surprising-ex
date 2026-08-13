#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${ACTION:-start}"
PRODUCT_LINE="${PRODUCT_LINE:?必须显式设置 PRODUCT_LINE，例如 SPOT、LINEAR_PERPETUAL、LINEAR_DELIVERY 或 OPTION}"
SERVICES_EXPLICIT="${SERVICES+x}"
JAVA_OPTS_EXPLICIT="${JAVA_OPTS+x}"
TEST_PROFILE="${TEST_PROFILE:-auto}"
source "${ROOT_DIR}/scripts/test-environment-profile.sh"
test_profile_detect
PRODUCT_TOPICS_ENABLED="${PRODUCT_TOPICS_ENABLED:-true}"
PORT_OFFSET="${PORT_OFFSET:-0}"
if [[ -z "${SERVICES_EXPLICIT}" ]]; then
  SERVICES="$(test_profile_services "${PRODUCT_LINE}" "${TEST_SCENARIO:-trade}")"
fi
BUILD_SERVICES="${BUILD_SERVICES:-false}"
NATIVE_IMAGE="${NATIVE_IMAGE:-false}"
NATIVE_BINARY_DIR="${NATIVE_BINARY_DIR:-}"
NATIVE_RUNTIME_ARGS="${NATIVE_RUNTIME_ARGS:-}"
WAIT_HEALTH="${WAIT_HEALTH:-true}"
WAIT_DEPENDENCIES="${WAIT_DEPENDENCIES:-true}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
LOCAL_HOST="${LOCAL_HOST:-127.0.0.1}"
LOG_DIR="${LOG_DIR:-}"
JAVA_BIN="${JAVA_BIN:-java}"
if [[ -z "${JAVA_OPTS_EXPLICIT}" ]]; then
  JAVA_OPTS="$(test_profile_java_opts)"
fi
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://${LOCAL_HOST}:5432/surprising_exchange}"
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-surprising}"
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-surprising}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-${LOCAL_HOST}:9092}"

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
    instrument) echo "surprising-instrument/surprising-instrument-provider" ;;
    candlestick) echo "surprising-candlestick/surprising-candlestick-provider" ;;
    index-price) echo "surprising-price/surprising-index-price-provider" ;;
    mark-price) echo "surprising-price/surprising-mark-price-provider" ;;
    order) echo "surprising-trading/surprising-order-provider" ;;
    matching) echo "surprising-trading/surprising-matching-provider" ;;
    trigger) echo "surprising-trading/surprising-trigger-provider" ;;
    account) echo "surprising-account/surprising-account-provider" ;;
    risk) echo "surprising-risk/surprising-risk-provider" ;;
    liquidation) echo "surprising-liquidation/surprising-liquidation-provider" ;;
    funding) echo "surprising-funding/surprising-funding-provider" ;;
    insurance) echo "surprising-insurance/surprising-insurance-provider" ;;
    adl) echo "surprising-adl/surprising-adl-provider" ;;
    websocket) echo "surprising-websocket/surprising-websocket-provider" ;;
    gateway) echo "surprising-gateway" ;;
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
    instrument) echo 9080 ;;
    candlestick) echo 9081 ;;
    index-price) echo 9082 ;;
    mark-price) echo 9083 ;;
    order) echo 9084 ;;
    matching) echo 9085 ;;
    account) echo 9086 ;;
    risk) echo 9087 ;;
    liquidation) echo 9088 ;;
    funding) echo 9089 ;;
    insurance) echo 9090 ;;
    adl) echo 9091 ;;
    websocket) echo "${WEBSOCKET_PORT:-9097}" ;;
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

find_native_binary() {
  local module="$1"
  local artifact
  artifact="$(basename "${module}")"
  local binary_dir="${NATIVE_BINARY_DIR:-${ROOT_DIR}/${module}/target}"
  local candidates=("${binary_dir}/${artifact}" "${binary_dir}/${artifact}-"*)
  local binary
  for binary in "${candidates[@]}"; do
    [[ -f "${binary}" && -x "${binary}" ]] || continue
    echo "${binary}"
    return
  done
  echo "Missing Native Image binary for ${module}; run BUILD_SERVICES=true NATIVE_IMAGE=true PRODUCT_LINE=${PRODUCT_LINE} $0" >&2
  exit 1
}

build_service() {
  local service="$1"
  local artifact module
  artifact="$(artifact_for "${service}")"
  module="$(module_for "${service}")"
  if [[ "${NATIVE_IMAGE}" == "true" ]]; then
    mvn -q -pl ":${artifact}" -am -Pnative -Dmaven.test.skip=true install
    "${ROOT_DIR}/scripts/check-native-aot-metadata.sh" "${module}" "${artifact}"
    mvn -q -pl ":${artifact}" -Pnative -Dmaven.test.skip=true native:compile
  else
    mvn -q -pl ":${artifact}" -am -DskipTests package
  fi
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
    "PRODUCT_LINE=${PRODUCT_LINE}" \
    "PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
    "SURPRISING_PRICE_CONSUMER_PRODUCT_LINE=${PRODUCT_LINE}" \
    "SURPRISING_PRICE_CONSUMER_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
    "INSTANCE_PRODUCT_LINE=${PRODUCT_LINE}" \
    "INSTANCE_PRODUCT_SLUG=${slug}" \
    "INSTANCE_SERVICE=${service}"
  if [[ -n "${SERVER_TOMCAT_THREADS_MAX:-}" ]]; then
    printf '%s\n' "SERVER_TOMCAT_THREADS_MAX=${SERVER_TOMCAT_THREADS_MAX}"
  fi
  if [[ -n "${SERVER_TOMCAT_THREADS_MIN_SPARE:-}" ]]; then
    printf '%s\n' "SERVER_TOMCAT_THREADS_MIN_SPARE=${SERVER_TOMCAT_THREADS_MIN_SPARE}"
  fi
  if [[ -n "${SERVER_TOMCAT_ACCEPT_COUNT:-}" ]]; then
    printf '%s\n' "SERVER_TOMCAT_ACCEPT_COUNT=${SERVER_TOMCAT_ACCEPT_COUNT}"
  fi
  if [[ -n "${SERVER_TOMCAT_MAX_CONNECTIONS:-}" ]]; then
    printf '%s\n' "SERVER_TOMCAT_MAX_CONNECTIONS=${SERVER_TOMCAT_MAX_CONNECTIONS}"
  fi
}

service_env() {
  local service="$1"
  local slug="$2"
  local instrument_port=$((9080 + PORT_OFFSET))
  local candlestick_port=$((9081 + PORT_OFFSET))
  local index_price_port=$((9082 + PORT_OFFSET))
  local mark_price_port=$((9083 + PORT_OFFSET))
  local order_port=$((9084 + PORT_OFFSET))
  local matching_port=$((9085 + PORT_OFFSET))
  local account_port=$((9086 + PORT_OFFSET))
  local risk_port=$((9087 + PORT_OFFSET))
  local liquidation_port=$((9088 + PORT_OFFSET))
  local funding_port=$((9089 + PORT_OFFSET))
  local insurance_port=$((9090 + PORT_OFFSET))
  local adl_port=$((9091 + PORT_OFFSET))
  local trigger_port=$((9095 + PORT_OFFSET))
  printf '%s\n' \
    "SURPRISING_CLIENTS_INSTRUMENT_BASE_URL=http://${LOCAL_HOST}:${instrument_port}" \
    "SURPRISING_CLIENTS_CANDLESTICK_BASE_URL=http://${LOCAL_HOST}:${candlestick_port}" \
    "SURPRISING_CLIENTS_INDEX_PRICE_BASE_URL=http://${LOCAL_HOST}:${index_price_port}" \
    "SURPRISING_CLIENTS_MARK_PRICE_BASE_URL=http://${LOCAL_HOST}:${mark_price_port}" \
    "SURPRISING_CLIENTS_ORDER_BASE_URL=http://${LOCAL_HOST}:${order_port}" \
    "SURPRISING_CLIENTS_MATCHING_BASE_URL=http://${LOCAL_HOST}:${matching_port}" \
    "SURPRISING_CLIENTS_ACCOUNT_BASE_URL=http://${LOCAL_HOST}:${account_port}" \
    "SURPRISING_CLIENTS_RISK_BASE_URL=http://${LOCAL_HOST}:${risk_port}" \
    "SURPRISING_CLIENTS_LIQUIDATION_BASE_URL=http://${LOCAL_HOST}:${liquidation_port}" \
    "SURPRISING_CLIENTS_FUNDING_BASE_URL=http://${LOCAL_HOST}:${funding_port}" \
    "SURPRISING_CLIENTS_INSURANCE_BASE_URL=http://${LOCAL_HOST}:${insurance_port}" \
    "SURPRISING_CLIENTS_ADL_BASE_URL=http://${LOCAL_HOST}:${adl_port}" \
    "SURPRISING_CLIENTS_TRIGGER_BASE_URL=http://${LOCAL_HOST}:${trigger_port}"
  case "${service}" in
    instrument)
      printf '%s\n' \
        "SURPRISING_INSTRUMENT_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}"
      ;;
    candlestick)
      printf '%s\n' \
        "SURPRISING_CANDLESTICK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_CANDLESTICK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_CANDLESTICK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_CANDLESTICK_KAFKA_APPLICATION_ID=surprising-candlestick-${slug}-${PORT_OFFSET}-v1" \
        "SURPRISING_CANDLESTICK_KAFKA_APPLICATION_ID_OVERRIDE=surprising-candlestick-${slug}-${PORT_OFFSET}-v2" \
        "SURPRISING_CANDLESTICK_STREAM_STATE_DIR=${ROOT_DIR}/data/kafka-streams/${slug}/candlestick" \
        "SURPRISING_CANDLESTICK_KAFKA_APPLICATION_SERVER=${LOCAL_HOST}:0"
      ;;
    index-price)
      printf '%s\n' \
        "SURPRISING_PRICE_INDEX_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_PRICE_INDEX_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_PRICE_INDEX_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_PRICE_INDEX_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-index"
      ;;
    mark-price)
      printf '%s\n' \
        "SURPRISING_PRICE_MARK_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_PRICE_MARK_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_PRICE_MARK_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "SURPRISING_PRICE_MARK_KAFKA_GROUP_ID=surprising-mark-price-${slug}-v1" \
        "SURPRISING_PRICE_MARK_COORDINATION_NODE_ID=${HOSTNAME:-local}-${slug}-mark"
      ;;
    order)
      local order_node_id="${ORDER_WAL_NODE_ID:-$((PORT_OFFSET / 100 + 1))}"
      if [[ "${order_node_id}" -lt 0 || "${order_node_id}" -gt 1023 ]]; then
        echo "ORDER_WAL_NODE_ID must be in 0..1023: ${order_node_id}" >&2
        exit 1
      fi
      printf '%s\n' \
        "SURPRISING_TRADING_ORDER_KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS}" \
        "SURPRISING_TRADING_ORDER_KAFKA_PRODUCT_LINE=${PRODUCT_LINE}" \
        "SURPRISING_TRADING_ORDER_KAFKA_PRODUCT_TOPICS_ENABLED=${PRODUCT_TOPICS_ENABLED}" \
        "ORDER_WAL_NODE_ID=${order_node_id}"
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
    gateway)
      local route_values=()
      local route_name route_port route_env_name
      for route_name in instrument candlestick price-index price-mark trading trading-leverage trading-market trading-trades trading-trigger account risk liquidation funding insurance adl market-maker; do
        case "${route_name}" in
          instrument) route_port="${instrument_port}" ;;
          candlestick) route_port="${candlestick_port}" ;;
          price-index) route_port="${index_price_port}" ;;
          price-mark) route_port="${mark_price_port}" ;;
          trading|trading-leverage) route_port="${order_port}" ;;
          trading-market|trading-trades) route_port="${matching_port}" ;;
          trading-trigger) route_port="${trigger_port}" ;;
          account) route_port="${account_port}" ;;
          risk) route_port="${risk_port}" ;;
          liquidation) route_port="${liquidation_port}" ;;
          funding) route_port="${funding_port}" ;;
          insurance) route_port="${insurance_port}" ;;
          adl) route_port="${adl_port}" ;;
          market-maker) route_port=$((9096 + PORT_OFFSET)) ;;
        esac
        route_env_name="${route_name//-/_}"
        route_env_name="$(printf '%s' "${route_env_name}" | tr '[:lower:]' '[:upper:]')"
        route_values+=("GATEWAY_ROUTE_${route_env_name}_${PRODUCT_LINE}_BASE_URL=http://${LOCAL_HOST}:${route_port}")
      done
      printf '%s\n' "${route_values[@]}"
      ;;
    market-maker)
      local mark_price_port=9083
      if service_requested index-price && ! service_requested mark-price; then
        mark_price_port=9082
      fi
      mark_price_port=$((mark_price_port + PORT_OFFSET))
      printf '%s\n' \
        "SURPRISING_MARKET_MAKER_ENGINE_NODE_ID=${HOSTNAME:-local}-${slug}-market-maker" \
        "SURPRISING_CLIENTS_MARK_PRICE_BASE_URL=http://${LOCAL_HOST}:${mark_price_port}"
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
  until curl -fsS "http://${LOCAL_HOST}:${port}/actuator/health/readiness" | grep -q '"status":"UP"'; do
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
  if [[ "${service}" =~ ^(index-price|mark-price|risk|liquidation|insurance|adl)$ ]] && ! supports_margin_services; then
    echo "${service}: skipped for ${PRODUCT_LINE}"
    return
  fi
  if [[ "${service}" == "funding" ]] && ! supports_funding; then
    echo "${service}: skipped for ${PRODUCT_LINE}"
    return
  fi
  local slug module launch_artifact port
  slug="$(product_slug "${PRODUCT_LINE}")"
  module="$(module_for "${service}")"
  port=$(( $(base_port_for "${service}") + PORT_OFFSET ))
  if [[ "${BUILD_SERVICES}" == "true" ]]; then
    build_service "${service}"
  fi
  if [[ "${WAIT_DEPENDENCIES}" == "true" && "${service}" != "instrument" ]] \
        && service_requested instrument; then
    wait_health instrument $((9080 + PORT_OFFSET))
  fi
  if [[ "${NATIVE_IMAGE}" == "true" ]]; then
    launch_artifact="$(find_native_binary "${module}")"
  else
    launch_artifact="$(find_jar "${module}")"
  fi
  local env_values=()
  local service_java_args=()
  local env_value
  if [[ "${NATIVE_IMAGE}" == "true" ]]; then
    read -r -a service_java_args <<<"${NATIVE_RUNTIME_ARGS}"
  elif [[ -z "${JAVA_OPTS_EXPLICIT}" ]]; then
    while IFS= read -r env_value; do
      service_java_args+=("${env_value}")
    done < <(test_profile_java_args_for_service "${service}")
  else
    read -r -a service_java_args <<<"${JAVA_OPTS}"
  fi
  while IFS= read -r env_value; do
    env_values+=("${env_value}")
  done < <(common_env "${service}" "${port}" "${slug}"; service_env "${service}" "${slug}")
  (
    cd "${ROOT_DIR}"
    if [[ "${NATIVE_IMAGE}" == "true" ]]; then
      if ((${#service_java_args[@]} > 0)); then
        env "${env_values[@]}" "${launch_artifact}" "${service_java_args[@]}"
      else
        env "${env_values[@]}" "${launch_artifact}"
      fi
    else
      env "${env_values[@]}" "${JAVA_BIN}" "${service_java_args[@]}" -jar "${launch_artifact}"
    fi
  ) >"${LOG_DIR}/${service}.log" 2>&1 &
  local pid=$!
  echo "${pid}" >"$(pid_file "${service}")"
  echo "${service}: started pid ${pid} port ${port} productLine ${PRODUCT_LINE}"
  if [[ "${WAIT_HEALTH}" == "true" ]]; then
    wait_health "${service}" "${port}"
  fi
}

validate_provider_budget() {
  local count=0 total_heap=0 service heap_mb max_heap_mb=0
  for service in ${SERVICES}; do
    count=$((count + 1))
    if [[ -n "${JAVA_OPTS_EXPLICIT}" && "${JAVA_OPTS}" =~ -Xmx([1-9][0-9]*)([mMgG]) ]]; then
      heap_mb="${BASH_REMATCH[1]}"
      [[ "${BASH_REMATCH[2]}" =~ [gG] ]] && heap_mb=$((heap_mb * 1024))
    else
      heap_mb="$(test_profile_service_heap_mb "${service}")"
    fi
    total_heap=$((total_heap + heap_mb))
    if ((heap_mb > max_heap_mb)); then
      max_heap_mb="${heap_mb}"
    fi
  done
  ((count > 0)) || { echo "SERVICES must contain at least one provider" >&2; exit 1; }
  ((count <= TEST_MAX_PROVIDER_PROCESSES)) || { echo "provider count ${count} exceeds TEST_MAX_PROVIDER_PROCESSES=${TEST_MAX_PROVIDER_PROCESSES}" >&2; exit 1; }
  local heap_budget_percent=60
  case "${TEST_PROFILE}" in local-low) heap_budget_percent=45 ;; local-standard) heap_budget_percent=55 ;; esac
  if ((TEST_MEMORY_MB > 0 && total_heap > TEST_MEMORY_MB * heap_budget_percent / 100)) && [[ "${ALLOW_RESOURCE_OVERRIDE}" != "true" ]]; then
    echo "provider heap total ${total_heap}MB exceeds ${TEST_PROFILE} budget ${heap_budget_percent}% of ${TEST_MEMORY_MB}MB" >&2
    exit 1
  fi
  if [[ -n "${JAVA_OPTS_EXPLICIT}" && "${max_heap_mb}" -gt "${TEST_JVM_HEAP_MB}" && "${ALLOW_RESOURCE_OVERRIDE}" != "true" ]]; then
    echo "explicit JAVA_OPTS -Xmx${max_heap_mb}m exceeds TEST_JVM_HEAP_MB=${TEST_JVM_HEAP_MB}; set ALLOW_RESOURCE_OVERRIDE=true only with approval" >&2
    exit 1
  fi
}

validate_product_line "${PRODUCT_LINE}"
validate_port_offset

# 所有业务模块都依赖 instrument JVM 快照；启动时即使调用方只指定了部分服务，也必须先启动它。
if [[ "${ACTION}" == "start" ]] && ! service_requested instrument; then
  instrument_port=$((9080 + PORT_OFFSET))
  if curl -fsS "http://${LOCAL_HOST}:${instrument_port}/actuator/health/readiness" \
      | grep -q '"status":"UP"'; then
    echo "instrument: already ready on port ${instrument_port}; reusing existing provider"
  else
    SERVICES="instrument ${SERVICES}"
  fi
fi
validate_provider_budget

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
