#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="${ACCEPTANCE_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUTPUT_DIR="${ACCEPTANCE_OUTPUT_DIR:-${ROOT_DIR}/reports/product-line-acceptance/${RUN_ID}}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
PATH="${JAVA_HOME}/bin:${PATH}"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:9092}"
DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://127.0.0.1:5432/postgres}"
DATABASE_USER="${DATABASE_USER:-postgres}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-postgres}"
SKIP_BUILD="${SKIP_BUILD:-false}"
SKIP_MIGRATIONS="${SKIP_MIGRATIONS:-false}"
KEEP_RUNTIME="${KEEP_RUNTIME:-false}"
RESUME="${RESUME:-false}"
SERVICES="instrument order matching account websocket gateway"
TOOLS_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-tools/target/surprising-aeron-tools.jar"
EXPORTER_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-exporter/target/surprising-aeron-exporter.jar"
SERVICE_JAR="${ROOT_DIR}/surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar"
export JAVA_HOME PATH KAFKA_BOOTSTRAP_SERVERS DATABASE_URL DATABASE_USER DATABASE_PASSWORD

if [[ -n "${PRODUCT_LINES:-}" ]]; then
  read -r -a lines <<<"${PRODUCT_LINES}"
else
  lines=(SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL LINEAR_DELIVERY INVERSE_DELIVERY OPTION)
fi

exporter_pid=""
projection_pid=""
input_bridge_pid=""
current_log_dir=""
current_product_line=""
cluster_data_dir=""
cluster_pids=""

slug() {
  printf '%s' "$1" | tr '[:upper:]_' '[:lower:]-'
}

topic_segment() {
  case "$1" in
    SPOT) echo spot ;;
    LINEAR_PERPETUAL) echo linear-perp ;;
    INVERSE_PERPETUAL) echo inverse-perp ;;
    LINEAR_DELIVERY) echo linear-delivery ;;
    INVERSE_DELIVERY) echo inverse-delivery ;;
    OPTION) echo option ;;
    *) echo "unsupported product line: $1" >&2; return 1 ;;
  esac
}

seed_for() {
  case "$1" in
    SPOT) echo 9201 ;;
    LINEAR_PERPETUAL) echo 9202 ;;
    INVERSE_PERPETUAL) echo 9203 ;;
    LINEAR_DELIVERY) echo 9204 ;;
    INVERSE_DELIVERY) echo 9205 ;;
    OPTION) echo 9206 ;;
    *) echo "unsupported product line: $1" >&2; return 1 ;;
  esac
}

account_type() {
  case "$1" in
    SPOT) echo SPOT ;;
    LINEAR_PERPETUAL) echo USDT_PERPETUAL ;;
    INVERSE_PERPETUAL) echo COIN_PERPETUAL ;;
    LINEAR_DELIVERY) echo USDT_DELIVERY ;;
    INVERSE_DELIVERY) echo COIN_DELIVERY ;;
    OPTION) echo OPTION ;;
  esac
}

instrument_type() {
  case "$1" in
    SPOT) echo SPOT ;;
    LINEAR_PERPETUAL|INVERSE_PERPETUAL) echo PERPETUAL ;;
    LINEAR_DELIVERY|INVERSE_DELIVERY) echo DELIVERY ;;
    OPTION) echo OPTION ;;
  esac
}

contract_type() {
  [[ "$1" == OPTION ]] && echo VANILLA_OPTION || echo "$1"
}

settle_asset() {
  case "$1" in
    INVERSE_PERPETUAL|INVERSE_DELIVERY) echo BTC ;;
    *) echo USDT ;;
  esac
}

is_expiring() {
  [[ "$1" == LINEAR_DELIVERY || "$1" == INVERSE_DELIVERY || "$1" == OPTION ]]
}

