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

/** 真实外部单成员 Cluster：六产品线批量响应交接、连续复用和终态删除的 JMH 回归。 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@Fork(1)
@Threads(1)
public class ClusterBatchResponseBenchmark {
    @Param({"SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    public String productLine;
    private static final int USERS = 32, ITEMS = 20, ROUNDS = 8;
    private static final long FUNDS = 1_000_000;
    private static final String SYMBOL = "BATCH-USDT";
    /** 发压线程独占客户端、请求序号及订单序号；Owner 和 Lane 使用真实节点实例。 */
    private AeronClientPool client;
    private long sequence, orderId, terminal;
    private String asset;

    @Setup(Level.Trial)
    public void setup() {
        var product = ProductLine.valueOf(productLine);
        var type = ContractType.valueOf(product.contractTypeCode());
        asset = type.isInverse() ? "BTC" : "USDT";
        client = new AeronClientPool("batch-response", product,
                List.of(System.getProperty("surprising.aeron.hostnames").split(",")), "127.0.0.1",
                Duration.ofSeconds(30), "batch-response", UUID.randomUUID().toString(),
                ClusterMixedCapacityMain.commandCapacity(256, 256));
        applied(send(CoreMessageType.UPSERT_INSTRUMENT, 0, TradingCommandCodec.encodeUpsertInstrument(
                new UpsertInstrumentCommand(SYMBOL, 1, type.ordinal(), "BTC", "USDT", asset, 1, 1, 1,
                        100_000, 50_000, 0, 0, type.isDelivery() || type.isOption() ? System.currentTimeMillis() + 3_600_000 : 0,
                        type.isOption() ? 0 : -1, type.isOption() ? 100 : 0))).join());
        applied(send(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                ? new ApplyMarkPriceCommand(SYMBOL, 1, 100, 100, 100, 1, System.currentTimeMillis())
                : new ApplyMarkPriceCommand(SYMBOL, 1, 100, 1, System.currentTimeMillis()))).join());
        for (int i = 0; i < USERS; i++) applied(send(CoreMessageType.ADJUST_BALANCE, user(i),
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, FUNDS))).join());
    }

    private static long user(int i) { return 910_000_000L + i; }
    private CompletableFuture<CoreResponse> send(CoreMessageType type, long user, byte[] payload) {
        return client.commandAsync(type, new UUID(90611, ++sequence), user, payload);
    }
    private static void applied(CoreResponse response) {
        if (response.commandStatus() != ResponseStatus.APPLIED)
            throw new IllegalStateException("batch response command failed: " + response.resultCode());
    }

    @Benchmark
    public long lanePreparedResponses() {
        long before = terminal;
        for (int round = 0; round < ROUNDS; round++) {
            var replies = new ArrayList<CompletableFuture<CoreResponse>>(USERS * 2);
            var identities = new ArrayList<long[]>(USERS);
            // 同一 FIFO 先发下单再发撤单；发送阶段不逐笔 join，也不等下单响应才发撤单。
            for (int i = 0; i < USERS; i++) {
                var orders = new ArrayList<PlaceOrderCommand>(ITEMS);
                long[] ids = new long[ITEMS];
                for (int n = 0; n < ITEMS; n++) {
                    ids[n] = ++orderId;
                    orders.add(new PlaceOrderCommand(ids[n], SYMBOL, 1, CoreOrderSide.BUY, 80, 1, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "batch-" + ids[n]));
                }
                identities.add(ids);
                replies.add(send(CoreMessageType.PLACE_ORDER_BATCH, user(i),
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
            }
            for (int i = 0; i < USERS; i++) {
                var cancels = Arrays.stream(identities.get(i)).mapToObj(CancelOrderCommand::new).toList();
                replies.add(send(CoreMessageType.CANCEL_ORDER_BATCH, user(i),
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(cancels))));
            }
            for (int i = 0; i < replies.size(); i++) {
                var response = replies.get(i).join();
                applied(response);
                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                long[] ids = identities.get(i % USERS);
                if (items.size() != ITEMS) throw new IllegalStateException("batch response size differs");
                for (int n = 0; n < ITEMS; n++) {
                    var item = items.get(n);
                    if (item.index() != n || item.orderId() != ids[n] || item.status() != ResponseStatus.APPLIED)
                        throw new IllegalStateException("batch response item differs");
                    if (i >= USERS ? item.order() != null : item.order() == null
                            || item.order().userId() != user(i) || !item.order().clientOrderId().equals("batch-" + ids[n]))
                        throw new IllegalStateException("Lane response state differs");
                    terminal++;
                }
            }
        }
        return terminal - before;
    }

    @TearDown(Level.Trial)
    public void verify() {
        try {
            for (int i = 0; i < USERS; i++) {
                var response = client.query(CoreMessageType.USER_STATE_QUERY, new UUID(90611, ++sequence), user(i), new byte[0]);
                if (response.status() != ResponseStatus.OK) throw new IllegalStateException("batch query failed");
                var view = CoreStateQueryCodec.decodeUserState(response.data());
                var orders = client.query(CoreMessageType.USER_OPEN_ORDERS_QUERY, new UUID(90611, ++sequence), user(i),
                        CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery(SYMBOL, 0, 1)));
                if (orders.status() != ResponseStatus.OK || !CoreStateQueryCodec.decodeOpenOrders(orders.data()).orders().isEmpty())
                    throw new IllegalStateException("batch active order remains");
                long funds = view.balances().stream().mapToLong(b -> b.availableUnits() + b.lockedUnits()).sum();
                if (funds != FUNDS || !view.reservations().isEmpty()
                        || view.balances().stream().anyMatch(b -> b.lockedUnits() != 0))
                    throw new IllegalStateException("batch funds or terminal state differs");
            }
            System.out.printf("batchResponseVerify=PASS product=%s terminalBusinessOperations=%d unfinished=0 fundsDiff=0%n", productLine, terminal);
        } finally { if (client != null) client.close(); }
    }
}
