package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.*;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;

/** Book/evidence fixture: prepares real reserved Direct slots; financial settlement is tested by the runtime tests. */
public final class DirectMatcherTestInputs {
    private DirectMatcherTestInputs() {}

    public static MatcherSettlementEvent preparePlace(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, CoreMatchingOrder order) {
        return preparePlace(adapter, sequence, commandId, timestamp, userId, order, List.of());
    }

    public static MatcherSettlementEvent preparePlace(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, CoreMatchingOrder order,
            List<Long> cancellations) {
        try (var runtime = new TradingRuntimeState()) {
            var identities = new RuntimeIdentityRegistry();
            var instrument = CoreInstrument.from(ProductLine.SPOT,
                    new RegisterInstrumentCommand(Integer.toString(order.instrumentId()), ContractType.SPOT.ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));
            runtime.setMetadata(ProductLine.SPOT, 0);
            runtime.registerInstrument(instrument);
            var admitted = new OrderRuntime(order.orderId(), ProductLine.SPOT, userId,
                    identities.symbolId(Integer.toString(order.instrumentId())), instrument, order.side(), order.matchingPriceTicks(),
                    order.matchingPriceTicks(), order.quantitySteps(), 0, order.quantitySteps(), false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, order.orderType(), order.timeInForce(),
                    order.timeInForce() == CoreTimeInForce.GTX, "", commandId, 0, 0, 0,
                    timestamp, timestamp, 0, CoreOrderStatus.OPEN, 1);
            long lanes = runtime.topology().accountLaneCount() == 64 ? -1L
                    : (1L << runtime.topology().accountLaneCount()) - 1;
            var target = runtime.prepareDirectMatcherSettlement(sequence, lanes, admitted, null, 1,
                    commandId, adapter.matcherShardId(Integer.toString(order.instrumentId())), identities, timestamp, 0, cancellations, null);
            target.reserveMatcherPublication();
            return target;
        }
    }

    public static MatcherSettlementEvent placeDirect(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, CoreMatchingOrder order) {
        var target = preparePlace(adapter, sequence, commandId, timestamp, userId, order);
        adapter.placeDirect(adapter.matcherShardId(Integer.toString(order.instrumentId())), sequence, commandId,
                timestamp, userId, order, target);
        return target;
    }

    public static MatcherSettlementEvent placeDirect(DeterministicExchangeCoreAdapter adapter,
            long userId, CoreMatchingOrder order) {
        return placeDirect(adapter, order.orderId(), new UUID(userId, order.orderId()), 1000, userId, order);
    }

    public static MatcherSettlementEvent cancelDirect(DeterministicExchangeCoreAdapter adapter,
            long sequence, UUID commandId, long timestamp, long userId, long orderId, String instrumentId) {
        try (var runtime = new TradingRuntimeState()) {
            var target = runtime.prepareDirectCancellation(sequence, userId, orderId, commandId,
                    adapter.matcherShardId(instrumentId), new RuntimeIdentityRegistry(), timestamp, 0);
            target.reserveMatcherPublication();
            adapter.cancelDirect(adapter.matcherShardId(instrumentId), sequence, commandId, orderId,
                    timestamp, userId, instrumentId, target);
            return target;
        }
    }
}