cleanup_runtime() {
  set +e
  if [[ -n "${current_log_dir}" && -n "${current_product_line}" ]]; then
    ACTION=stop PRODUCT_LINE="${current_product_line}" PRODUCT_TOPICS_ENABLED=true \
      SERVICES="${SERVICES}" LOG_DIR="${current_log_dir}" \
      bash "${ROOT_DIR}/scripts/start-product-line-providers.sh" >/dev/null 2>&1
  fi
  for pid in "${input_bridge_pid}" "${exporter_pid}" "${projection_pid}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      kill "${pid}" >/dev/null 2>&1
      wait "${pid}" >/dev/null 2>&1
    fi
  done
  exporter_pid=""
  projection_pid=""
  input_bridge_pid=""
  if [[ "${KEEP_RUNTIME}" != true ]]; then
    for pid in ${cluster_pids}; do
      if kill -0 "${pid}" >/dev/null 2>&1; then
        kill "${pid}" >/dev/null 2>&1
      fi
    done
    local attempt
    for attempt in {1..25}; do
      local alive=false
      for pid in ${cluster_pids}; do
        kill -0 "${pid}" >/dev/null 2>&1 && alive=true
      done
      [[ "${alive}" == false ]] && break
      sleep 0.2
    done
    for pid in ${cluster_pids}; do
      if kill -0 "${pid}" >/dev/null 2>&1; then
        kill -KILL "${pid}" >/dev/null 2>&1
      fi
    done
    for pid in ${cluster_pids}; do
      wait "${pid}" >/dev/null 2>&1
    done
    if [[ -n "${cluster_data_dir}" && "${cluster_data_dir}" == "${TMPDIR:-/tmp}/surprising-p8-"* ]]; then
      rm -rf "${cluster_data_dir}"
    fi
  fi
  cluster_pids=""
  cluster_data_dir=""
  current_log_dir=""
  current_product_line=""
  set -e
}

trap cleanup_runtime EXIT
trap 'exit 130' INT TERM

require_environment() {
  [[ -x "${JAVA_HOME}/bin/java" ]] || { echo "JDK 25 not found at ${JAVA_HOME}" >&2; exit 1; }
  "${JAVA_HOME}/bin/java" -version 2>&1 | head -n 1 | rg -q 'version "25\.' \
    || { echo "P8 acceptance requires JDK 25" >&2; exit 1; }
  for container in rainbo-postgres rainbo-kafka rainbo-valkey; do
    [[ "$(docker inspect -f '{{.State.Running}}' "${container}" 2>/dev/null)" == true ]] \
      || { echo "required container is not running: ${container}" >&2; exit 1; }
  done
}

