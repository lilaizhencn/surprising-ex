package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Canonical identity cost only; neither matching throughput nor complete Core allocation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms128m", "-Xmx128m"})
public class CommandFingerprintBenchmark {
    @Param({"BALANCE", "MAX_BATCH"}) public String payload;
    private final CommandFingerprint.Calculator calculator = new CommandFingerprint.Calculator();
    private final CoreMessage slot = CoreMessage.decoderSlot();
    private CoreMessage message;

    @Setup public void setup() {
        CoreMessageType type = CoreMessageType.ADJUST_BALANCE;
        byte[] body = TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1));
        if (payload.equals("MAX_BATCH")) {
            var orders = new ArrayList<PlaceOrderCommand>();
            for (int i = 1; i <= PlaceOrderBatchCommand.MAX_ORDERS; i++) orders.add(new PlaceOrderCommand(i, "604", CoreOrderSide.BUY,
                    100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "order-" + i));
            type = CoreMessageType.PLACE_ORDER_BATCH;
            body = TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders));
        }
        message = new CoreMessage(CoreMessageHeader.command(type, new UUID(1, 2), ProductLine.SPOT,
                CommandSource.GATEWAY, 1, 1, 7, 1, 1), body);
    }

    @Benchmark public byte ownedValue() {
        return calculator.of(message).byteAt(0);
    }

    @Benchmark public byte leasedValue() {
        if (!slot.acquireDecoderSlot()) throw new IllegalStateException("previous input still leased");
        try {
            var h = message.header();
            slot.decoderHeader(h.schemaVersion(), h.messageType(), h.commandId(), h.productLine(), h.source(), h.sourceId(),
                    h.sourceSequence(), h.userId(), h.submittedAtEpochMillis(), h.correlationId());
            message.copyPayloadTo(slot.decoderPayloadBuffer(message.payloadLength()), 0);
            return calculator.ofBorrowed(slot).byteAt(0);
        } finally {
            slot.releaseDecoderSlot();
        }
    }
}
