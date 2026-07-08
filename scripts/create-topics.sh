#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_SERVERS="${BOOTSTRAP_SERVERS:-localhost:9092}"
REPLICATION_FACTOR="${REPLICATION_FACTOR:-1}"
SYMBOL_COUNT="${SYMBOL_COUNT:-0}"
TARGET_SYMBOLS_PER_PARTITION="${TARGET_SYMBOLS_PER_PARTITION:-10}"
PROVIDER_INSTANCES="${PROVIDER_INSTANCES:-1}"
STREAM_THREADS="${STREAM_THREADS:-2}"
MIN_PARTITIONS="${MIN_PARTITIONS:-24}"
MAX_PARTITIONS="${MAX_PARTITIONS:-384}"
PARTITION_STEP="${PARTITION_STEP:-12}"
INCLUDE_SHARED_TOPICS="${INCLUDE_SHARED_TOPICS:-true}"
INCLUDE_LEGACY_PERP_TOPICS="${INCLUDE_LEGACY_PERP_TOPICS:-true}"
INCLUDE_PRODUCT_TOPICS="${INCLUDE_PRODUCT_TOPICS:-true}"
PRODUCT_TOPIC_LINES="${PRODUCT_TOPIC_LINES:-spot linear-perp inverse-perp linear-delivery inverse-delivery option}"

if [[ "${DRY_RUN:-false}" != "true" ]]; then
  if command -v kafka-topics >/dev/null 2>&1; then
    KAFKA_TOPICS_CMD=(kafka-topics)
  elif command -v kafka-topics.sh >/dev/null 2>&1; then
    KAFKA_TOPICS_CMD=(kafka-topics.sh)
  else
    echo "Missing Kafka topic command: install Kafka or add kafka-topics to PATH" >&2
    exit 1
  fi
fi

ceil_div() {
  local dividend="$1"
  local divisor="$2"
  echo $(( (dividend + divisor - 1) / divisor ))
}

round_up() {
  local value="$1"
  local step="$2"
  echo $(( ((value + step - 1) / step) * step ))
}

calculate_partitions() {
  if [[ -n "${PARTITIONS:-}" ]]; then
    echo "${PARTITIONS}"
    return
  fi

  local by_symbol=0
  if (( SYMBOL_COUNT > 0 )); then
    by_symbol="$(ceil_div "${SYMBOL_COUNT}" "${TARGET_SYMBOLS_PER_PARTITION}")"
  fi

  local by_parallelism=$(( PROVIDER_INSTANCES * STREAM_THREADS * 2 ))
  local selected="${MIN_PARTITIONS}"
  (( by_symbol > selected )) && selected="${by_symbol}"
  (( by_parallelism > selected )) && selected="${by_parallelism}"
  selected="$(round_up "${selected}" "${PARTITION_STEP}")"
  (( selected > MAX_PARTITIONS )) && selected="${MAX_PARTITIONS}"
  echo "${selected}"
}

PARTITIONS="$(calculate_partitions)"

echo "Creating exchange topics with partitions=${PARTITIONS}, replication_factor=${REPLICATION_FACTOR}"

create_topic() {
  local topic="$1"
  echo "create topic ${topic}"
  if [[ "${DRY_RUN:-false}" == "true" ]]; then
    return
  fi
  "${KAFKA_TOPICS_CMD[@]}" --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --create --if-not-exists \
    --topic "${topic}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION_FACTOR}"
}

create_product_topics() {
  local product_line="$1"
  local prefix="surprising.${product_line}"
  create_topic "${prefix}.trade.events.v1"
  create_topic "${prefix}.candle.events.v1"
  create_topic "${prefix}.order.commands.v1"
  create_topic "${prefix}.order.events.v1"
  create_topic "${prefix}.match.results.v1"
  create_topic "${prefix}.match.trades.v1"
  create_topic "${prefix}.orderbook.depth.v1"
  case "${product_line}" in
    linear-perp|inverse-perp|linear-delivery|inverse-delivery|option)
      create_topic "${prefix}.index.price.v1"
      create_topic "${prefix}.index.components.v1"
      create_topic "${prefix}.book.ticker.v1"
      create_topic "${prefix}.mark.price.v1"
      create_topic "${prefix}.mark.price.audit.v1"
      create_topic "${prefix}.account.position.events.v1"
      create_topic "${prefix}.risk.account.events.v1"
      create_topic "${prefix}.risk.position.events.v1"
      create_topic "${prefix}.liquidation.candidates.v1"
      ;;
  esac
  case "${product_line}" in
    linear-perp|inverse-perp)
      create_topic "${prefix}.funding.rate.v1"
      ;;
  esac
  case "${product_line}" in
    linear-delivery|inverse-delivery)
      create_topic "${prefix}.delivery.settlements.v1"
      ;;
    option)
      create_topic "${prefix}.option.exercises.v1"
      ;;
  esac
}

if [[ "${INCLUDE_SHARED_TOPICS}" == "true" ]]; then
  create_topic surprising.instrument.events.v1
  create_topic surprising.account.position.events.v1
  create_topic surprising.account.liquidation-fee.events.v1
  create_topic surprising.risk.account.events.v1
  create_topic surprising.risk.position.events.v1
fi

if [[ "${INCLUDE_LEGACY_PERP_TOPICS}" == "true" ]]; then
  create_topic surprising.perp.trade.events.v1
  create_topic surprising.perp.candle.events.v1
  create_topic surprising.perp.order.commands.v1
  create_topic surprising.perp.order.events.v1
  create_topic surprising.perp.match.results.v1
  create_topic surprising.perp.match.trades.v1
  create_topic surprising.perp.orderbook.depth.v1
  create_topic surprising.perp.liquidation.candidates.v1
  create_topic surprising.perp.index.price.v1
  create_topic surprising.perp.index.components.v1
  create_topic surprising.perp.book.ticker.v1
  create_topic surprising.perp.funding.rate.v1
  create_topic surprising.perp.mark.price.v1
  create_topic surprising.perp.mark.price.audit.v1
fi

if [[ "${INCLUDE_PRODUCT_TOPICS}" == "true" ]]; then
  for product_line in ${PRODUCT_TOPIC_LINES}; do
    create_product_topics "${product_line}"
  done
fi