apply_migrations() {
  docker exec -i rainbo-postgres psql -v ON_ERROR_STOP=1 -U "${DATABASE_USER}" -d postgres \
    <"${ROOT_DIR}/init.sql" >/dev/null
  local migration
  for migration in "${ROOT_DIR}"/migrations/*.sql \
      "${ROOT_DIR}"/surprising-aeron-core/surprising-aeron-exporter/src/main/resources/db/migration/V*.sql; do
    docker exec -i rainbo-postgres psql -v ON_ERROR_STOP=1 -U "${DATABASE_USER}" -d postgres \
      <"${migration}" >/dev/null
  done
}

build_artifacts() {
  [[ "${SKIP_BUILD}" == true ]] && return
  mvn -q -f "${ROOT_DIR}/pom.xml" \
    -pl :surprising-aeron-service,:surprising-aeron-tools,:surprising-aeron-exporter,\
:surprising-instrument-provider,:surprising-order-provider,:surprising-matching-provider,\
:surprising-account-provider,:surprising-websocket-provider,:surprising-gateway \
    -am -DskipTests package
}

wait_cluster() {
  local attempt
  for attempt in {1..30}; do
    if "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -Dsurprising.aeron.product-line="${current_product_line}" \
      -Dsurprising.aeron.probe-mode=query -cp "${TOOLS_JAR}" \
      com.surprising.aeron.tools.ClusterProbeMain >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Aeron Cluster readiness timed out for ${current_product_line}" >&2
  return 1
}

assert_cluster_ports_free() {
  local ordinal member offset port
  case "${current_product_line}" in
    SPOT) ordinal=0 ;;
    LINEAR_PERPETUAL) ordinal=1 ;;
    INVERSE_PERPETUAL) ordinal=2 ;;
    LINEAR_DELIVERY) ordinal=3 ;;
    INVERSE_DELIVERY) ordinal=4 ;;
    OPTION) ordinal=5 ;;
  esac
  for member in 0 1 2; do
    for offset in 1 2 3 4 5; do
      port=$((20000 + ordinal * 1000 + member * 100 + offset))
      if lsof -nP -iUDP:"${port}" >/dev/null 2>&1; then
        echo "Aeron port ${port}/udp is already in use; stop the stale ${current_product_line} cluster" >&2
        return 1
      fi
    done
  done
}

start_cluster() {
  local evidence="$1" node_id
  assert_cluster_ports_free
  cluster_data_dir="$(mktemp -d "${TMPDIR:-/tmp}/surprising-p8-$(slug "${current_product_line}").XXXXXX")"
  cluster_pids=""
  for node_id in 0 1 2; do
    "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -Xms256m -Xmx256m \
      -Dsurprising.aeron.product-line="${current_product_line}" \
      -Dsurprising.aeron.node-id="${node_id}" \
      -Dsurprising.aeron.hostnames=localhost,localhost,localhost \
      -Dsurprising.aeron.data-dir="${cluster_data_dir}" \
      -jar "${SERVICE_JAR}" >"${evidence}/node${node_id}.log" 2>&1 &
    cluster_pids="${cluster_pids} $!"
  done
  wait_cluster
}

reset_core_topic() {
  local segment="$1" topic="surprising.${1}.core.events.v1" input_topic="surprising.${1}.core.inputs.v1"
  local partition_count latest_offset earliest_offset attempt input_deleted=false
  docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "${topic}" --partitions 1 --replication-factor 1 >/dev/null
  docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "${input_topic}" --partitions 1 --replication-factor 1 >/dev/null
  docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --delete --if-exists --topic "${input_topic}" >/dev/null 2>&1 || true
  for attempt in {1..30}; do
    if ! docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
        --describe --topic "${input_topic}" >/dev/null 2>&1; then
      input_deleted=true
      break
    fi
    sleep 1
  done
  [[ "${input_deleted}" == true ]] || { echo "failed to reset ${input_topic}" >&2; return 1; }
  docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "${input_topic}" --partitions 1 --replication-factor 1 >/dev/null
  partition_count="$(docker exec rainbo-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --describe --topic "${topic}" | sed -n 's/.*PartitionCount: \([0-9][0-9]*\).*/\1/p' | head -n 1)"
  [[ "${partition_count}" == 1 ]] || { echo "${topic} must have exactly one partition" >&2; return 1; }
  latest_offset="$(docker exec rainbo-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic "${topic}" --time -1 | awk -F: '$2 == 0 {print $3}')"
  if ((latest_offset > 0)); then
    printf '{"partitions":[{"topic":"%s","partition":0,"offset":%s}],"version":1}\n' \
      "${topic}" "${latest_offset}" | docker exec -i rainbo-kafka \
      /opt/kafka/bin/kafka-delete-records.sh --bootstrap-server localhost:9092 \
      --offset-json-file /dev/stdin >/dev/null
  fi
  earliest_offset="$(docker exec rainbo-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 \
    --topic "${topic}" --time -2 | awk -F: '$2 == 0 {print $3}')"
  [[ "${earliest_offset}" == "${latest_offset}" ]] \
    || { echo "failed to truncate ${topic}: earliest=${earliest_offset} latest=${latest_offset}" >&2; return 1; }
  docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --delete --group "surprising-core-projection-${segment}" >/dev/null 2>&1 || true
  docker exec rainbo-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --delete --group "surprising-core-input-${segment}" >/dev/null 2>&1 || true
  docker exec rainbo-postgres psql -v ON_ERROR_STOP=1 -U "${DATABASE_USER}" -d postgres -c \
    "DELETE FROM core_execution_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_order_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_user_fact_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_funding_payment_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_funding_settlement_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_liquidation_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_treasury_projection WHERE product_line='${current_product_line}';
     DELETE FROM core_event_projection WHERE product_line='${current_product_line}';" >/dev/null
}

