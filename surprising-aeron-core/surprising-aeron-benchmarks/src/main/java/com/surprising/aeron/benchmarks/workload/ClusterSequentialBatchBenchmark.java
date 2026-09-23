package com.surprising.aeron.benchmarks.workload;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Real single-member SPOT lifecycle, including setup and queries; not steady-state throughput. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
@Slf4j
public class ClusterSequentialBatchBenchmark {
    private static long commandSequence;
    @Benchmark
    public void proceedsAndAmendOnLane() { main(new String[]{"run"}); }

    public static void main(String[] args) {
        if (args.length != 1 || !List.of("run", "verify").contains(args[0]))
            throw new IllegalArgumentException("expected run|verify");
        try (var client = SurprisingAeronClient.connect(ProductLine.SPOT,
                List.of(System.getProperty("surprising.aeron.hostnames", "127.0.0.1").split(",")),
                System.getProperty("surprising.aeron.egress-hostname", "127.0.0.1"), Duration.ofSeconds(10))) {
            for (int i = 0; i < 128; i++) {
                long user = 1001 + i * 2L, maker = user + 1, id = 10000 + i * 10L;
                String asset = "B" + i, symbol = asset + "-USDT";
                if (args[0].equals("run")) {
                    send(client, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                            new RegisterInstrumentCommand(symbol, ContractType.SPOT.ordinal(), asset, "USDT", "USDT",
                                    1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
                    send(client, CoreMessageType.ADJUST_BALANCE, user, TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand(asset, 1)));
                    send(client, CoreMessageType.ADJUST_BALANCE, maker, TradingCommandCodec.encodeBalanceAdjustment(
                            new BalanceAdjustmentCommand("USDT", 2000)));
                    batch(client, maker, CoreMessageType.PLACE_ORDER_BATCH, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(order(id, symbol, CoreOrderSide.BUY)))), 1);
                    // Force sequential admission, then exercise native rejection and admission rejection.
                    var postOnly = new PlaceOrderCommand(id + 4, symbol, CoreOrderSide.SELL, 1000, 1, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTX,
                            true, "reject-" + id);
                    var rejected = send(client, CoreMessageType.PLACE_ORDER_BATCH, user,
                            TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(List.of(
                                    postOnly, order(id + 5, symbol, CoreOrderSide.BUY)))));
                    var items = TradingOrderBatchCodec.decodeResult(rejected.data()).items();
                    if (items.size() != 2 || items.get(0).status() != ResponseStatus.REJECTED
                            || items.get(0).resultCode() != CoreResultCode.MATCHING_REJECTED
                            || items.get(1).status() != ResponseStatus.REJECTED
                            || items.get(1).resultCode() != CoreResultCode.INSUFFICIENT_AVAILABLE_BALANCE)
                        throw new IllegalStateException("sequential rejection mismatch");
                    balance(client, user, asset, 1, 0);
                    // Admission must retry sequentially: the second item can only reserve proceeds of the first fill.
                    batch(client, user, CoreMessageType.PLACE_ORDER_BATCH, TradingOrderBatchCodec.encodePlaceOrderBatch(
                            new PlaceOrderBatchCommand(List.of(order(id + 1, symbol, CoreOrderSide.SELL),
                                    order(id + 2, symbol, CoreOrderSide.BUY)))), 2);
                    balance(client, user, "USDT", 0, 1000);
                    batch(client, user, CoreMessageType.AMEND_ORDER_BATCH, TradingOrderBatchCodec.encodeAmendOrderBatch(
                            new AmendOrderBatchCommand(List.of(new AmendOrderCommand(id + 2, id + 3, "amend-" + id,
                                    900L, 1L, CoreTimeInForce.GTC, false)))), 1);
                    balance(client, user, "USDT", 100, 900);
                    batch(client, user, CoreMessageType.CANCEL_ORDER_BATCH, TradingOrderBatchCodec.encodeCancelOrderBatch(
                            new CancelOrderBatchCommand(List.of(new CancelOrderCommand(id + 3)))), 1);
                }
                balance(client, user, "USDT", 1000, 0);
                balance(client, user, asset, 0, 0);
                balance(client, maker, "USDT", 1000, 0);
                balance(client, maker, asset, 1, 0);
                log.info("{}", "sequentialCase=" + i + " PASS fundsDiff=0 locked=0");
            }
            log.info("sequentialBatch=PASS symbols=128");
        }
    }

    private static PlaceOrderCommand order(long id, String symbol, CoreOrderSide side) {
        return new PlaceOrderCommand(id, symbol, side, 1000, 1, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "seq-" + id);
    }
    private static void batch(SurprisingAeronClient client, long user, CoreMessageType type, byte[] payload, int count) {
        var response = send(client, type, user, payload);
        if (TradingOrderBatchCodec.firstNonAppliedItem(response, count) != -1)
            throw new IllegalStateException("batch item failed " + type);
    }
    private static CoreResponse send(SurprisingAeronClient client, CoreMessageType type, long user, byte[] payload) {
        long sequence = ++commandSequence;
        var response = client.submit(new CoreMessage(CoreMessageHeader.command(type, UUID.randomUUID(), ProductLine.SPOT,
                CommandSource.OPERATIONS, 770077, sequence, user, System.currentTimeMillis(), sequence), payload));
        if (response.commandStatus() != ResponseStatus.APPLIED)
            throw new IllegalStateException(type + " failed " + response.resultCode());
        return response;
    }
    private static void balance(SurprisingAeronClient client, long user, String asset, long available, long locked) {
        var response = client.submit(new CoreMessage(CoreMessageHeader.query(CoreMessageType.USER_STATE_QUERY,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 770077, 0, user,
                System.currentTimeMillis(), 0), new byte[0]));
        if (response.status() != ResponseStatus.OK) throw new IllegalStateException("account query failed");
        var state = CoreStateQueryCodec.decodeUserState(response.data());
        var value = state.balances().stream().filter(b -> b.asset().equals(asset)).findFirst().orElse(null);
        if (value == null ? available != 0 || locked != 0
                : value.availableUnits() != available || value.lockedUnits() != locked)
            throw new IllegalStateException("balance mismatch user=" + user + " asset=" + asset);
        if (!state.positions().isEmpty()) throw new IllegalStateException("SPOT must have no derivative positions");
    }
}
