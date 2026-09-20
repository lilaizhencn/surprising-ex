package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** External single-member Cluster; ordinary/batch fills churn client identities on 128 symbols.
 * Client-ID queries verify the runtime index is empty after Lane-owned identity retirement.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(1)
@Threads(1)
public class ClusterDirectSettlementBenchmark {
    @Param({"SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    public String productLine;
    private static final int SYMBOLS = 128, ROUNDS = 16, GROUP = 16;
    private static final long CASH = 1_000_000_000L, COINS = 10_000;
    private AeronClientPool client;
    private ProductLine product;
    private long sequence, orderId, accepted, terminal, messages, marks;
    private long markSequence = 1;
    private final long[] lastOrderIds = new long[SYMBOLS * 2];
    private static String symbol(int index) { return "DIRECT" + index + "-USDT"; }
    private static long user(int index, int side) { return 820_000_000L + index * 2L + side; }

    private void connect() {
        product = ProductLine.valueOf(productLine);
        client = new AeronClientPool("direct-settlement", product,
                List.of(System.getProperty("surprising.aeron.hostnames", "127.0.0.1").split(",")),
                "127.0.0.1", Duration.ofSeconds(30), "direct-settlement", UUID.randomUUID().toString(),
                ClusterMixedCapacityMain.commandCapacity(64, 64));
    }

    @Setup(Level.Trial)
    public void setup() {
        connect();
        var type = ContractType.valueOf(product.contractTypeCode());
        String settle = type.isInverse() ? "BTC" : "USDT";
        long now = System.currentTimeMillis();
        for (int index = 0; index < SYMBOLS; index++) {
            applied(send(CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand(symbol(index), type.ordinal(), "BTC", "USDT", settle,
                            1, 1, 1, 100_000, 50_000, 0, 0,
                            type.isDelivery() || type.isOption() ? now + 3_600_000 : 0,
                            type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))).join());
            applied(send(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                    ? new ApplyMarkPriceCommand(symbol(index), 100, 100, 100, 1, now)
                    : new ApplyMarkPriceCommand(symbol(index), 100, 1, now))).join());
            for (int side = 0; side < 2; side++) {
                applied(send(CoreMessageType.ADJUST_BALANCE, user(index, side), TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand(settle, CASH))).join());
                if (product == ProductLine.SPOT) applied(send(CoreMessageType.ADJUST_BALANCE, user(index, side),
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", COINS))).join());
            }
        }
    }

    private CompletableFuture<CoreResponse> send(CoreMessageType type, long user, byte[] payload) {
        return client.commandAsync(type, new UUID(9132026, ++sequence), user, payload);
    }
    private static void applied(CoreResponse response) {
        if (response.commandStatus() != ResponseStatus.APPLIED)
            throw new IllegalStateException("direct settlement command rejected: " + response.resultCode());
    }
    private CompletableFuture<CoreResponse> place(int index, int who, CoreOrderSide side, int count) {
        var orders = new ArrayList<PlaceOrderCommand>(count);
        for (int item = 0; item < count; item++) {
            long id = ++orderId;
            lastOrderIds[index * 2 + who] = id;
            orders.add(new PlaceOrderCommand(id, symbol(index), side, 100, 1, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                    false, "direct-" + id));
        }
        accepted += count;
        return count == 1 ? send(CoreMessageType.PLACE_ORDER, user(index, who), TradingCommandCodec.encodePlaceOrder(orders.getFirst()))
                : send(CoreMessageType.PLACE_ORDER_BATCH, user(index, who),
                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
    }

    @Benchmark
    public long matcherToLaneLifecycle() {
        System.out.println("directLifecycleStartEpochMillis=" + System.currentTimeMillis());
        long before = terminal;
        for (int round = 0; round < ROUNDS; round++) {
            int count = round % 2 == 0 ? 1 : 20;
            markSequence++;
            for (int group = 0; group < SYMBOLS; group += GROUP) {
                var prices = new ArrayList<CompletableFuture<CoreResponse>>(GROUP);
                long now = System.currentTimeMillis();
                for (int index = group; index < group + GROUP; index++) {
                    prices.add(send(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                            product == ProductLine.OPTION
                                    ? new ApplyMarkPriceCommand(symbol(index), 100, 100, 100, markSequence, now)
                                    : new ApplyMarkPriceCommand(symbol(index), 100, markSequence, now))));
                    accepted++;
                }
                // Market freshness is a financial prerequisite; orders themselves remain asynchronous.
                for (var price : prices) { applied(price.join()); terminal++; messages++; marks++; }
                var replies = new ArrayList<CompletableFuture<CoreResponse>>(64);
                for (int index = group; index < group + GROUP; index++) {
                    replies.add(place(index, 0, CoreOrderSide.SELL, count));
                    replies.add(place(index, 1, CoreOrderSide.BUY, count));
                    replies.add(place(index, 1, CoreOrderSide.SELL, count));
                    replies.add(place(index, 0, CoreOrderSide.BUY, count));
                }
                for (var reply : replies) {
                    var response = reply.join(); applied(response);
                    if (count > 1) {
                        var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                        if (items.size() != count || items.stream().anyMatch(item -> item.status() != ResponseStatus.APPLIED))
                            throw new IllegalStateException("direct batch terminal mismatch: size=" + items.size()
                                    + " rejected=" + items.stream().filter(item -> item.status() != ResponseStatus.APPLIED)
                                    .map(item -> item.index() + ":" + item.resultCode()).toList());
                    }
                    terminal += count; messages++;
                }
            }
        }
        System.out.printf("directSettlementCounts acceptedBusinessOperations=%d terminalBusinessOperations=%d terminalCoreMessages=%d unfinished=%d fills=%d%n",
                accepted, terminal, messages, accepted - terminal, (terminal - marks) / 2);
        System.out.println("directLifecycleEndEpochMillis=" + System.currentTimeMillis());
        return terminal - before;
    }

    private void verifyState() {
        String settle = ContractType.valueOf(product.contractTypeCode()).isInverse() ? "BTC" : "USDT";
        for (int index = 0; index < SYMBOLS; index++) for (int side = 0; side < 2; side++) {
            long id = user(index, side);
            long lastOrderId = lastOrderIds[index * 2 + side];
            if (lastOrderId != 0) {
                var terminalOrder = client.query(CoreMessageType.CLIENT_ORDER_STATE_QUERY, UUID.randomUUID(), id,
                        CoreStateQueryCodec.encodeClientOrderStateQuery("direct-" + lastOrderId));
                if (terminalOrder.resultCode() != CoreResultCode.ENTITY_NOT_FOUND || terminalOrder.data().length != 0)
                    throw new IllegalStateException("terminal client identity remains in the runtime index");
            }
            var response = client.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), id, new byte[0]);
            var view = CoreStateQueryCodec.decodeUserState(response.data());
            long expectedAssets = product == ProductLine.SPOT ? 2 : 1;
            if (view.balances().size() != expectedAssets || view.balances().stream().anyMatch(balance ->
                    balance.lockedUnits() != 0 || balance.availableUnits() != (balance.asset().equals(settle)
                            ? CASH : product == ProductLine.SPOT && balance.asset().equals("BTC") ? COINS : -1))
                    || !view.reservations().isEmpty() || view.positions().stream().anyMatch(position -> position.signedQuantitySteps() != 0))
                throw new IllegalStateException("direct settlement funds/position/reservation mismatch for " + id);
            var orders = client.query(CoreMessageType.USER_OPEN_ORDERS_QUERY, UUID.randomUUID(), id,
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery(symbol(index), 0, 1)));
            if (!CoreStateQueryCodec.decodeOpenOrders(orders.data()).orders().isEmpty())
                throw new IllegalStateException("unfinished direct order");
        }
        long hash = CoreStateQueryCodec.decodeStateHash(client.query(CoreMessageType.BUSINESS_STATE_HASH_QUERY,
                UUID.randomUUID(), 0, new byte[0]).data());
        System.out.printf("directSettlementVerify=PASS product=%s symbols=128 fundsDiff=0 positions=0 reservations=0 openOrders=0 businessHash=%s%n",
                product, Long.toUnsignedString(hash, 16));
    }

    @TearDown(Level.Trial)
    public void verifyAndClose() {
        try { if (accepted != terminal) throw new IllegalStateException("unfinished direct operations"); verifyState(); }
        finally { if (client != null) client.close(); }
    }
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("expected product line and optional last order ID");
        var work = new ClusterDirectSettlementBenchmark(); work.productLine = args[0];
        if (args.length == 2) {
            long base = Long.parseLong(args[1]) - SYMBOLS * 4L * 20;
            if (base < 0) throw new IllegalArgumentException("invalid lifecycle last order ID");
            for (int index = 0; index < SYMBOLS; index++) {
                work.lastOrderIds[index * 2] = base + (index + 1) * 80L;
                work.lastOrderIds[index * 2 + 1] = base + index * 80L + 60;
            }
        }
        work.connect();
        work.verifyAndClose();
    }
}