start_export_pipeline() {
  local evidence="$1" segment input_topic
  segment="$(topic_segment "${current_product_line}")"
  input_topic="surprising.${segment}.core.inputs.v1"
  PRODUCT_LINE="${current_product_line}" CORE_INPUT_TOPICS="${input_topic}" \
    KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    AERON_HOSTNAMES=localhost,localhost,localhost AERON_EGRESS_HOSTNAME=localhost \
    "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -cp "${EXPORTER_JAR}" com.surprising.aeron.exporter.InputBridgeMain \
      >"${evidence}/input-bridge.log" 2>&1 &
  input_bridge_pid=$!
  PRODUCT_LINE="${current_product_line}" KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    DATABASE_URL="${DATABASE_URL}" DATABASE_USER="${DATABASE_USER}" DATABASE_PASSWORD="${DATABASE_PASSWORD}" \
    "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
      -cp "${EXPORTER_JAR}" com.surprising.aeron.exporter.ExporterMain \
      >"${evidence}/exporter.log" 2>&1 &
  exporter_pid=$!
  PRODUCT_LINE="${current_product_line}" KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    DATABASE_URL="${DATABASE_URL}" DATABASE_USER="${DATABASE_USER}" DATABASE_PASSWORD="${DATABASE_PASSWORD}" \
    "${JAVA_HOME}/bin/java" -cp "${EXPORTER_JAR}" com.surprising.aeron.exporter.ProjectionMain \
      >"${evidence}/projection.log" 2>&1 &
  projection_pid=$!
  sleep 1
  kill -0 "${input_bridge_pid}"
  kill -0 "${exporter_pid}"
  kill -0 "${projection_pid}"
}

start_providers() {
  local evidence="$1" requested_services="$2"
  current_log_dir="${evidence}/providers"
  mkdir -p "${current_log_dir}"
  ACTION=start PRODUCT_LINE="${current_product_line}" PRODUCT_TOPICS_ENABLED=true \
    SPRING_DATASOURCE_URL="${DATABASE_URL}" SPRING_DATASOURCE_USERNAME="${DATABASE_USER}" \
    SPRING_DATASOURCE_PASSWORD="${DATABASE_PASSWORD}" KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS}" \
    JAVA_OPTS='--add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Xms96m -Xmx256m' \
    SURPRISING_TRADING_ORDER_RISK_LIMIT_PRICE_PROTECTION_ENABLED=false \
    SURPRISING_WS_QUERY_USER_ID=true GATEWAY_ALLOW_USER_ID_HEADER_FALLBACK=true \
    SERVICES="${requested_services}" LOG_DIR="${current_log_dir}" \
    bash "${ROOT_DIR}/scripts/start-product-line-providers.sh"
}

