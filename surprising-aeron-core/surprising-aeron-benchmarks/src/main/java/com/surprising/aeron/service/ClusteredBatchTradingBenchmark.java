package com.surprising.aeron.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.*;

/** Real service ingress/egress and non-waiting completion pump; no external transport. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsAppend = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
@Threads(1)
public class ClusteredBatchTradingBenchmark {
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long acceptedBusinessOperations;
        public long terminalBusinessOperations;
        public long acceptedCoreMessages;
        public long terminalCoreMessages;
        public long terminalBatches;
        public long terminalItems;
        public long queries;
        public long terminalTrades;
    }

    @Benchmark
    public long batchPlaceCancelWithMetrics(Workload workload, Counters counters) {
        workload.run();
        counters.acceptedBusinessOperations += 2L * 256 * workload.batchSize;
        counters.terminalBusinessOperations += 2L * 256 * workload.batchSize;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        counters.terminalBatches += 512;
        counters.terminalItems += 512L * workload.batchSize;
        counters.queries += 512;
        return workload.terminal;
    }

    @State(Scope.Thread)
    public static class Workload implements AutoCloseable {
        @Param({"LINEAR_PERPETUAL", "SPOT"}) public ProductLine productLine;
        @Param("4") public int accountLanes;
        @Param("20") public int batchSize;
        @Param("256") public int maxInFlight;
        private SurprisingClusteredService service;
        private ClientSession session;
        private final Header header = new Header(0, 0).buffer(new UnsafeBuffer(new byte[64]))
                .offset(0).initialTermId(0).positionBitsToShift(16);
        private long sequence;
        private long orderId = 1_000;
        private long terminal;
        private long queryResults;
        private long maxBacklog;
        private final long[] firstOrders = new long[256];
        private static final long BALANCE = 1_000_000_000L;

        @Setup(Level.Iteration)
        public void setup() {
            if (maxInFlight != 256 || batchSize <= 0) throw new IllegalArgumentException("requires 256 in-flight");
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            sequence = terminal = queryResults = maxBacklog = 0;
            service = new SurprisingClusteredService(productLine);
            Cluster cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(),
                    new Class<?>[]{Cluster.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "role" -> Cluster.Role.LEADER;
                        case "idleStrategy" -> NoOpIdleStrategy.INSTANCE;
                        case "timeUnit" -> TimeUnit.MILLISECONDS;
                        case "time", "logPosition" -> 1_700_000_000_000L;
                        default -> defaultValue(method.getReturnType());
                    });
            service.onStart(cluster, null);
            session = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
                    new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "id" -> 91L;
                        case "isClosing" -> false;
                        case "offer" -> {
                            byte[] bytes = new byte[(int) args[2]];
                            ((DirectBuffer) args[0]).getBytes((int) args[1], bytes);
                            CoreMessage message = CoreMessageCodec.decode(bytes);
                            CoreResponse response = CoreProtocol.decodeResponse(message.payloadUnsafe());
                            if (response.status() == ResponseStatus.OK) queryResults++;
                            else {
                                if (response.status() != ResponseStatus.APPLIED) {
                                    throw new IllegalStateException("batch rejected: " + response.resultCode());
                                }
                                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                                if (items.size() != batchSize || items.stream().anyMatch(
                                        item -> item.status() != ResponseStatus.APPLIED || !item.executions().isEmpty())) {
                                    throw new IllegalStateException("batch item or trade mismatch");
                                }
                                terminal++;
                            }
                            yield 1L;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
            service.onSessionOpen(session, 1_700_000_000_000L);
            apply(CoreMessageType.UPSERT_INSTRUMENT, 0, TradingCommandCodec.encodeUpsertInstrument(
                    new UpsertInstrumentCommand("JMH-BTC-USDT", 1,
                            (productLine == ProductLine.SPOT ? ContractType.SPOT : ContractType.LINEAR_PERPETUAL).ordinal(),
                            "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
            if (productLine != ProductLine.SPOT) {
                apply(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                        new ApplyMarkPriceCommand("JMH-BTC-USDT", 1, 100, 1, 1_700_000_000_000L)));
            }
            for (int user = 0; user <= 256; user++) {
                apply(CoreMessageType.ADJUST_BALANCE, 1_000 + user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", BALANCE)));
            }
            if (productLine == ProductLine.SPOT) {
                apply(CoreMessageType.ADJUST_BALANCE, 1_256,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", 1)));
            }
            // Resting maker liquidity remains present throughout all iterations.
            CoreMessage maker = command(CoreMessageType.PLACE_ORDER, 1_256,
                    TradingCommandCodec.encodePlaceOrder(order(1, CoreOrderSide.SELL, 120)));
            var response = service.state().apply(maker);
            if (response.resultCode() != CoreResultCode.MATCHING_PENDING) throw new IllegalStateException("maker failed");
            drain();
        }

        public void run() {
            long terminalBefore = terminal;
            long queriesBefore = queryResults;
            for (int user = 0; user < 256; user++) {
                firstOrders[user] = orderId;
                var orders = new ArrayList<PlaceOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) orders.add(order(orderId++, CoreOrderSide.BUY, 90));
                send(command(CoreMessageType.PLACE_ORDER_BATCH, 1_000 + user,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
                metrics();
            }
            drain();
            for (int user = 0; user < 256; user++) {
                var orders = new ArrayList<CancelOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) orders.add(new CancelOrderCommand(firstOrders[user] + item));
                send(command(CoreMessageType.CANCEL_ORDER_BATCH, 1_000 + user,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders))));
                metrics();
            }
            drain();
            if (terminal - terminalBefore != 512 || queryResults - queriesBefore != 512) {
                throw new IllegalStateException("accepted/terminal mismatch");
            }
        }

        private void metrics() {
            send(new CoreMessage(CoreMessageHeader.query(CoreMessageType.LANE_METRICS_QUERY, UUID.randomUUID(),
                    productLine, CommandSource.GATEWAY, 77, 0, 0, 1_700_000_000_000L, 0), new byte[0]));
        }

        private void send(CoreMessage message) {
            byte[] bytes = CoreMessageCodec.encode(message);
            service.onSessionMessage(session, 1_700_000_000_000L, new UnsafeBuffer(bytes), 0, bytes.length, header);
            maxBacklog = Math.max(maxBacklog, service.state().pendingMatchingCount());
        }

        private void drain() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            // Deferred ingress is included: pendingMatchingCount alone is not an ingress fence.
            int work;
            do {
                work = service.doBackgroundWork(System.nanoTime());
                if (System.nanoTime() > deadline) throw new IllegalStateException("service completion timeout");
                if (work == 0) Thread.onSpinWait();
            } while (work != 0 || service.state().pendingMatchingCount() != 0);
        }

        private CoreMessage command(CoreMessageType type, long user, byte[] payload) {
            long next = ++sequence;
            return new CoreMessage(CoreMessageHeader.command(type, new UUID(77, next), productLine,
                    CommandSource.GATEWAY, 77, next, user, 1_700_000_000_000L, next), payload);
        }

        private void apply(CoreMessageType type, long user, byte[] payload) {
            if (service.state().apply(command(type, user, payload)).status() != ResponseStatus.APPLIED) {
                throw new IllegalStateException("fixture command failed: " + type);
            }
        }

        private PlaceOrderCommand order(long id, CoreOrderSide side, long price) {
            return new PlaceOrderCommand(id, "JMH-BTC-USDT", 1, side, price, 1, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                    false, "cluster-batch-" + id);
        }

        @TearDown(Level.Iteration)
        public void close() {
            if (service == null) return;
            try {
                drain();
                var state = service.state().tradingState();
                for (int user = 0; user < 256; user++) {
                    var balance = state.users().get(1_000L + user).balances().get("USDT");
                    if (balance.availableUnits() != BALANCE || balance.lockedUnits() != 0) {
                        throw new IllegalStateException("cancel did not restore user funds");
                    }
                    if (!state.users().get(1_000L + user).reservations().isEmpty()
                            || !state.users().get(1_000L + user).positions().isEmpty()) {
                        throw new IllegalStateException("terminal user retained reservations or positions");
                    }
                }
                var maker = state.users().get(1_256L);
                if (maker.balances().get("USDT").totalUnits() != BALANCE || !maker.positions().isEmpty()
                        || maker.reservations().size() != 1) throw new IllegalStateException("maker funds mismatch");
                if (productLine == ProductLine.SPOT && maker.balances().get("BTC").totalUnits() != 1) {
                    throw new IllegalStateException("maker BTC conservation mismatch");
                }
                if (state.orders().size() != 1 || state.order(1) == null) {
                    throw new IllegalStateException("terminal order retention mismatch");
                }
                byte[] snapshot = service.state().snapshot();
                try (CoreProbeState restored = CoreProbeState.fromSnapshot(productLine, snapshot)) {
                    if (restored.tradingState().businessStateHash() != state.businessStateHash()) {
                        throw new IllegalStateException("snapshot recovery mismatch");
                    }
                }
                System.out.printf("clusterBatch acceptedCore=%d terminalCore=%d unfinished=0 endBacklog=0 maxBacklog=%d queries=%d%n",
                        terminal, terminal, maxBacklog, queryResults);
            } finally {
                service.onTerminate(null);
                service = null;
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
