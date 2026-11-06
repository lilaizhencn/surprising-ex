#!/bin/bash
set -euo pipefail

JAVA_CMD="/Users/atomex/.sdkman/candidates/java/current/bin/java"
JAR_PATH="$HOME/.local/share/surprising-ex/perpetual-pmm-20/artifacts/surprising-maker/target/surprising-maker-1.0.0-SNAPSHOT-exec.jar"
LOG_DIR="$HOME/.local/share/surprising-ex/perpetual-pmm-20/runtime/local-perpetual/logs"
mkdir -p "$LOG_DIR"

export PRODUCT_LINE=LINEAR_PERPETUAL
export KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
export SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
export SURPRISING_PRICE_MARK_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
export SURPRISING_PRICE_INDEX_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
export SURPRISING_MARKET_MAKER_INFRASTRUCTURE_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092
export SURPRISING_MARKET_MAKER_INFRASTRUCTURE_KAFKA_INSTRUMENT_SNAPSHOT_GROUP_ID=surprising-market-maker-instrument-snapshot-LINEAR_PERPETUAL-v1
export SURPRISING_CLIENTS_INSTRUMENT_BASE_URL=http://127.0.0.1:9094
export SURPRISING_CLIENTS_ORDER_BASE_URL=http://127.0.0.1:9094
export SURPRISING_CLIENTS_ACCOUNT_BASE_URL=http://127.0.0.1:9094
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/surprising_telegram_local_20261020
export SPRING_DATASOURCE_USERNAME=surprising
export SPRING_DATASOURCE_PASSWORD=surprising
export SERVER_PORT=9096

exec "$JAVA_CMD" -Xms256m -Xmx512m \
  "-Dsurprising.market-maker.engine.enabled=true" \
  "-Dsurprising.market-maker.strategies[0].strategy-id=btc-maker" \
  "-Dsurprising.market-maker.strategies[0].product-line=LINEAR_PERPETUAL" \
  "-Dsurprising.market-maker.strategies[0].account-ids[0]=2" \
  "-Dsurprising.market-maker.strategies[0].instrument-ids[0]=604" \
  "-Dsurprising.market-maker.strategies[0].enabled=true" \
  -jar "$JAR_PATH"