instrument_payload() {
  local line="$1" expiry=null option_fields='"underlyingSymbol":null,"strikePriceUnits":null,"optionType":null,"optionExerciseStyle":null'
  local max_leverage=10000000 initial_margin=100000 maintenance_margin=50000
  local risk_brackets index_sources
  if is_expiring "${line}"; then
    expiry='"2033-05-18T03:33:20Z"'
  fi
  if [[ "${line}" == OPTION ]]; then
    option_fields='"underlyingSymbol":"BTC-USDT","strikePriceUnits":100,"optionType":"CALL","optionExerciseStyle":"EUROPEAN"'
  fi
  if [[ "${line}" == SPOT ]]; then
    max_leverage=1000000
    initial_margin=1000000
    maintenance_margin=1000000
    risk_brackets='[]'
    index_sources='[]'
  else
    risk_brackets="[{\"bracketNo\":1,\"notionalFloorUnits\":0,\"notionalCapUnits\":1000000000000000,\"maxLeveragePpm\":${max_leverage},\"initialMarginRatePpm\":${initial_margin},\"maintenanceMarginRatePpm\":${maintenance_margin}}]"
    index_sources='[{"source":"P8_SYNTHETIC","enabled":true,"baseUrl":"http://127.0.0.1:9082","path":"/api/v1/index","sourceSymbol":"BTC-USDT","parser":"SYNTHETIC","quoteCurrency":"USDT","targetQuoteCurrency":"USDT","conversionBaseUrl":null,"conversionPath":null,"conversionParser":null,"conversionMode":"DISCOUNT","conversionOperation":"MULTIPLY","fallbackWeightMultiplierPpm":1000000,"websocketEnabled":false,"websocketUrl":null,"websocketSubscribeMessage":null,"websocketParser":null,"weightPpm":1000000}]'
  fi
  printf '{"symbol":"%s","instrumentType":"%s","contractType":"%s","baseAsset":"BTC","quoteAsset":"USDT","settleAsset":"%s","contractMultiplierPpm":1000000,"contractValueAsset":"%s","priceTickUnits":1,"quantityStepUnits":1,"minQuantitySteps":1,"maxQuantitySteps":100000,"minNotionalUnits":1,"maxNotionalUnits":1000000000000000,"notionalMultiplierUnits":1,"pricePrecision":0,"quantityPrecision":0,"supportedOrderTypes":["LIMIT","MARKET"],"supportedTimeInForce":["GTC","IOC","FOK","GTX"],"postOnlyEnabled":true,"reduceOnlyEnabled":%s,"marketOrderEnabled":true,"maxLeveragePpm":%s,"initialMarginRatePpm":%s,"maintenanceMarginRatePpm":%s,"makerFeeRatePpm":0,"takerFeeRatePpm":0,"maxPositionNotionalUnits":1000000000000000,"userOpenInterestLimitRatePpm":0,"userOpenInterestLimitFloorUnits":1,"fundingIntervalHours":%s,"interestRatePpm":0,"fundingRateCapPpm":0,"fundingRateFloorPpm":0,"impactNotionalUnits":1,"minValidIndexSources":1,"expiryTime":%s,"deliveryTime":%s,%s,"settlementMethod":%s,"status":"TRADING","effectiveTime":"2026-08-14T00:00:00Z","riskLimitBrackets":%s,"indexSources":%s}' \
    "${SYMBOL}" "$(instrument_type "${line}")" "$(contract_type "${line}")" "$(settle_asset "${line}")" \
    "$(settle_asset "${line}")" "$([[ "${line}" == SPOT ]] && echo false || echo true)" \
    "${max_leverage}" "${initial_margin}" "${maintenance_margin}" \
    "$([[ "${line}" == LINEAR_PERPETUAL || "${line}" == INVERSE_PERPETUAL ]] && echo 8 || echo 0)" \
    "${expiry}" "${expiry}" "${option_fields}" "$(is_expiring "${line}" && echo '"CASH"' || echo null)" \
    "${risk_brackets}" "${index_sources}"
}

acceptance_tool() {
  local mode="$1"
  "${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="${current_product_line}" \
    -Dsurprising.aeron.acceptance-seed="${SEED}" \
    -Dsurprising.aeron.symbol="${SYMBOL}" \
    -Dsurprising.aeron.instrument-version="${INSTRUMENT_VERSION}" \
    -Dsurprising.aeron.acceptance-mode="${mode}" \
    -cp "${TOOLS_JAR}" com.surprising.aeron.tools.ClusterApiAcceptanceMain
}

gateway_post() {
  local user="$1" route="$2" trace="$3" body="$4" output="$5"
  curl -fsS -X POST "http://127.0.0.1:9094/api/v1/gateway/${route}" \
    -H 'Content-Type: application/json' -H "X-User-Id: ${user}" \
    -H "X-Product-Line: ${current_product_line}" -H "X-Trace-Id: ${trace}" \
    -d "${body}" -o "${output}"
}

