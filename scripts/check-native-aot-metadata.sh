#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="${1:?module path is required}"
ARTIFACT="${2:?artifact id is required}"
METADATA="${ROOT_DIR}/${MODULE}/target/spring-aot/main/resources/META-INF/native-image/com.surprising/${ARTIFACT}/reachability-metadata.json"

if [[ ! -s "${METADATA}" ]]; then
  echo "Missing Spring AOT reachability metadata: ${METADATA}" >&2
  exit 1
fi

if ! rg -Fq '"type": "org.springframework.boot.webmvc.WebMvcWebApplicationTypeDeducer"' "${METADATA}"; then
  echo "Missing Spring Web MVC application type binding in ${ARTIFACT}" >&2
  exit 1
fi
if rg -Fq 'PastOrPresentValidatorForReadablePartial' "${METADATA}"; then
  echo "Obsolete Hibernate Validator metadata is present in ${ARTIFACT}" >&2
  exit 1
fi

required_types() {
  case "${ARTIFACT}" in
    surprising-instrument-provider)
      printf '%s\n' \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent \
        com.surprising.instrument.api.model.DeliverySettlementEvent \
        com.surprising.instrument.api.model.OptionExerciseEvent
      ;;
    surprising-candlestick-provider)
      printf '%s\n' \
        com.surprising.trading.api.model.PublicTradeEvent \
        com.surprising.candlestick.api.model.CandleUpdatedEvent \
        com.surprising.candlestick.provider.aggregation.CandleAccumulator \
        com.surprising.candlestick.provider.aggregation.CandleSnapshot
      ;;
    surprising-index-price-provider)
      printf '%s\n' \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.price.api.model.IndexComponentSnapshot \
        com.surprising.price.api.model.IndexPriceEvent
      ;;
    surprising-mark-price-provider)
      printf '%s\n' \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.price.api.model.IndexPriceEvent \
        com.surprising.price.api.model.MarkPricePublishedEvent \
        com.surprising.price.api.model.PerpFundingRateEvent
      ;;
    surprising-account-provider)
      printf '%s\n' \
        com.surprising.account.api.model.AccountCommandResultEvent \
        com.surprising.account.provider.model.AccountCommandTerminalResult \
        com.surprising.account.api.model.AccountUserCommand \
        com.surprising.account.api.model.BalanceAdjustmentAccountCommand \
        com.surprising.account.api.model.ProductBalanceAdjustmentAccountCommand \
        com.surprising.account.api.model.OrderReserveAccountCommand \
        com.surprising.account.api.model.OrderReleaseAccountCommand \
        com.surprising.account.api.model.TradeSideSettlementCommand \
        com.surprising.account.api.model.FundingSettlementAccountCommand \
        com.surprising.account.api.model.ExpiringPositionSettlementAccountCommand \
        com.surprising.account.api.model.AdlTargetSettlementAccountCommand \
        com.surprising.account.api.model.DeficitReservationAccountCommand \
        com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent \
        com.surprising.account.api.model.PositionUpdatedEvent \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.instrument.api.model.InstrumentLifecycleDrainEvent
      ;;
    surprising-order-provider)
      printf '%s\n' \
        com.surprising.account.api.model.AccountCommandResultEvent \
        com.surprising.account.api.model.AccountUserCommand \
        com.surprising.account.api.model.OrderReserveAccountCommand \
        com.surprising.account.api.model.OrderReleaseAccountCommand \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.trading.api.model.MatchResultEvent \
        com.surprising.trading.api.model.OrderEvent \
        com.surprising.trading.api.model.OrderUserCommand \
        com.surprising.trading.api.model.OrderUserCommandResult \
        com.surprising.trading.order.model.CancelAllAfterTimer \
        com.surprising.trading.order.model.OrderUserCancelCommand
      ;;
    surprising-trigger-provider)
      printf '%s\n' \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.trading.api.model.TriggerOrderUpdatedEvent
      ;;
    surprising-matching-provider)
      printf '%s\n' \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.trading.api.model.OrderCommandEvent \
        com.surprising.trading.api.model.MatchResultEvent \
        com.surprising.trading.api.model.OrderBookDepthEvent \
        com.surprising.trading.api.model.PublicTradeEvent \
        'com.surprising.trading.matching.store.MatchingLocalStateStore$StoredOrder' \
        'com.surprising.trading.matching.store.MatchingLocalStateStore$LocalOutboxRecord'
      ;;
    surprising-risk-provider)
      printf '%s\n' \
        com.surprising.account.api.model.AccountCommandResultEvent \
        com.surprising.account.api.model.AccountUserCommand \
        com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent \
        com.surprising.account.api.model.PositionUpdatedEvent \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.risk.api.model.LiquidationCandidateEvent \
        com.surprising.risk.api.model.RiskAccountUpdatedEvent \
        com.surprising.risk.api.model.RiskPositionUpdatedEvent \
        com.surprising.trading.api.model.FeeScheduleEvent \
        com.surprising.trading.api.model.MatchResultEvent \
        'com.surprising.risk.provider.service.RiskLocalProjectionStore$RiskProjectionBatch' \
        'com.surprising.risk.provider.service.RiskLocalProjectionStore$ProjectionIds'
      ;;
    surprising-liquidation-provider|surprising-funding-provider|surprising-insurance-provider|surprising-adl-provider)
      printf '%s\n' \
        com.surprising.account.api.model.AccountCommandResultEvent \
        com.surprising.account.api.model.AccountUserCommand \
        com.surprising.instrument.api.model.InstrumentEvent
      ;;
    surprising-gateway)
      printf '%s\n' \
        com.surprising.gateway.provider.service.ProductTransferWireRequest
      ;;
    surprising-websocket-provider)
      printf '%s\n' \
        com.surprising.candlestick.api.model.CandleUpdatedEvent \
        com.surprising.candlestick.api.model.TradeEvent \
        com.surprising.trading.api.model.OrderBookDepthEvent \
        com.surprising.trading.api.model.OrderEvent \
        com.surprising.trading.api.model.MatchResultEvent \
        com.surprising.trading.api.model.PublicTradeEvent \
        com.surprising.account.api.model.PositionUpdatedEvent \
        com.surprising.risk.api.model.RiskAccountUpdatedEvent \
        com.surprising.risk.api.model.RiskPositionUpdatedEvent \
        com.surprising.websocket.api.model.WsClientCommand \
        com.surprising.websocket.api.model.WsServerMessage
      ;;
    surprising-market-maker-provider)
      printf '%s\n' \
        com.surprising.instrument.api.model.InstrumentEvent \
        com.surprising.price.api.model.MarkPricePublishedEvent
      ;;
    *)
      echo "No Native AOT contract is defined for ${ARTIFACT}" >&2
      exit 1
      ;;
  esac
}

missing=0
while IFS= read -r type; do
  if ! rg -Fq "\"type\": \"${type}\"" "${METADATA}"; then
    echo "Missing Native reflection binding for ${type} in ${ARTIFACT}" >&2
    missing=1
  fi
done < <(required_types)

exit "${missing}"
