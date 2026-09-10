package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.*;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

/** 覆盖真实输入/输出线程边界、持续Owner、冻结撤单及快照恢复；没有交易推进定时器。 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@State(Scope.Thread)
public class ContinuousOwnerBenchmark implements AutoCloseable {
    @Param({"SPOT", "LINEAR_PERPETUAL", "INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    public ProductLine productLine;
    /** 20项批量响应覆盖不可变payload跨线程交接及复用编码缓冲区。 */
    @Param({"1", "20"})
    public int batchSize = 1;
    private static final long TIME = 1_700_000_000_000L;
    /** 单一负载线程持有发送计数和响应计数；实际Owner运行于生产服务创建的线程。 */
    private ContinuousTradingClusterService service;
    private ClientSession session;
    private long sequence, terminal, orderId = 1;
    private final long[] orders = new long[256];
    private String asset;
    private Thread transportThread;
    private int apiCalls, timerCalls;
    private boolean closed;
    private boolean holdEgress;
    private boolean validateBatchResponses;
    private long lastCommitted;
    private Cluster.Role role = Cluster.Role.LEADER;

    @Setup(Level.Trial)
    public void setup() {
        transportThread = Thread.currentThread();
        ContractType type = ContractType.valueOf(productLine.contractTypeCode());
        asset = type.isInverse() ? "BTC" : "USDT";
        Cluster cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(), new Class<?>[]{Cluster.class},
                (proxy, method, args) -> {
                    assertTransportThread();
                    return switch (method.getName()) {
                        case "role" -> role;
                        case "timeUnit" -> TimeUnit.MILLISECONDS;
                        case "time" -> TIME;
                        case "logPosition" -> sequence;
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        case "scheduleTimer", "cancelTimer" -> {
                            timerCalls++;
                            throw new IllegalStateException("continuous owner must not use timers");
                        }
                        default -> zero(method.getReturnType());
                    };
                });
        session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                new Class<?>[]{ClientSession.class}, (proxy, method, args) -> {
                    assertTransportThread();
                    if (method.getName().equals("offer")) {
                        if (holdEgress) return io.aeron.Publication.BACK_PRESSURED;
                        int length = (int) args[2];
                        byte[] bytes = new byte[length];
                        ((org.agrona.DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                        var message = CoreMessageCodec.decode(bytes);
                        CoreResponse response = CoreProtocol.decodeResponse(message.payloadUnsafe());
                        if (response.commandStatus() != ResponseStatus.APPLIED)
                            throw new IllegalStateException("unexpected response " + response);
                        if (response.committedCoreSequence() < lastCommitted
                                || response.committedCoreSequence() > response.appliedCommandCount())
                            throw new IllegalStateException("response commit cursor changed after Owner handoff");
                        lastCommitted = response.committedCoreSequence();
                        if (validateBatchResponses && batchSize > 1) {
                            var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                            if (items.size() != batchSize || items.stream().anyMatch(item -> item.status() != ResponseStatus.APPLIED))
                                throw new IllegalStateException("batch response was overwritten");
                        }
                        terminal++;
                        return 1L;
                    }
                    if (method.getName().equals("id")) return 1L;
                    return zero(method.getReturnType());
                });
        service = new ContinuousTradingClusterService(productLine, (clusterContext, target) ->
                new ContinuousTradingClusterService.SessionEgress() {
                    public long offer(long term, long timestamp, org.agrona.DirectBuffer source, int offset, int length) {
                        return target.offer(source, offset, length);
                    }
                    public void close() { assertTransportThread(); }
                });
        service.onStart(cluster, null);
        service.onSessionOpen(session, TIME);
        send(CoreMessageType.UPSERT_INSTRUMENT, 0, TradingCommandCodec.encodeUpsertInstrument(
                new UpsertInstrumentCommand("BTC-USDT", 1, type.ordinal(), "BTC", "USDT", asset,
                        1, 1, type.isInverse() ? 1000 : 1, 100000, 50000, 0, 0,
                        type.isDelivery() || type.isOption() ? TIME + 100000 : 0,
                        type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
        drain();
        send(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                ? new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 100, 100, 1, TIME)
                : new ApplyMarkPriceCommand("BTC-USDT", 1, 100, 1, TIME)));
        drain();
        for (int i = 0; i < 256; i++)
            send(CoreMessageType.ADJUST_BALANCE, 1000 + i,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset, 20000)));
        drain();
    }

    @Benchmark
    public long placeCancelWithoutTimers(Counters counters) {
        validateBatchResponses = true;
        long before = terminal;
        for (int i = 0; i < 256; i++) {
            orders[i] = orderId;
            var items = new java.util.ArrayList<PlaceOrderCommand>(batchSize);
            for (int item = 0; item < batchSize; item++) items.add(new PlaceOrderCommand(orderId++,
                    "BTC-USDT", 1, CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS,
                    CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""));
            send(batchSize == 1 ? CoreMessageType.PLACE_ORDER : CoreMessageType.PLACE_ORDER_BATCH, 1000 + i,
                    batchSize == 1 ? TradingCommandCodec.encodePlaceOrder(items.getFirst())
                            : TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(items)));
        }
        if (holdEgress) {
            service.captureSnapshot();
            holdEgress = false;
        }
        drain();
        for (int i = 0; i < 256; i++) {
            var items = new java.util.ArrayList<CancelOrderCommand>(batchSize);
            for (int item = 0; item < batchSize; item++) items.add(new CancelOrderCommand(orders[i] + item));
            send(batchSize == 1 ? CoreMessageType.CANCEL_ORDER : CoreMessageType.CANCEL_ORDER_BATCH, 1000 + i,
                    batchSize == 1 ? TradingCommandCodec.encodeCancelOrder(items.getFirst())
                            : TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(items)));
        }
        drain();
        if (terminal - before != 512) throw new IllegalStateException("missing terminal response");
        counters.acceptedBusinessOperations += 512L * batchSize;
        counters.terminalBusinessOperations += 512L * batchSize;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        validateBatchResponses = false;
        return terminal;
    }

    /** 只用于功能回归：模拟慢出口后恢复，核对队列内旧响应未被下一条编码覆盖。 */
    public void verifyDeferredResponseHandoff() {
        holdEgress = true;
        placeCancelWithoutTimers(new Counters());
    }

    /** 功能场景：快照请求进入尚有在途订单的FIFO，随后核对恢复结果和角色切换。 */
    public void verifyPendingSnapshotBoundary() {
        for (int i = 0; i < 256; i++) {
            orders[i] = orderId++;
            send(CoreMessageType.PLACE_ORDER, 1000 + i, TradingCommandCodec.encodePlaceOrder(
                    new PlaceOrderCommand(orders[i], "BTC-USDT", 1, CoreOrderSide.BUY, 80, 1, false,
                            CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "")));
        }
        try (var restored = TradingCoreRuntime.fromSnapshot(productLine, service.captureSnapshot())) {
            for (int i = 0; i < 256; i++) {
                var order = restored.tradingState().order(orders[i]);
                var balance = restored.tradingState().user(1000 + i).balances().get(asset);
                if (order == null || order.status() != com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN
                        || balance.availableUnits() + balance.lockedUnits() != 20000)
                    throw new IllegalStateException("snapshot omitted an in-flight order");
            }
        }
        drain();
        role = Cluster.Role.FOLLOWER;
        service.onRoleChange(role);
        role = Cluster.Role.LEADER;
        service.onRoleChange(role);
        for (int i = 0; i < 256; i++)
            send(CoreMessageType.CANCEL_ORDER, 1000 + i,
                    TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(orders[i])));
        drain();
    }

    private void send(CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        byte[] bytes = CoreMessageCodec.encode(CoreMessage.owned(CoreMessageHeader.command(type,
                new UUID(77, id), productLine, CommandSource.GATEWAY, 77, id, user, TIME, id), payload));
        Header header = new Header(0, 0).buffer(new UnsafeBuffer(new byte[64]))
                .offset(0).initialTermId(0).positionBitsToShift(16);
        service.onSessionMessage(session, TIME, new UnsafeBuffer(bytes), 0, bytes.length, header);
    }

    /** 只轮询传输出口，不调用业务processor，也不提供后续命令或timer。 */
    private void drain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (terminal < sequence) {
            service.doBackgroundWork(System.nanoTime());
            if (System.nanoTime() > deadline) throw new IllegalStateException("tail did not complete without new input");
            Thread.onSpinWait();
        }
    }

    private void assertTransportThread() {
        if (Thread.currentThread() != transportThread) throw new IllegalStateException("Aeron API called from owner");
        apiCalls++;
    }

    @TearDown(Level.Trial)
    public void close() {
        if (closed || service == null) return;
        closed = true;
        try {
            drain();
            byte[] snapshot = service.captureSnapshot();
            try (var restored = TradingCoreRuntime.fromSnapshot(productLine, snapshot)) {
                for (int i = 0; i < 256; i++) {
                    var balance = restored.tradingState().user(1000 + i).balances().get(asset);
                    if (balance.availableUnits() != 20000 || balance.lockedUnits() != 0)
                        throw new IllegalStateException("funds or reservations differ after snapshot restore");
                    if (orders[i] != 0 && restored.tradingState().order(orders[i]) != null)
                        throw new IllegalStateException("canceled order remained in restored active orders");
                }
            }
            if (timerCalls != 0 || apiCalls == 0 || terminal != sequence)
                throw new IllegalStateException("invalid transport/timer/terminal counters");
        } finally { service.onTerminate(null); }
    }

    private static Object zero(Class<?> type) {
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == boolean.class) return false;
        return null;
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long acceptedBusinessOperations, terminalBusinessOperations;
        public long acceptedCoreMessages, terminalCoreMessages;
    }
}
