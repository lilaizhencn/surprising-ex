package com.surprising.aeron.service.execution;

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

/** Deterministic service log callbacks, with 256-request submission waves; no external transport. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(value = 1, jvmArgsAppend = {"--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"})
@Threads(1)
public class ClusteredBatchTradingBenchmark {
    @Benchmark
    public long independentBatchWindows(Workload workload, Counters counters) {
        workload.runIndependentBatchWindows();
        counters.acceptedBusinessOperations += 512L * workload.batchSize;
        counters.terminalBusinessOperations += 512L * workload.batchSize;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        return workload.terminal;
    }
    /** Cross-callback independent orders, replicated timer drain, then independent cancellations. */
    @Benchmark
    public long independentCommandWindows(Workload workload, Counters counters) {
        workload.runIndependentCommandWindows();
        counters.acceptedBusinessOperations += 512;
        counters.terminalBusinessOperations += 512;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        return workload.terminal;
    }
    /**
     * Owner progress/empty polling plus bounded Lane spin/park handoffs, reservation consume,
     * partial fill and cancel release. Use settlement-spin-limit=0/256 on the current build
     * to distinguish wake-up scheduling cost from useful computation.
     * Kept as a scenario definition; performance execution must use the real-three-node harness.
     */
    @Benchmark
    public long ownerProgressAndReservationTransitions(Workload workload, Counters counters) {
        multiFillSettlementAndEncoding(workload, counters);
        return batchPlaceCancelWithMetrics(workload, counters);
    }

    /**
     * Exercises ingress, repeated settlement polling, and existing-order admission scans.
     * The maker accumulates same-symbol orders before taker/close waves, exercising STP and close capacity.
     */
    @Benchmark
    public long decodedBatchAdmissionAndSettlement(Workload workload, Counters counters) {
        batchAmendRoundTripTrades(workload, counters);
        return batchPlaceCancelWithMetrics(workload, counters);
    }
    /** Fused batch cancellation commit, result encoding, and all-rejected continuation readiness. */
    @Benchmark
    public long ownerBatchCompletion(Workload workload, Counters counters) {
        rejectedCancelContinuations(workload, counters);
        return batchPlaceCancelWithMetrics(workload, counters);
    }

    /** Maker batches of small orders; each taker consumes batchSize distinct fills and then closes. */
    @Benchmark
    public long multiFillSettlementAndEncoding(Workload workload, Counters counters) {
        workload.runMultiFillRoundTrip();
        long business = 512L * (workload.batchSize + 1);
        counters.acceptedBusinessOperations += business; counters.terminalBusinessOperations += business;
        counters.acceptedCoreMessages += 1024; counters.terminalCoreMessages += 1024;
        counters.terminalBatches += 1024; counters.terminalItems += business;
        counters.terminalTrades += 512L * workload.batchSize;
        return workload.terminal;
    }

    /** Trigger publication/removal plus successful batch place/cancel completion. */
    @Benchmark
    public long ownerTriggerAndBatchCompletion(Workload workload, Counters counters) {
        workload.runTriggerRoundTrip();
        counters.acceptedBusinessOperations += 1536;
        counters.terminalBusinessOperations += 1536;
        counters.acceptedCoreMessages += 1536;
        counters.terminalCoreMessages += 1536;
        counters.terminalTrades += 512;
        return batchPlaceCancelWithMetrics(workload, counters);
    }

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
        public long rejectedBusinessOperations;
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

    @Benchmark
    public long rejectedCancelContinuations(Workload workload, Counters counters) {
        workload.runRejectedContinuations();
        counters.acceptedBusinessOperations += 258L * workload.batchSize;
        counters.terminalBusinessOperations += 258L * workload.batchSize;
        counters.acceptedCoreMessages += 258;
        counters.terminalCoreMessages += 258;
        counters.terminalBatches += 258;
        counters.terminalItems += 258L * workload.batchSize;
        counters.rejectedBusinessOperations += 254L * workload.batchSize;
        return workload.terminal;
    }

    @Benchmark
    public long committedRealtimeTrades(Workload workload,Counters counters) {
        workload.runRoundTripTrades();
        counters.acceptedBusinessOperations+=1024;counters.terminalBusinessOperations+=1024;
        counters.acceptedCoreMessages+=1024;counters.terminalCoreMessages+=1024;counters.terminalTrades+=512;
        return workload.terminal;
    }

    @Benchmark
    public long batchAmendRoundTripTrades(Workload workload, Counters counters) {
        workload.runAmendRoundTripTrades();
        long business = 512L + 1024L * workload.batchSize;
        counters.acceptedBusinessOperations += business;
        counters.terminalBusinessOperations += business;
        counters.acceptedCoreMessages += 1536;
        counters.terminalCoreMessages += 1536;
        counters.terminalBatches += 1024;
        counters.terminalItems += 1024L * workload.batchSize;
        counters.terminalTrades += 512L * workload.batchSize;
        return workload.terminal;
    }

    @State(Scope.Thread)
    public static class Workload implements AutoCloseable {
        @Param({"LINEAR_PERPETUAL", "SPOT"}) public ProductLine productLine;
        @Param("4") public int accountLanes;
        @Param("20") public int batchSize;
        @Param("256") public int maxInFlight;
        @Param("false") public boolean realtime;
        @Param({"0", "256"}) public int settlementSpinLimit;
        private String previousSpinLimit;
        private com.surprising.aeron.client.RealtimeOutbox realtimeOutbox;
        private Thread realtimeConsumer;
        private volatile boolean consuming;
        private boolean singleResponses;
        private java.util.Queue<RealtimeFrame> snapshotRequests;
        private final org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap batchRequestSizes =
                new org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap(512);
        private final org.eclipse.collections.impl.set.mutable.primitive.LongHashSet singleRequestIds =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        private int responseBatchSize;
        private String settleAsset;
        private String pipelineSymbolB;
        private SurprisingClusteredService service;
        private ClientSession session;
        private final Header header = new Header(0, 0).buffer(new UnsafeBuffer(new byte[64]))
                .offset(0).initialTermId(0).positionBitsToShift(16);
        private long sequence;
        private long orderId = 1_000;
        private long terminal;
        private long queryResults;
        private long maxBacklog;
        private boolean expectMissingCancels;
        private long rejectedItems;
        private boolean expectBatchTrades;
        private long batchTrades;
        private long makerBaseBalance;
        private final long[] firstOrders = new long[256];
        private static final long BALANCE = 1_000_000_000L;

        @Setup(Level.Iteration)
        public void setup() {
            if (maxInFlight != 256 || batchSize <= 0) throw new IllegalArgumentException("requires 256 in-flight");
            previousSpinLimit = System.getProperty("surprising.aeron.settlement-spin-limit");
            System.setProperty("surprising.aeron.settlement-spin-limit", Integer.toString(settlementSpinLimit));
            LinearPerpetualBenchmarkSupport.configureAccountLanes(accountLanes);
            sequence = terminal = queryResults = maxBacklog = 0;
            singleRequestIds.clear();
            batchRequestSizes.clear();
            responseBatchSize = batchSize;
            expectMissingCancels = false;
            rejectedItems = 0;
            expectBatchTrades = false;
            batchTrades = 0;
            makerBaseBalance = 1L + 256L * batchSize;
            service = new SurprisingClusteredService(productLine);
            try {
                var requests = SurprisingClusteredService.class.getDeclaredField("snapshotRequests");
                requests.setAccessible(true);
                @SuppressWarnings("unchecked")
                var queue = (java.util.Queue<RealtimeFrame>) requests.get(service);
                snapshotRequests = queue;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            // Aeron invokes background callbacks while its service idle strategy is running.
            // Define that path here too; performance execution remains on the real cluster.
            var serviceIdle = new org.agrona.concurrent.IdleStrategy() {
                public void idle(int work) { if (realtime) service.doBackgroundWork(System.nanoTime()); }
                public void idle() { idle(0); }
                public void reset() { }
                public String alias() { return "realtime-reentrant"; }
            };
            Cluster cluster = (Cluster) Proxy.newProxyInstance(Cluster.class.getClassLoader(),
                    new Class<?>[]{Cluster.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "role" -> Cluster.Role.LEADER;
                        case "idleStrategy" -> realtime ? serviceIdle : NoOpIdleStrategy.INSTANCE;
                        case "timeUnit" -> TimeUnit.MILLISECONDS;
                        case "scheduleTimer" -> true;
                        case "time", "logPosition" -> 1_700_000_000_000L;
                        default -> defaultValue(method.getReturnType());
                    });
            service.onStart(cluster, null);
            if(realtime) {
                realtimeOutbox=new com.surprising.aeron.client.RealtimeOutbox(8192,8*1024*1024);
                RealtimeBenchmarkFixture.attach(service, realtimeOutbox);consuming=true;
                realtimeConsumer=Thread.ofPlatform().name("realtime-benchmark-drain").start(()->{
                    while(consuming){if(realtimeOutbox.poll()==null)java.util.concurrent.locks.LockSupport.parkNanos(100_000);}
                    while(realtimeOutbox.poll()!=null){}
                });
            }
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
                                if(singleRequestIds.remove(message.header().correlationId())){terminal++;yield 1L;}
                                var items = TradingOrderBatchCodec.decodeResult(response.data()).items();
                                int expectedSize = batchRequestSizes.get(message.header().correlationId());
                                batchRequestSizes.removeKey(message.header().correlationId());
                                if (items.size() != expectedSize)
                                    throw new IllegalStateException("batch size mismatch");
                                for (var item : items) {
                                    if (!expectBatchTrades && !item.executions().isEmpty()) {
                                        throw new IllegalStateException("unexpected trade");
                                    }
                                    batchTrades += item.executions().size();
                                    if (item.status() == ResponseStatus.APPLIED) continue;
                                    if (!expectMissingCancels || item.status() != ResponseStatus.REJECTED
                                            || item.resultCode() != CoreResultCode.ORDER_NOT_FOUND) {
                                        throw new IllegalStateException("unexpected batch rejection: " + item);
                                    }
                                    rejectedItems++;
                                }
                                terminal++;
                            }
                            yield 1L;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
            service.onSessionOpen(session, 1_700_000_000_000L);
            ContractType type=ContractType.valueOf(productLine.contractTypeCode());
            settleAsset=type.isInverse()?"BTC":"USDT";
            pipelineSymbolB = "JMH-PIPE-B-USDT";
            while (com.surprising.aeron.service.state.TradingDependencyMask.account(pipelineSymbolB.hashCode())
                    == com.surprising.aeron.service.state.TradingDependencyMask.account("JMH-PIPE-A-USDT".hashCode()))
                pipelineSymbolB = "X" + pipelineSymbolB;
            for (String symbol : new String[]{"JMH-PIPE-A-USDT", pipelineSymbolB}) {
                apply(CoreMessageType.UPSERT_INSTRUMENT, 0, TradingCommandCodec.encodeUpsertInstrument(
                        new UpsertInstrumentCommand(symbol, 1, type.ordinal(), "BTC", "USDT", settleAsset, 1, 1,
                                type.isInverse() ? 1000 : 1, 100_000, 50_000, 0, 0,
                                type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
                if (productLine.isDerivative()) apply(CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                ? new ApplyMarkPriceCommand(symbol, 1, 100, 100, 100, 1, 1_700_000_000_000L)
                                : new ApplyMarkPriceCommand(symbol, 1, 100, 1, 1_700_000_000_000L)));
            }
            apply(CoreMessageType.UPSERT_INSTRUMENT,0,TradingCommandCodec.encodeUpsertInstrument(
                new UpsertInstrumentCommand("JMH-BTC-USDT",1,type.ordinal(),"BTC","USDT",settleAsset,1,1,
                    type.isInverse()?1000:1,100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                    type.isOption()?0:-1,type.isOption()?100:0)));
            if(productLine.isDerivative())apply(CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(
                type.isOption()?new ApplyMarkPriceCommand("JMH-BTC-USDT",1,100,100,100,1,1_700_000_000_000L)
                :new ApplyMarkPriceCommand("JMH-BTC-USDT",1,100,1,1_700_000_000_000L)));
            for (int user = 0; user <= 256; user++) {
                apply(CoreMessageType.ADJUST_BALANCE, 1_000 + user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(settleAsset, BALANCE)));
            }
            if (productLine == ProductLine.SPOT) {
                apply(CoreMessageType.ADJUST_BALANCE, 1_256,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("BTC", makerBaseBalance)));
            }
            // Resting maker liquidity remains present throughout all iterations.
            CoreMessage maker = command(CoreMessageType.PLACE_ORDER, 1_256,
                    TradingCommandCodec.encodePlaceOrder(order(1, CoreOrderSide.SELL, 120)));
            byte[] makerBytes = CoreMessageCodec.encode(maker);
            service.onSessionMessage(null, 1_700_000_000_000L, new UnsafeBuffer(makerBytes),
                    0, makerBytes.length, header);
            service.onTimerEvent(SurprisingClusteredService.PIPELINE_TIMER_ID, 1_700_000_000_001L);
            service.state().assertClusterCallbackComplete();
        }

        public void runMultiFillRoundTrip() {
            long before = terminal, tradesBefore = batchTrades;
            expectBatchTrades = true;
            try {
                for (int phase = 0; phase < 4; phase++) {
                    boolean resting = phase == 0 || phase == 2;
                    responseBatchSize = resting ? batchSize : 1;
                    CoreMessage[] wave = new CoreMessage[maxInFlight];
                    for (int user = 0; user < maxInFlight; user++) {
                        long account = phase == 0 || phase == 3 ? 1256 : 1000 + user;
                        CoreOrderSide side = resting ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                        var orders = new ArrayList<PlaceOrderCommand>(responseBatchSize);
                        for (int item = 0; item < responseBatchSize; item++) {
                            orders.add(order(orderId++, side, 100, resting ? 1 : batchSize));
                        }
                        wave[user] = command(CoreMessageType.PLACE_ORDER_BATCH, account,
                                TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
                    }
                    for (CoreMessage request : wave) send(request);
                }
                drain();
                if (terminal - before != 1024 || batchTrades - tradesBefore != 512L * batchSize) {
                    throw new IllegalStateException("multi-fill terminal/trade mismatch");
                }
            } finally { expectBatchTrades = false; responseBatchSize = batchSize; }
        }

        public void runRoundTripTrades() {
            long before=terminal;singleResponses=true;
            try {
                CoreMessage[] wave=new CoreMessage[maxInFlight];
                for(int phase=0;phase<4;phase++) {
                    for(int user=0;user<maxInFlight;user++) {
                        long account=phase==0 || phase==3 ? 1256 : 1000+user;
                        CoreOrderSide side=phase==0 || phase==2 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                        wave[user]=command(CoreMessageType.PLACE_ORDER,account,
                                TradingCommandCodec.encodePlaceOrder(order(orderId++,side,100)));
                    }
                    // Exactly 256 requests are ready at the entry boundary in every closed-loop wave.
                    for(CoreMessage request:wave)send(request);
                }
                drain();
                if(terminal-before!=1024)throw new IllegalStateException("realtime trade terminal mismatch");
                if(realtime) {
                    service.state().captureRealtimeSnapshot(1000,Math.max(1,sequence),sequence,1_700_000_000_000L);
                    service.state().captureRealtimeBook("JMH-BTC-USDT",sequence,1_700_000_000_000L);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(service.state().realtimeSnapshotPending() || service.state().realtimeBookPending()) {
                        service.state().pollRealtimeSnapshot();service.state().pollRealtimeBook();
                        if(System.nanoTime()>deadline)throw new IllegalStateException("realtime snapshot timed out");
                    }
                }
            } finally {singleResponses=false;}
        }

        public void runTriggerRoundTrip() {
            long before = terminal;
            singleResponses = true;
            try {
                for (int phase = 0; phase < 6; phase++) {
                    CoreMessage[] wave = new CoreMessage[maxInFlight];
                    for (int user = 0; user < maxInFlight; user++) {
                        if (phase == 2) {
                            firstOrders[user] = orderId++;
                            var trigger = new com.surprising.aeron.service.state.model.CoreTriggerOrderState(
                                    firstOrders[user], productLine, 1_000 + user, "trigger-" + firstOrders[user], "",
                                    "JMH-BTC-USDT", CoreOrderSide.SELL, CoreTriggerOrderType.STOP_LOSS,
                                    CoreTriggerCondition.LESS_OR_EQUAL, 90, 0, 0, 0, 0, 0,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "",
                                    0, 0, 1, 1, 1);
                            wave[user] = command(CoreMessageType.PLACE_TRIGGER_ORDER, 1_000 + user,
                                    CoreTriggerOrderCodec.encodeState(trigger.view()));
                        } else if (phase == 3) {
                            wave[user] = command(CoreMessageType.CANCEL_TRIGGER_ORDER, 1_000 + user,
                                    CoreTriggerOrderCodec.encodeId(firstOrders[user]));
                        } else {
                            long account = phase == 0 || phase == 5 ? 1256 : 1000 + user;
                            CoreOrderSide side = phase == 0 || phase == 4 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                            wave[user] = command(CoreMessageType.PLACE_ORDER, account,
                                    TradingCommandCodec.encodePlaceOrder(order(orderId++, side, 100)));
                        }
                    }
                    for (CoreMessage message : wave) send(message);
                    drain();
                }
                if (terminal - before != 1536) throw new IllegalStateException("trigger terminal mismatch");
            } finally { singleResponses = false; }
        }

        public void runAmendRoundTripTrades() {
            long before = terminal;
            long fillsBefore = batchTrades;
            expectBatchTrades = true;
            try {
                for (int phase = 0; phase < 6; phase++) {
                    CoreMessage[] wave = new CoreMessage[maxInFlight];
                    singleResponses = phase == 0 || phase == 4;
                    for (int user = 0; user < maxInFlight; user++) {
                        if (singleResponses) {
                            CoreOrderSide side = phase == 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                            wave[user] = command(CoreMessageType.PLACE_ORDER, 1256,
                                    TradingCommandCodec.encodePlaceOrder(order(orderId++, side, 100, batchSize)));
                        } else if (phase == 1 || phase == 3) {
                            firstOrders[user] = orderId;
                            var orders = new ArrayList<PlaceOrderCommand>(batchSize);
                            for (int item = 0; item < batchSize; item++) {
                                orders.add(order(orderId++, phase == 1 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                                        phase == 1 ? 90 : 110));
                            }
                            wave[user] = command(CoreMessageType.PLACE_ORDER_BATCH, 1000 + user,
                                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
                        } else {
                            var orders = new ArrayList<AmendOrderCommand>(batchSize);
                            for (int item = 0; item < batchSize; item++) {
                                long replacement = orderId++;
                                orders.add(new AmendOrderCommand(firstOrders[user] + item, replacement,
                                        "cluster-amend-" + replacement, 100L, 1L, CoreTimeInForce.GTC, false));
                            }
                            wave[user] = command(CoreMessageType.AMEND_ORDER_BATCH, 1000 + user,
                                    TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(orders)));
                        }
                    }
                    // 256 requests ready before each wave enters the real service log callback.
                    for (CoreMessage request : wave) send(request);
                }
                if (terminal - before != 1536 || batchTrades - fillsBefore != 512L * batchSize) {
                    throw new IllegalStateException("amend batch terminal/fill mismatch");
                }
            } finally {
                expectBatchTrades = singleResponses = false;
            }
        }

        public void runIndependentCommandWindows() {
            long before = terminal;
            singleResponses = true;
            try {
                for (int user = 0; user < 256; user++) {
                    long id = orderId++;
                    firstOrders[user] = id;
                    String symbol = (user & 1) == 0 ? "JMH-PIPE-A-USDT" : pipelineSymbolB;
                    send(command(CoreMessageType.PLACE_ORDER, 1_000 + user,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(id, symbol, 1,
                                    CoreOrderSide.BUY, 90, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "pipeline-" + id))));
                }
                drain();
                for (int user = 0; user < 256; user++) send(command(CoreMessageType.CANCEL_ORDER, 1_000 + user,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(firstOrders[user]))));
                drain();
                if (terminal - before != 512 || service.commandWindowHighWaterMark() < 2)
                    throw new IllegalStateException("independent commands did not pipeline and complete");
            } finally { singleResponses = false; }
        }

        public void runIndependentBatchWindows() {
            long before = terminal;
            if (realtime && snapshotRequests.isEmpty() && !service.state().realtimeSnapshotPending())
                snapshotRequests.offer(new RealtimeFrame(productLine, RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                        1_000, 0, 0, 1_700_000_000_000L, sequence + 1, "", "", new byte[0]));
            for (int user = 0; user < 256; user++) {
                firstOrders[user] = orderId;
                String symbol = (user & 1) == 0 ? "JMH-PIPE-A-USDT" : pipelineSymbolB;
                var orders = new ArrayList<PlaceOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) {
                    long id = orderId++;
                    orders.add(new PlaceOrderCommand(id, symbol, 1, CoreOrderSide.BUY, 90, 1,
                            false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "batch-window-" + id));
                }
                send(command(CoreMessageType.PLACE_ORDER_BATCH, 1_000 + user,
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
            }
            drain();
            for (int user = 0; user < 256; user++) {
                var orders = new ArrayList<CancelOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) orders.add(new CancelOrderCommand(firstOrders[user] + item));
                send(command(CoreMessageType.CANCEL_ORDER_BATCH, 1_000 + user,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders))));
            }
            drain();
            if (terminal - before != 512 || service.commandWindowHighWaterMark() < 2)
                throw new IllegalStateException("independent batches did not pipeline and complete");
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

        public void runRejectedContinuations() {
            long before = terminal;
            long rejectedBefore = rejectedItems;
            expectMissingCancels = true;
            for (int user = 0; user < 256; user++) {
                if (user == 0 || user == 255) {
                    firstOrders[user] = orderId;
                    var orders = new ArrayList<PlaceOrderCommand>(batchSize);
                    for (int item = 0; item < batchSize; item++) {
                        orders.add(order(orderId++, CoreOrderSide.BUY, 90));
                    }
                    send(command(CoreMessageType.PLACE_ORDER_BATCH, 1_000 + user,
                            TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
                } else {
                    var missing = new ArrayList<CancelOrderCommand>(batchSize);
                    for (int item = 0; item < batchSize; item++) {
                        missing.add(new CancelOrderCommand(Long.MAX_VALUE - user - item * 256L));
                    }
                    send(command(CoreMessageType.CANCEL_ORDER_BATCH, 1_000 + user,
                            TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(missing))));
                }
            }
            drain();
            expectMissingCancels = false;
            for (int boundary = 0; boundary < 2; boundary++) {
                int user = boundary == 0 ? 0 : 255;
                var orders = new ArrayList<CancelOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) {
                    orders.add(new CancelOrderCommand(firstOrders[user] + item));
                }
                send(command(CoreMessageType.CANCEL_ORDER_BATCH, 1_000 + user,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders))));
            }
            drain();
            if (terminal - before != 258 || rejectedItems - rejectedBefore != 254L * batchSize) {
                throw new IllegalStateException("rejected continuation lost its terminal response");
            }
        }

        private void send(CoreMessage message) {
            if (singleResponses) singleRequestIds.add(message.header().correlationId());
            else if (message.header().messageType() == CoreMessageType.PLACE_ORDER_BATCH
                    || message.header().messageType() == CoreMessageType.CANCEL_ORDER_BATCH
                    || message.header().messageType() == CoreMessageType.AMEND_ORDER_BATCH)
                batchRequestSizes.put(message.header().correlationId(), responseBatchSize);
            byte[] bytes = CoreMessageCodec.encode(message);
            service.onSessionMessage(session, 1_700_000_000_000L, new UnsafeBuffer(bytes), 0, bytes.length, header);
            maxBacklog = Math.max(maxBacklog, service.state().pendingMatchingCount());
        }

        private void drain() {
            service.onTimerEvent(SurprisingClusteredService.PIPELINE_TIMER_ID, 1_700_000_000_001L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            // A replicated timer drains the final partial command window, including at low traffic.
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
            return order(id, side, price, 1);
        }

        private PlaceOrderCommand order(long id, CoreOrderSide side, long price, long quantity) {
            return new PlaceOrderCommand(id, "JMH-BTC-USDT", 1, side, price, quantity, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                    false, "cluster-batch-" + id);
        }

        private static boolean nonFlat(com.surprising.aeron.service.state.model.CorePositionState p) {
            return p.signedQuantitySteps()!=0 || p.positionMarginUnits()!=0 || p.entryPriceTicks()!=0
                    || p.entryValueTicks()!=0 || p.instrumentChangeId()!=0 || p.realizedPnlUnits()!=0;
        }

        @TearDown(Level.Iteration)
        public void close() {
            if (service == null) return;
            try {
                drain();
                var state = service.state().tradingState();
                for (int user = 0; user < 256; user++) {
                    var balance = state.users().get(1_000L + user).balances().get(settleAsset);
                    if (balance.availableUnits() != BALANCE || balance.lockedUnits() != 0) {
                        throw new IllegalStateException("cancel did not restore user funds");
                    }
                    var base = state.users().get(1_000L + user).balances().get("BTC");
                    if (productLine == ProductLine.SPOT && base != null && base.totalUnits() != 0) {
                        throw new IllegalStateException("user BTC conservation mismatch");
                    }
                    if (!state.users().get(1_000L + user).reservations().isEmpty()
                            || state.users().get(1_000L + user).positions().values().stream().anyMatch(Workload::nonFlat)) {
                        throw new IllegalStateException("terminal user retained reservations or positions");
                    }
                }
                var maker = state.users().get(1_256L);
                if (maker.balances().get(settleAsset).totalUnits() != BALANCE || maker.positions().values().stream().anyMatch(Workload::nonFlat)
                        || maker.reservations().size() != 1) throw new IllegalStateException("maker funds mismatch");
                if (productLine == ProductLine.SPOT && maker.balances().get("BTC").totalUnits() != makerBaseBalance) {
                    throw new IllegalStateException("maker BTC conservation mismatch");
                }
                if (state.triggerOrders().values().stream().anyMatch(t -> t.status().open()))
                    throw new IllegalStateException("open trigger retained after cancel");
                if (state.orders().size() != 1 || state.order(1) == null) {
                    throw new IllegalStateException("terminal order retention mismatch");
                }
                if (state.clientOrderIndex().size() != 1
                        || state.clientOrderIndex().values().stream().anyMatch(id -> id != 1L)) {
                    throw new IllegalStateException("terminal order retained client aliases");
                }
                var metrics = service.state().laneMetrics();
                if (metrics.accountLaneCount() != accountLanes
                        || metrics.accountLaneQueueDepths().length != accountLanes
                        || metrics.accountLaneCompletedOperations().length != accountLanes * 4
                        || metrics.accountLaneLatencySamples().length != accountLanes * 4) {
                    throw new IllegalStateException("direct Lane metrics shape mismatch");
                }
                long[] revisions = metrics.accountLaneRevisions();
                long firstRevision = revisions[0];
                revisions[0] = -1;
                if (metrics.accountLaneRevisions()[0] != firstRevision) {
                    throw new IllegalStateException("Lane metrics exposed mutable snapshot arrays");
                }
                byte[] snapshot = service.state().snapshot();
                try (CoreProbeState restored = CoreProbeState.fromSnapshot(productLine, snapshot)) {
                    if (restored.tradingState().businessStateHash() != state.businessStateHash()
                            || !restored.tradingState().clientOrderIndex().equals(state.clientOrderIndex())) {
                        throw new IllegalStateException("snapshot recovery mismatch");
                    }
                }
                System.out.printf("clusterBatch acceptedCore=%d terminalCore=%d unfinished=0 endBacklog=0 maxBacklog=%d queries=%d%n",
                        terminal, terminal, maxBacklog, queryResults);
            } finally {
                service.onTerminate(null);
                consuming=false;
                if(realtimeConsumer!=null){try{realtimeConsumer.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                if(realtimeOutbox!=null)System.out.println("realtimeDroppedBatches="+realtimeOutbox.droppedBatches());
                service = null;
                if (previousSpinLimit == null) System.clearProperty("surprising.aeron.settlement-spin-limit");
                else System.setProperty("surprising.aeron.settlement-spin-limit", previousSpinLimit);
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