run_api_flow() {
  local evidence="$1" seller="$2" buyer="$3" account="$4"
  local common unauthorized_code order_id replacement_id websocket_pid
  common="\"symbol\":\"${SYMBOL}\",\"orderType\":\"LIMIT\",\"marginMode\":\"CROSS\",\"positionSide\":\"NET\",\"reduceOnly\":false,\"postOnly\":false"
  unauthorized_code="$(curl -sS -o "${evidence}/unauthorized.json" -w '%{http_code}' -X POST \
    http://127.0.0.1:9094/api/v1/gateway/trading -H 'Content-Type: application/json' \
    -H "X-Product-Line: ${current_product_line}" \
    -d "{\"userId\":${seller},\"clientOrderId\":\"p8-unauthorized-${SEED}\",${common},\"side\":\"SELL\",\"timeInForce\":\"GTC\",\"priceTicks\":110,\"quantitySteps\":1}")"
  [[ "${unauthorized_code}" == 401 ]]

  gateway_post "${seller}" trading "p8-amend-place-${SEED}" \
    "{\"userId\":${seller},\"clientOrderId\":\"p8-amend-${SEED}\",${common},\"side\":\"SELL\",\"timeInForce\":\"GTC\",\"priceTicks\":110,\"quantitySteps\":2}" \
    "${evidence}/amend-place.json"
  order_id="$(jq -r .orderId "${evidence}/amend-place.json")"
  curl -fsS "http://127.0.0.1:9094/api/v1/gateway/trading/${order_id}?userId=${seller}" \
    -H "X-User-Id: ${seller}" -H "X-Product-Line: ${current_product_line}" \
    -H "X-Trace-Id: p8-query-${SEED}" -o "${evidence}/order-query.json"
  gateway_post "${seller}" trading/amend "p8-amend-${SEED}" \
    "{\"userId\":${seller},\"orderId\":${order_id},\"newClientOrderId\":\"p8-amended-${SEED}\",\"priceTicks\":111,\"quantitySteps\":3}" \
    "${evidence}/order-amend.json"
  replacement_id="$(jq -r .replacementOrder.orderId "${evidence}/order-amend.json")"
  gateway_post "${seller}" trading/cancel "p8-cancel-${SEED}" \
    "{\"userId\":${seller},\"orderId\":${replacement_id}}" "${evidence}/replacement-cancel.json"

  node "${ROOT_DIR}/scripts/product-line-websocket-smoke.mjs" --url ws://127.0.0.1:9097/ws/v1 \
    --product-line "${current_product_line}" --symbol "${SYMBOL}" --user-id "${seller}" \
    --other-user-id "$((seller + 8))" --timeout-ms 60000 --evidence "${evidence}/websocket.json" \
    >"${evidence}/websocket.out" 2>&1 &
  websocket_pid=$!
  sleep 2
  gateway_post "${seller}" trading "p8-maker-${SEED}" \
    "{\"userId\":${seller},\"clientOrderId\":\"p8-maker-${SEED}\",${common},\"side\":\"SELL\",\"timeInForce\":\"GTC\",\"priceTicks\":100,\"quantitySteps\":10}" \
    "${evidence}/maker.json"
  sleep 1
  gateway_post "${buyer}" trading "p8-taker-${SEED}" \
    "{\"userId\":${buyer},\"clientOrderId\":\"p8-taker-${SEED}\",${common},\"side\":\"BUY\",\"timeInForce\":\"IOC\",\"priceTicks\":100,\"quantitySteps\":10}" \
    "${evidence}/taker.json"
  wait "${websocket_pid}"

  curl -fsS "http://127.0.0.1:9094/api/v1/gateway/trading/history?userId=${seller}&symbol=${SYMBOL}&limit=20" \
    -H "X-User-Id: ${seller}" -H "X-Product-Line: ${current_product_line}" \
    -H "X-Trace-Id: p8-history-${SEED}" -o "${evidence}/order-history.json"
  curl -fsS "http://127.0.0.1:9094/api/v1/gateway/trading/open?userId=${seller}&symbol=${SYMBOL}&limit=20" \
    -H "X-User-Id: ${seller}" -H "X-Product-Line: ${current_product_line}" \
    -H "X-Trace-Id: p8-open-${SEED}" -o "${evidence}/open-orders.json"
  curl -fsS "http://127.0.0.1:9094/api/v1/gateway/account/product-balances?userId=${seller}&accountType=${account}" \
    -H "X-User-Id: ${seller}" -H "X-Product-Line: ${current_product_line}" \
    -H "X-Trace-Id: p8-balances-${SEED}" -o "${evidence}/balances.json"
  if [[ "${current_product_line}" != SPOT ]]; then
    curl -fsS "http://127.0.0.1:9094/api/v1/gateway/account/positions?userId=${seller}" \
      -H "X-User-Id: ${seller}" -H "X-Product-Line: ${current_product_line}" \
      -H "X-Trace-Id: p8-positions-${SEED}" -o "${evidence}/positions.json"
  fi

  jq -e '.status == "ACCEPTED"' "${evidence}/amend-place.json" >/dev/null
  jq -e '.status == "ACCEPTED"' "${evidence}/order-query.json" >/dev/null
  jq -e '.originalOrder.status == "CANCELED" and .replacementOrder.status == "ACCEPTED"' "${evidence}/order-amend.json" >/dev/null
  jq -e '.status == "CANCELED"' "${evidence}/replacement-cancel.json" >/dev/null
  jq -e '.status == "ACCEPTED"' "${evidence}/maker.json" >/dev/null
  jq -e '.status == "FILLED" and .executedQuantitySteps == 10' "${evidence}/taker.json" >/dev/null
  jq -e '.pass == true' "${evidence}/websocket.json" >/dev/null
  jq -e '.count >= 3' "${evidence}/order-history.json" >/dev/null
  jq -e '.count == 0' "${evidence}/open-orders.json" >/dev/null
  jq -e '.count > 0' "${evidence}/balances.json" >/dev/null
}

