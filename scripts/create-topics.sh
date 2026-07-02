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

echo "Creating perp market-data topics with partitions=${PARTITIONS}, replication_factor=${REPLICATION_FACTOR}"

if [[ "${DRY_RUN:-false}" == "true" ]]; then
  exit 0
fi

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.instrument.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.trade.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.candle.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.order.commands.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.order.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.match.results.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.match.trades.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.orderbook.depth.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.account.position.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.account.liquidation-fee.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.risk.account.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.risk.position.events.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.liquidation.candidates.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.index.price.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.index.components.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.book.ticker.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.funding.rate.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.mark.price.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"

kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --create --if-not-exists \
  --topic surprising.perp.mark.price.audit.v1 \
  --partitions "${PARTITIONS}" \
  --replication-factor "${REPLICATION_FACTOR}"
