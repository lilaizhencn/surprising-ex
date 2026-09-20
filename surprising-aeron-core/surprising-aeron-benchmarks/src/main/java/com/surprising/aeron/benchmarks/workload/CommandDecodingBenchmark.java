package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.stream.IntStream;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** One decoded command (PLACE_BATCH has 20 items); input encoding is outside measurement. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CommandDecodingBenchmark {
    @Param({"PLACE_EMPTY", "PLACE_BATCH", "REPLACE", "AMEND", "BALANCE", "TRANSFER"})
    public String command;
    private byte[] payload;

    @Setup public void setup() {
        var order = new PlaceOrderCommand(72, "BTC-USDT", CoreOrderSide.BUY, 101, 6,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, false, "");
        payload = switch (command) {
            case "PLACE_EMPTY" -> TradingCommandCodec.encodePlaceOrder(order);
            case "PLACE_BATCH" -> TradingOrderBatchCodec.encodePlaceOrderBatch(
                    new PlaceOrderBatchCommand(IntStream.range(0, 20).mapToObj(i ->
                            new PlaceOrderCommand(72 + i, order.symbol(), order.side(), 101, 6,
                                    false, order.marginMode(), order.positionSide(), order.orderType(),
                                    order.timeInForce(), false, "")).toList()));
            case "REPLACE" -> TradingCommandCodec.encodeReplaceOrder(new ReplaceOrderCommand(71, order));
            case "AMEND" -> TradingCommandCodec.encodeAmendOrder(
                    new AmendOrderCommand(71, 72, "client-72", null, null, null, null));
            case "BALANCE" -> TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10));
            case "TRANSFER" -> TradingCommandCodec.encodeTransferFunds(new TransferFundsCommand(91L,
                    ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, "FUNDING", "USDT_PERPETUAL",
                    "USDT", 250L, "transfer-91", "allocation"));
            default -> throw new IllegalArgumentException(command);
        };
    }

    @Benchmark public Object decode() {
        return switch (command) {
            case "PLACE_EMPTY" -> TradingCommandCodec.decodePlaceOrder(payload);
            case "PLACE_BATCH" -> TradingOrderBatchCodec.decodePlaceOrderBatch(payload);
            case "REPLACE" -> TradingCommandCodec.decodeReplaceOrder(payload);
            case "AMEND" -> TradingCommandCodec.decodeAmendOrder(payload);
            case "BALANCE" -> TradingCommandCodec.decodeBalanceAdjustment(payload);
            case "TRANSFER" -> TradingCommandCodec.decodeTransferFunds(payload);
            default -> throw new IllegalArgumentException(command);
        };
    }
}
