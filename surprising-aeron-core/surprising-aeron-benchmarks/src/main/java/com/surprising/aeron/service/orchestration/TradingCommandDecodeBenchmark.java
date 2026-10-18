package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

/** Ingress copy and actual command decoding with 256 simultaneously retained command leases. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms256m", "-Xmx256m", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class TradingCommandDecodeBenchmark {
    private static final int WINDOW = 256;
    @Param({"CANCEL_ORDER", "CANCEL_ORDER_BATCH", "AMEND_ORDER", "AMEND_ORDER_BATCH", "REPLACE_ORDER"})
    public String commandType;
    @Param({"FIXED", "VARIABLE"}) public String lengths;
    private final CoreMessageFlyweightDecoder decoder = new CoreMessageFlyweightDecoder();
    private final DecodedMatchingCommand[] decoded = new DecodedMatchingCommand[WINDOW];
    private final CoreMessage[] leased = new CoreMessage[WINDOW];
    private final UnsafeBuffer[][] frames = new UnsafeBuffer[3][WINDOW];
    private int generation;
    private CoreMessageType type;

    @Setup public void setup() {
        type = CoreMessageType.valueOf(commandType);
        for (int index = 0; index < WINDOW; index++) decoded[index] = DecodedMatchingCommand.commandSlot();
        for (int bank = 0; bank < frames.length; bank++) for (int index = 0; index < WINDOW; index++) {
            int totalText = lengths.equals("VARIABLE") ? bank * WINDOW + index : 0;
            byte[] payload;
            if (type == CoreMessageType.CANCEL_ORDER) {
                payload = TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(index + 1));
            } else if (type == CoreMessageType.CANCEL_ORDER_BATCH) {
                var orders = new ArrayList<CancelOrderCommand>();
                int count = lengths.equals("VARIABLE") ? 1 + index % 20 : 20;
                for (int item = 0; item < count; item++) orders.add(new CancelOrderCommand(index * 20L + item + 1));
                payload = TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders));
            } else if (type == CoreMessageType.AMEND_ORDER_BATCH) {
                var orders = new ArrayList<AmendOrderCommand>();
                for (int item = 0; item < 20; item++) {
                    int textLength = Math.min(64, totalText);
                    totalText -= textLength;
                    orders.add(amend(index * 20L + item + 1, "a".repeat(textLength)));
                }
                payload = TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(orders));
            } else if (type == CoreMessageType.AMEND_ORDER) {
                payload = TradingCommandCodec.encodeAmendOrder(amend(index + 1, "a".repeat(totalText % 65)));
            } else {
                var replacement = new PlaceOrderCommand(index + 1_000_000, "10001", CoreOrderSide.BUY,
                        100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "a".repeat(totalText % 65));
                payload = TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(index + 1, replacement));
            }
            var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(bank + 1, index + 1),
                    ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 1, index + 1, 7, 1, index + 1), payload);
            frames[bank][index] = new UnsafeBuffer(CoreMessageCodec.encode(message));
        }
    }

    private static AmendOrderCommand amend(long id, String clientOrderId) {
        return new AmendOrderCommand(id, id + 1_000_000, clientOrderId,
                101L, 2L, CoreTimeInForce.GTC, (byte) 0);
    }

    /** Consume every decoded item, including text, so the benchmark retains the real decode work. */
    private long decodedChecksum(DecodedMatchingCommand value) {
        return switch (type) {
            case CANCEL_ORDER -> value.cancelOrder().orderId();
            case CANCEL_ORDER_BATCH -> {
                long sum = 0;
                var orders = value.cancelOrderBatch().orders();
                for (int item = 0; item < orders.size(); item++) sum += orders.get(item).orderId();
                yield sum;
            }
            case AMEND_ORDER -> amendChecksum(value.amendOrder());
            case AMEND_ORDER_BATCH -> {
                long sum = 0;
                var orders = value.amendOrderBatch().orders();
                for (int item = 0; item < orders.size(); item++) sum += amendChecksum(orders.get(item));
                yield sum;
            }
            case REPLACE_ORDER -> {
                var replace = value.replaceOrder();
                var order = replace.replacement();
                yield replace.originalOrderId() + order.orderId() + order.limitPriceTicks()
                        + order.quantitySteps() + order.clientOrderId().hashCode() + order.instrumentId().hashCode();
            }
            default -> throw new IllegalStateException("unsupported benchmark command: " + type);
        };
    }

    private static long amendChecksum(AmendOrderCommand value) {
        return value.originalOrderId() + value.replacementOrderId() + value.rawPriceTicks()
                + value.rawQuantitySteps() + value.newClientOrderId().hashCode()
                + value.timeInForce().ordinal() + value.rawPostOnly();
    }

    @Benchmark @OperationsPerInvocation(WINDOW)
    public long decodeRetainedCommandWindow() {
        var bank = frames[generation];
        if (++generation == frames.length) generation = 0;
        long checksum = 0;
        try {
            for (int index = 0; index < WINDOW; index++) {
                var frame = bank[index];
                var message = decoder.decodeBorrowed(frame, 0, frame.capacity());
                leased[index] = message;
                decoded[index].decodeInto(message);
                checksum += message.payloadLength() + decodedChecksum(decoded[index]);
            }
            return checksum;
        } finally {
            for (int index = 0; index < WINDOW; index++) if (leased[index] != null) {
                decoded[index].clearCommandSlot();
                leased[index].releaseDecoderSlot();
                leased[index] = null;
            }
        }
    }
}