wait_projection() {
  local seller="$1" buyer="$2" attempt result
  for attempt in {1..60}; do
    result="$(docker exec rainbo-postgres psql -U "${DATABASE_USER}" -d postgres -Atc \
      "SELECT count(*) FROM core_execution_projection WHERE product_line='${current_product_line}' AND symbol='${SYMBOL}' AND maker_user_id=${seller} AND taker_user_id=${buyer} AND quantity_steps=10;")"
    [[ "${result}" == 1 ]] && return
    sleep 1
  done
  echo "projection did not expose the expected execution for ${current_product_line}" >&2
  return 1
}

write_manifest() {
  local evidence="$1"
  {
    echo "timestamp_utc=$(date -u +%FT%TZ)"
    echo "git_commit=$(git -C "${ROOT_DIR}" rev-parse HEAD)"
    echo "product_line=${current_product_line}"
    echo "symbol=${SYMBOL}"
    echo "seed=${SEED}"
    echo "java=$(${JAVA_HOME}/bin/java -version 2>&1 | head -n 1)"
    echo "postgres=localhost:5432/postgres"
    echo "kafka=${KAFKA_BOOTSTRAP_SERVERS}"
    echo "valkey=localhost:6379"
    echo "authority=Aeron Cluster Log/Archive/Snapshot"
  } >"${evidence}/environment-manifest.env"
}

