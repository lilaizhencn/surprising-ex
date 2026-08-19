package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

public final class CoreAcceptFreezeBenchmark {

    private static final String SYMBOL = "BTC-USDT";
    private static final long USER_ID = 1001L;
    private static final long BALANCE_UNITS = 1_000_000_000_000L;

    private CoreAcceptFreezeBenchmark() {
    }

    public static void main(String[] args) {
        int orders = positive(args, 0, 100_000);
        int warmup = positive(args, 1, 10_000);
        try (CoreProbeState state = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            long gatewaySequence = 1;
            long operationsSequence = 1;
            apply(state, CoreMessageType.UPSERT_INSTRUMENT, CommandSource.OPERATIONS, operationsSequence++, 1,
                    TradingCommandCodec.encodeUpsertInstrument(instrument()));
            apply(state, CoreMessageType.ADJUST_BALANCE, CommandSource.GATEWAY, gatewaySequence++, 2,
                    TradingCommandCodec.encodeBalanceAdjustment(
                            new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", BALANCE_UNITS)));
            for (int index = 0; index < warmup; index++) {
                apply(state, CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY, gatewaySequence++,
                        10L + index, TradingCommandCodec.encodePlaceOrder(place(1_000_000L + index)));
            }
            long started = System.nanoTime();
            long lastExportSequence = 0;
            for (int index = 0; index < orders; index++) {
                CoreResponse response = apply(state, CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                        gatewaySequence++, 1_000_000L + index,
                        TradingCommandCodec.encodePlaceOrder(place(10_000_000L + index)));
                if (response.status() != ResponseStatus.OK) {
                    throw new IllegalStateException("order was not accepted: " + response.status());
                }
                lastExportSequence = response.requiredExportSequence();
                if ((index & 255) == 255) {
                    apply(state, CoreMessageType.ACK_EXPORT, CommandSource.OPERATIONS, operationsSequence++,
                            2_000_000L + index,
                            CoreExportCodec.encodeAck(new AckExportCommand(lastExportSequence)));
                }
            }
            long elapsed = System.nanoTime() - started;
            System.out.printf("coreAcceptFreezeBenchmark=PASS productLine=%s orders=%d "
                            + "elapsedSeconds=%.3f acceptedPerSec=%.3f pendingMatching=%d%n",
                    ProductLine.LINEAR_PERPETUAL, orders, elapsed / 1_000_000_000.0,
                    orders / (elapsed / 1_000_000_000.0), state.pendingMatchingCount());
        }
    }

    private static CoreResponse apply(CoreProbeState state, CoreMessageType type, CommandSource source,
                                      long sourceSequence, long correlationId, byte[] payload) {
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, source, source == CommandSource.OPERATIONS ? 9 : 7,
                sourceSequence, source == CommandSource.OPERATIONS ? 0 : USER_ID, 1_000, correlationId), payload);
        CoreResponse response = state.apply(message);
        if (response.status() != ResponseStatus.APPLIED && response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("command rejected type=" + type + " status=" + response.status());
        }
        return response;
    }

    private static PlaceOrderCommand place(long orderId) {
        return new PlaceOrderCommand(orderId, SYMBOL, 1, "BTC", "USDT", "USDT", CoreOrderSide.BUY,
                1_000, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_000, CoreOrderType.LIMIT,
                CoreTimeInForce.IOC, 1_000, false, "accept-freeze-" + orderId, 0, 0);
    }

    private static UpsertInstrumentCommand instrument() {
        return new UpsertInstrumentCommand(SYMBOL, 1, ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT",
                "USDT", 1, 1, 1, 100_000, 100_000, 0, 0, 0, -1, 0);
    }

    private static int positive(String[] args, int index, int fallback) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value <= 0) throw new IllegalArgumentException("benchmark counts must be positive");
        return value;
    }
}
