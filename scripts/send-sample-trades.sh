#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP_SERVERS="${BOOTSTRAP_SERVERS:-localhost:9092}"
TOPIC="${TOPIC:-surprising.perp.trade.events.v1}"

kafka-console-producer.sh \
  --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --topic "${TOPIC}" \
  --property parse.key=true \
  --property key.separator='|' <<'EOF'
BTC-USDT|{"symbol":"BTC-USDT","tradeId":"10000001","sequence":10000001,"tradeTime":"2026-06-30T10:15:20Z","price":61500.12,"quantity":0.05,"side":"BUY","makerOrderId":"m-1","takerOrderId":"t-1"}
BTC-USDT|{"symbol":"BTC-USDT","tradeId":"10000002","sequence":10000002,"tradeTime":"2026-06-30T10:15:50Z","price":61510.00,"quantity":0.03,"side":"SELL","makerOrderId":"m-2","takerOrderId":"t-2"}
ETH-USDT|{"symbol":"ETH-USDT","tradeId":"20000001","sequence":20000001,"tradeTime":"2026-06-30T10:15:22Z","price":3400.10,"quantity":1.20,"side":"BUY","makerOrderId":"m-3","takerOrderId":"t-3"}
EOF