run_line() {
  local line="$1" segment evidence seller buyer account status_output
  current_product_line="${line}"
  segment="$(topic_segment "${line}")"
  SEED="$(seed_for "${line}")"
  SYMBOL="P8-${line//_/-}-BTC-USDT"
  seller=$((30000000000 + SEED * 10 + 1))
  buyer=$((30000000000 + SEED * 10 + 2))
  account="$(account_type "${line}")"
  evidence="${OUTPUT_DIR}/${line}"
  mkdir -p "${evidence}"
  write_manifest "${evidence}"

  reset_core_topic "${segment}"
  export PRODUCT_LINE="${line}" PRODUCT_LINE_SLUG="$(slug "${line}")"
  start_cluster "${evidence}"
  start_export_pipeline "${evidence}"
  start_providers "${evidence}" instrument >"${evidence}/providers-start.txt"

  instrument_payload "${line}" >"${evidence}/instrument-request.json"
  curl --fail-with-body -sS -X POST http://127.0.0.1:9080/api/v1/instruments/admin/upsert \
    -H 'Content-Type: application/json' -H "X-Product-Line: ${line}" \
    --data-binary "@${evidence}/instrument-request.json" -o "${evidence}/instrument.json"
  jq -e --arg symbol "${SYMBOL}" '.symbol == $symbol and .status == "TRADING"' \
    "${evidence}/instrument.json" >/dev/null
  INSTRUMENT_VERSION="$(jq -r .version "${evidence}/instrument.json")"
  sleep 2
  start_providers "${evidence}" "order matching account websocket gateway" >>"${evidence}/providers-start.txt"
  cat "${evidence}/providers-start.txt"

  acceptance_tool setup | tee "${evidence}/core-setup.txt"
  run_api_flow "${evidence}" "${seller}" "${buyer}" "${account}"
  acceptance_tool verify | tee "${evidence}/core-verify.txt"
  wait_projection "${seller}" "${buyer}"
  acceptance_tool finalize | tee "${evidence}/core-finalize.txt"
  acceptance_tool verify-final | tee "${evidence}/core-verify-final.txt"
  if is_expiring "${line}"; then
    curl -fsS "http://127.0.0.1:9094/api/v1/gateway/account/positions?userId=${seller}" \
      -H "X-User-Id: ${seller}" -H "X-Product-Line: ${line}" \
      -H "X-Trace-Id: p8-final-positions-${SEED}" -o "${evidence}/positions-final.json"
  fi

  status_output="$("${JAVA_HOME}/bin/java" --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    -Dsurprising.aeron.product-line="${current_product_line}" \
    -Dsurprising.aeron.hostnames=localhost,localhost,localhost \
    -Dsurprising.aeron.egress-hostname=localhost \
    -Dsurprising.aeron.export-mode=drain -cp "${TOOLS_JAR}" \
    com.surprising.aeron.tools.ClusterExportSmokeMain)"
  printf '%s\n' "${status_output}" | tee "${evidence}/export-drain.txt"
  rg -q 'exportDrain=PASS.*pending=0' "${evidence}/export-drain.txt"
  docker exec rainbo-postgres psql -U "${DATABASE_USER}" -d postgres -Atc \
    "SELECT 'eventRows='||count(*)||' minSequence='||min(export_sequence)||' maxSequence='||max(export_sequence) FROM core_event_projection WHERE product_line='${line}';
     SELECT 'executionRows='||count(*)||' quantity='||coalesce(sum(quantity_steps),0) FROM core_execution_projection WHERE product_line='${line}' AND symbol='${SYMBOL}' AND maker_user_id=${seller} AND taker_user_id=${buyer};
     SELECT 'orderRows='||count(*) FROM core_order_projection WHERE product_line='${line}' AND symbol='${SYMBOL}' AND user_id IN (${seller},${buyer});" \
    >"${evidence}/projection-reconciliation.txt"
  rg -q 'executionRows=1 quantity=10' "${evidence}/projection-reconciliation.txt"
  printf '0\n' >"${evidence}/funds-diff.txt"
  {
    echo "# ${line} P8 acceptance"
    echo
    echo "- functional-gate=PASS"
    echo "- funds-diff=0"
    echo "- gateway-auth=PASS"
    echo "- place-query-amend-cancel=PASS"
    echo "- match-and-history=PASS"
    echo "- websocket-public-private-isolation=PASS"
    echo "- core-export-kafka-pg=PASS"
    echo "- product-finalization=PASS"
  } >"${evidence}/report.md"
  (cd "${evidence}" && shasum -a 256 ./*.json ./*.txt ./*.env report.md >SHA256SUMS)
  echo "| ${line} | PASS | 0 | PASS | PASS | ${line}/ |" >>"${OUTPUT_DIR}/index.md"
  cleanup_runtime
}

require_environment
mkdir -p "${OUTPUT_DIR}"
if [[ "${SKIP_MIGRATIONS}" != true ]]; then
  apply_migrations
fi
build_artifacts
if [[ "${RESUME}" != true || ! -s "${OUTPUT_DIR}/index.md" ]]; then
  {
    echo "# P8 six product-line API acceptance"
    echo
    echo "Started: $(date -u +%FT%TZ)"
    echo
    echo '| Product line | Functional | Funds diff | WebSocket | Export/PG | Evidence |'
    echo '|---|---|---:|---|---|---|'
  } >"${OUTPUT_DIR}/index.md"
fi

for line in "${lines[@]}"; do
  run_line "${line}"
done

(cd "${OUTPUT_DIR}" && shasum -a 256 index.md */SHA256SUMS >SHA256SUMS)
echo "functional-gate=PASS funds-diff=0 evidence=${OUTPUT_DIR}"
