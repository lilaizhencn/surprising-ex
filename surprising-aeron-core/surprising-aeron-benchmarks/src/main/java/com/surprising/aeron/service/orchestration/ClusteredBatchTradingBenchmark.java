package com.surprising.aeron.service.orchestration;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ClusteredBatchTradingBenchmark {
    /** 风险评估在实际开仓和平仓之间完成，包括扫描结束后的空续扫。 */
    @Benchmark
    public long riskScanBetweenOpenAndClose(Workload workload, Counters counters) {
        long riskCommands = workload.runRoundTripTrades(true);
        long operations = 1024 + riskCommands;
        counters.acceptedBusinessOperations += operations;
        counters.terminalBusinessOperations += operations;
        counters.acceptedCoreMessages += operations;
        counters.terminalCoreMessages += operations;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** 批量命令携带风险续扫，覆盖其异步 Lane 终态提交。 */
    @Benchmark
    public long batchRiskScanBetweenOpenAndClose(Workload workload, Counters counters) {
        long riskCommands = workload.runRoundTripTrades(true, true);
        long operations = 1024 + riskCommands;
        counters.acceptedBusinessOperations += operations;
        counters.terminalBusinessOperations += operations;
        counters.acceptedCoreMessages += operations;
        counters.terminalCoreMessages += operations;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** 满64条独立命令的提交边界必须覆盖整个窗口，不依赖额外timer补交剩余命令。 */
    @Benchmark
    public long fullWindowCommit(Workload workload, Counters counters) {
        workload.runIndependentCommandWindows(true);
        counters.acceptedBusinessOperations += 512;
        counters.terminalBusinessOperations += 512;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        return workload.terminal;
    }
    /** 单项批量命令走顺序撮合续接，覆盖等待期间的 owner 上下文交接；使用 batchSize=1。 */
    @Benchmark
    public long sequentialBatchContextHandoff(Workload workload, Counters counters) {
        if (workload.batchSize != 1) throw new IllegalArgumentException("requires batchSize=1");
        return independentBatchWindows(workload, counters);
    }
    /** 异步 Lane 完成后才恢复提交上下文：覆盖普通单、改单和批量撤单的服务回调路径。 */
    @Benchmark
    public long laneCompletionContextHandoff(Workload workload, Counters counters) {
        return decodedBatchAdmissionAndSettlement(workload, counters);
    }
    /** Shared non-crossing maker liquidity plus alternating-symbol batches exercises price scopes and prefix commits. */
    @Benchmark
    public long priceScopedBatchWindows(Workload workload, Counters counters) {
        workload.runPriceScopedBatchWindows();
        counters.acceptedBusinessOperations += 512L * workload.batchSize + 4;
        counters.terminalBusinessOperations += 512L * workload.batchSize + 4;
        counters.acceptedCoreMessages += 516;
        counters.terminalCoreMessages += 516;
        return workload.terminal;
    }
    /** 多订单簿分区的独立批量下撤单，覆盖分区派发游标与最终资金/快照核对。 */
    @Benchmark
    public long independentBatchWindows(Workload workload, Counters counters) {
        workload.runIndependentBatchWindows();
        counters.acceptedBusinessOperations += 512L * workload.batchSize;
        counters.terminalBusinessOperations += 512L * workload.batchSize;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        return workload.terminal;
    }
    /** 同一账户跨两个撮合分区下单，共享资金保持串行依赖，其余账户继续并行。 */
    @Benchmark
    public long sharedAccountPartitionWindows(Workload workload, Counters counters) {
        workload.runIndependentBatchWindows(true);
        counters.acceptedBusinessOperations += 512L * workload.batchSize;
        counters.terminalBusinessOperations += 512L * workload.batchSize;
        counters.acceptedCoreMessages += 512;
        counters.terminalCoreMessages += 512;
        return workload.terminal;
    }
    /** Cross-callback independent orders and cancellations. Cancellation scopes use the removed
     * order's side/price, exercising range dependency checks without a whole-symbol drain.
     * Repeated rounds also exercise bounded publication-index turnover and receipt removal. */
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

    /** 普通准入及结算发布缓冲反复复用；保留实际成交、排空与资金/恢复核对。 */
    @Benchmark
    public long singleOrderSettlementReuse(Workload workload, Counters counters) {
        for (int round = 0; round < 2; round++) {
            workload.runRoundTripTrades();
            if (workload.service.state().runtimeState.hasPendingReservations())
                throw new IllegalStateException("reservation turnover retained pending users");
            counters.acceptedBusinessOperations += 1024;
            counters.terminalBusinessOperations += 1024;
            counters.acceptedCoreMessages += 1024;
            counters.terminalCoreMessages += 1024;
            counters.terminalTrades += 512;
        }
        return workload.terminal;
    }

    /** 重复普通成交并排空预留：覆盖按资产复用计数表、删除完成用户和下一轮重新准入。 */
    @Benchmark
    // Also exercises metadata/remaining-quantity updates without rebuilding the Lane admission aggregate.
    public long reservationCompletionLifecycle(Workload workload, Counters counters) {
        return singleOrderSettlementReuse(workload, counters);
    }

    /** 账户成交与 Owner 定时器控制交替，检验控制边界不转移账户所有权。 */
    @Benchmark
    public long ownerTimerAndTrading(Workload workload, Counters counters) {
        workload.runOwnerTimerUpdates();
        workload.runRoundTripTrades();
        counters.acceptedBusinessOperations += 1152;
        counters.terminalBusinessOperations += 1152;
        counters.acceptedCoreMessages += 1152;
        counters.terminalCoreMessages += 1152;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** 固定算法单反复更新并穿插真实账户成交，避免用无限增长状态测试更新路径。 */
    @Benchmark
    public long algoLaneAndTrading(Workload workload, Counters counters) {
        workload.runAlgoLaneUpdates();
        workload.runRoundTripTrades();
        counters.acceptedBusinessOperations += 1088;
        counters.terminalBusinessOperations += 1088;
        counters.acceptedCoreMessages += 1088;
        counters.terminalCoreMessages += 1088;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** 故障诊断：Lane 已写入后注入全局版本溢出，覆盖账户异步回滚后继续成交；非容量场景。 */
    @Benchmark
    public long algoLaneRollbackAndTrading(Workload workload, Counters counters) {
        workload.runAlgoLaneUpdates();
        workload.runAlgoRollbackFailure();
        workload.runRoundTripTrades();
        counters.acceptedBusinessOperations += 1089;
        counters.terminalBusinessOperations += 1089;
        counters.acceptedCoreMessages += 1089;
        counters.terminalCoreMessages += 1089;
        counters.rejectedBusinessOperations++;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** Core故障诊断：账户已修改后终态容量校验失败；非真实Cluster容量场景。 */
    @Benchmark
    public long finalizationFailureAndTrading(Workload workload, Counters counters) throws Exception {
        workload.runFinalizationFailure();
        workload.runRoundTripTrades();
        counters.acceptedBusinessOperations += 1025;
        counters.terminalBusinessOperations += 1025;
        counters.acceptedCoreMessages += 1025;
        counters.terminalCoreMessages += 1025;
        counters.rejectedBusinessOperations++;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** Keep protective triggers through actual fills that close derivative positions. */
    @Benchmark
    public long closePositionWithPendingTriggers(Workload workload, Counters counters) {
        workload.runTriggerRoundTrip(true);
        counters.acceptedBusinessOperations += 1280;
        counters.terminalBusinessOperations += 1280;
        counters.acceptedCoreMessages += 1280;
        counters.terminalCoreMessages += 1280;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    @Benchmark
    public long partialThenFullCloseWithTriggers(Workload workload, Counters counters) {
        workload.runTriggerRoundTrip(true, true);
        counters.acceptedBusinessOperations += 1792;
        counters.terminalBusinessOperations += 1792;
        counters.acceptedCoreMessages += 1792;
        counters.terminalCoreMessages += 1792;
        counters.terminalTrades += 768;
        return workload.terminal;
    }

    @Benchmark
    public long batchCloseWithPendingTriggers(Workload workload, Counters counters) {
        workload.runTriggerRoundTrip(true, false, true);
        long business = 1024 + 256L * workload.batchSize;
        counters.acceptedBusinessOperations += business;
        counters.terminalBusinessOperations += business;
        counters.acceptedCoreMessages += 1280;
        counters.terminalCoreMessages += 1280;
        counters.terminalTrades += 256L * (workload.batchSize + 1);
        counters.terminalBatches += 256;
        counters.terminalItems += 256L * workload.batchSize;
        return workload.terminal;
    }

    /** 大/小批次交替复用相同结算池，覆盖尾槽隔离、资金和终态响应。 */
    @Benchmark
    public long variableBatchSettlementReuse(Workload workload, Counters counters) {
        int originalSize = workload.batchSize;
        try {
            for (int phase = 0; phase < 4; phase++) {
                workload.batchSize = switch (phase) {
                    case 1 -> 1;
                    case 2 -> Math.max(1, originalSize / 2);
                    default -> originalSize;
                };
                multiFillSettlementAndEncoding(workload, counters);
            }
            return workload.terminal;
        } finally {
            workload.batchSize = originalSize;
            workload.responseBatchSize = originalSize;
        }
    }

    /** 触发单所属 Lane 控制派发、撮合和终态提交的完整往返。 */
    @Benchmark
    public long triggerLaneControlRoundTrip(Workload workload, Counters counters) {
        workload.runTriggerRoundTrip();
        counters.acceptedBusinessOperations += 1536;
        counters.terminalBusinessOperations += 1536;
        counters.acceptedCoreMessages += 1536;
        counters.terminalCoreMessages += 1536;
        counters.terminalTrades += 512;
        return workload.terminal;
    }

    /** Trigger publication/removal plus successful batch place/cancel completion. */
    @Benchmark
    public long ownerTriggerAndBatchCompletion(Workload workload, Counters counters) {
        triggerLaneControlRoundTrip(workload, counters);
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
        if (workload.interleavedMetrics) counters.queries += 512;
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

    /** 连续两轮批量改单，覆盖命令变更集合在账户提交与输出之间的重复消费。 */
    @Benchmark
    public long repeatedAmendMetadata(Workload workload, Counters counters) {
        batchAmendRoundTripTrades(workload, counters);
        return batchAmendRoundTripTrades(workload, counters);
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
        /** 订单簿分区数；账户路由和资金共享规则独立于该参数。 */
        @Param({"1", "2", "4"}) public int matchingEngines = 1;
        private String previousMatchingEngines;
        @Param("20") public int batchSize;
        @Param("256") public int maxInFlight;
        @Param("false") public boolean realtime;
        /** Explicitly separate trading capacity from the historical per-command audit query load. */
        @Param("true") public boolean interleavedMetrics = true;
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
        private TradingCoreOwner service;
        private ClientSession session;
        private byte[] responseScratch = new byte[4096];
        private final UnsafeBuffer responseBuffer = new UnsafeBuffer(responseScratch);
        private final Header header = new Header(0, 0).buffer(new UnsafeBuffer(new byte[64]))
                .offset(0).initialTermId(0).positionBitsToShift(16);
        private long sequence;
        private long orderId = 1_000;
        private long terminal;
        private long queryResults;
        private long maxBacklog;
        private boolean expectMissingCancels;
        private boolean expectSingleRejection;
        private long rejectedItems;
        private boolean expectBatchTrades;
        private long batchTrades;
        private long makerBaseBalance;
        private final long[] firstOrders = new long[256];
        private static final long BALANCE = 1_000_000_000L;

        /** Local benchmark consumes encoded responses immediately; real-cluster runs use ClusterServiceEgress. */
        private void publishResponse(ClientSession target, CoreMessageHeader responseHeader,
                                     CoreResponse response, long committedSequence) {
            try {
                if (target == null || target.isClosing()) return;
                int length = CoreMessageCodec.encodedResponseLength(response);
                if (responseScratch.length < length) {
                    responseScratch = new byte[length];
                    responseBuffer.wrap(responseScratch);
                }
                CoreMessageCodec.encodeResponse(responseHeader, response, committedSequence, responseScratch);
                if (target.offer(responseBuffer, 0, length) < 0)
                    throw new IllegalStateException("local benchmark response was not consumed");
            } finally {
                service.releaseResponse(response);
            }
        }

        @Setup(Level.Iteration)
        public void setup() {
            if (maxInFlight != 256 || batchSize <= 0) throw new IllegalArgumentException("requires 256 in-flight");
            previousMatchingEngines = System.getProperty("surprising.aeron.matching-engines");
            System.setProperty("surprising.aeron.matching-engines", Integer.toString(matchingEngines));
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
            service = new TradingCoreOwner(productLine, this::publishResponse);
            snapshotRequests = service.snapshotRequests();
            // Aeron invokes background callbacks while its service idle strategy is running.
            // Define that path here too; performance execution remains on the real cluster.
            var serviceIdle = new org.agrona.concurrent.IdleStrategy() {
                public void idle(int work) { if (realtime) service.pollBackgroundWork(System.nanoTime()); }
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
            service.start(cluster);
            if(realtime) {
                realtimeOutbox=new com.surprising.aeron.client.RealtimeOutbox(8192,8*1024*1024);
                service.attachRealtime(realtimeOutbox, null);consuming=true;
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
                                if (expectSingleRejection && response.status() == ResponseStatus.REJECTED
                                        && singleRequestIds.remove(message.header().correlationId())) {
                                    terminal++;
                                    yield 1L;
                                }
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
            service.sessionOpened();
            ContractType type=ContractType.valueOf(productLine.contractTypeCode());
            settleAsset=type.isInverse()?"BTC":"USDT";
            pipelineSymbolB = "JMH-PIPE-B-USDT";
            String[] startupSymbols = {"JMH-PIPE-A-USDT", pipelineSymbolB, "JMH-BTC-USDT"};
            for (String symbol : startupSymbols) {
                apply(CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand(symbol, type.ordinal(), "BTC", "USDT", settleAsset, 1, 1,
                                type.isInverse() ? 1000 : 1, 100_000, 50_000, 0, 0,
                                type.isDelivery() || type.isOption() ? 2_000_000_000_000L : 0,
                                type.isOption() ? 0 : -1, type.isOption() ? 100 : 0)));
            }
            if (productLine.isDerivative()) {
                for (String symbol : startupSymbols) apply(CoreMessageType.APPLY_MARK_PRICE, 0,
                        TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                                ? new ApplyMarkPriceCommand(symbol, 100, 100, 100, 1, 1_700_000_000_000L)
                                : new ApplyMarkPriceCommand(symbol, 100, 1, 1_700_000_000_000L)));
            }
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
            service.acceptCommittedCommand(null, com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder.decode(
                            new UnsafeBuffer(makerBytes), 0, makerBytes.length),
                    1_700_000_000_000L, header.position());
            drain();
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

        public void runRoundTripTrades() { runRoundTripTrades(false); }

        long runRoundTripTrades(boolean scanRisk) { return runRoundTripTrades(scanRisk, false); }

        long runRoundTripTrades(boolean scanRisk, boolean batchRisk) {
            if (scanRisk && !productLine.isDerivative()) throw new IllegalArgumentException("requires derivatives");
            long riskCommands = 0;
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
                    if (scanRisk && phase == 1) {
                        drain();
                        riskCommands = runRiskContinuations(batchRisk);
                    }
                }
                drain();
                if(terminal-before!=1024 + riskCommands)throw new IllegalStateException("realtime trade terminal mismatch");
                if(realtime) {
                    service.state().captureRealtimeSnapshot(1000,Math.max(1,sequence),sequence,1_700_000_000_000L);
                    service.state().captureRealtimeBook("JMH-BTC-USDT",sequence,1_700_000_000_000L);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(service.state().realtimeSnapshotPending() || service.state().realtimeBookPending()) {
                        service.state().pollRealtimeSnapshot();service.state().pollRealtimeBook();
                        if(System.nanoTime()>deadline)throw new IllegalStateException("realtime snapshot timed out");
                    }
                }
                return riskCommands;
            } finally {singleResponses=false;}
        }

        private long runRiskContinuations(boolean batchRisk) {
            long before = terminal;
            long priceSequence = sequence + 1;
            send(command(CoreMessageType.APPLY_MARK_PRICE, 0,
                    TradingCommandCodec.encodeApplyMarkPrice(productLine == ProductLine.OPTION
                            ? new ApplyMarkPriceCommand("JMH-BTC-USDT", 100, 100, 100,
                                    priceSequence, 1_700_000_000_000L)
                            : new ApplyMarkPriceCommand("JMH-BTC-USDT", 100,
                                    priceSequence, 1_700_000_000_000L))));
            drain();
            int rounds = 0;
            do {
                if (++rounds > 1024) throw new IllegalStateException("risk continuation did not finish");
                var scan = service.state().runtimeState.firstRiskIncompleteScan();
                if (batchRisk && scan != null) {
                    var continuation = new CoreRiskScanContinuation(
                            service.state().identities.symbol(scan.symbolId()), scan.priceSequence(), scan.lastUserId());
                    send(command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, 0,
                            TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                                    java.util.List.of(), 64, 0, continuation, 64))));
                } else {
                    send(command(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                            TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
                }
                drain();
            } while (service.state().runtimeState.firstIncompleteRiskScan() != null);
            // Empty continuation must not pause otherwise idle account workers either.
            send(command(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            drain();
            return terminal - before;
        }

        public void runFinalizationFailure() throws Exception {
            var owner = service.state();
            var runtime = owner.runtimeState;
            int assetId = owner.identities.assetId("USDT");
            var before = runtime.balance(1000, assetId);
            long available = before == null ? 0 : before.availableUnits();
            long locked = before == null ? 0 : before.lockedUnits();
            var request = command(CoreMessageType.ADJUST_BALANCE, 1000,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1)));
            CoreResponse result;
            runtime.enterAsynchronousCommandScope();
            try {
                if (owner.applyDecodedCommand(request, 1_700_000_000_000L, sequence, null, false) != null)
                    throw new IllegalStateException("fault command did not enter continuation");
                var business = owner.directCommand.controlWork();
                owner.directCommand.replaceControlWork(() -> {
                    if (!business.getAsBoolean()) return false;
                    // Deliberate capacity fault: this sentinel must be cleared before dispatch.
                    owner.admissions.queuedMatching.addAll(java.util.Collections.nCopies(
                                owner.pendingMatching.capacity() + 1, null));
                    return true;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                do {
                    result = owner.pollDirectCommand();
                    if (System.nanoTime() > deadline) throw new IllegalStateException("fault rollback timeout");
                } while (result == null);
            } finally { runtime.exitAsynchronousCommandScope(); }
            if (result.status() != ResponseStatus.REJECTED || result.resultCode() != CoreResultCode.MATCHING_BACKPRESSURE)
                throw new IllegalStateException("terminal capacity failure was not rejected");
            var after = runtime.balance(1000, assetId);
            if ((after == null ? 0 : after.availableUnits()) != available
                    || (after == null ? 0 : after.lockedUnits()) != locked)
                throw new IllegalStateException("terminal failure changed account funds");
            terminal++;
        }

        public void runAlgoRollbackFailure() {
            var runtime = service.state().runtimeState;
            var before = runtime.algoOrder(9_000_000);
            long runtimeRevision = runtime.revision();
            long revision = before.revision() + 1;
            var next = new com.surprising.aeron.protocol.CoreAlgoOrderView(9_000_000, 1000,
                    "algo-lane-0", "JMH-BTC-USDT", 0, CoreOrderSide.BUY, 0, 100, 10, 1, 10,
                    CoreMarginMode.CROSS, CorePositionSide.NET, false, false, CoreTimeInForce.IOC,
                    0, 0, "", "trace", 1, 1, 0, 1, revision, revision, java.util.List.of(), 0, 0, 0);
            long terminalBefore = terminal;
            singleResponses = true;
            expectSingleRejection = true;
            runtime.setMetadata(productLine, Long.MAX_VALUE);
            try {
                send(command(CoreMessageType.UPSERT_ALGO_ORDER, 1000,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(next)));
                drain();
                if (terminal != terminalBefore + 1 || !before.equals(runtime.algoOrder(9_000_000)))
                    throw new IllegalStateException("failed algo update was not restored");
            } finally {
                runtime.setMetadata(productLine, runtimeRevision);
                expectSingleRejection = false;
                singleResponses = false;
            }
        }

        public void runAlgoLaneUpdates() {
            long before = terminal;
            singleResponses = true;
            try {
                for (int i = 0; i < 64; i++) {
                    long id = 9_000_000L + i;
                    var current = service.state().runtimeState.algoOrder(id);
                    long revision = current == null ? 1 : current.revision() + 1;
                    var algo = new com.surprising.aeron.protocol.CoreAlgoOrderView(id, 1000 + i,
                            "algo-lane-" + i, "JMH-BTC-USDT", 0, CoreOrderSide.BUY, 0, 100, 10, 1, 10,
                            CoreMarginMode.CROSS, CorePositionSide.NET, false, false, CoreTimeInForce.IOC,
                            0, 0, "", "trace", 1, 1, 0, 1, revision, revision, java.util.List.of(), 0, 0, 0);
                    send(command(CoreMessageType.UPSERT_ALGO_ORDER, 1000 + i,
                            com.surprising.aeron.protocol.CoreAlgoOrderCodec.encode(algo)));
                }
                drain();
                if (terminal - before != 64) throw new IllegalStateException("algo updates unfinished");
            } finally { singleResponses = false; }
        }

        public void runOwnerTimerUpdates() {
            long before = terminal;
            singleResponses = true;
            try {
                for (int i = 0; i < 128; i++) {
                    var timer = new com.surprising.aeron.protocol.CoreCancelAllAfterCommand(
                            com.surprising.aeron.protocol.CoreCancelAllAfterAction.SET,
                            1000 + i, "JMH-BTC-USDT", 1000, 2000, 0, 0, 0, 1000);
                    send(command(CoreMessageType.UPDATE_CANCEL_ALL_AFTER, 1000 + i,
                            com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeCommand(timer)));
                }
                drain();
                if (terminal - before != 128) throw new IllegalStateException("timer controls unfinished");
            } finally { singleResponses = false; }
        }

        public void runTriggerRoundTrip() { runTriggerRoundTrip(false); }

        public void runTriggerRoundTrip(boolean automaticClose) { runTriggerRoundTrip(automaticClose, false); }

        public void runTriggerRoundTrip(boolean automaticClose, boolean partialFirst) {
            runTriggerRoundTrip(automaticClose, partialFirst, false);
        }

        public void runTriggerRoundTrip(boolean automaticClose, boolean partialFirst, boolean batchClosing) {
            if (automaticClose && !productLine.isDerivative())
                throw new IllegalArgumentException("position-close triggers require derivatives");
            long before = terminal;
            boolean previousBatchTrades = expectBatchTrades;
            int previousResponseSize = responseBatchSize;
            singleResponses = true;
            if (batchClosing) { expectBatchTrades = true; responseBatchSize = batchSize; }
            try {
                for (int phase = 0; phase < (partialFirst ? 8 : 6); phase++) {
                    if (automaticClose && phase == 3) continue;
                    singleResponses = !(batchClosing && phase == 5);
                    CoreMessage[] wave = new CoreMessage[maxInFlight];
                    for (int user = 0; user < maxInFlight; user++) {
                        if (phase == 2) {
                            firstOrders[user] = orderId++;
                            var trigger = new com.surprising.aeron.service.state.model.CoreTriggerOrderState(
                                    firstOrders[user], productLine, 1_000 + user, "trigger-" + firstOrders[user], "",
                                    "JMH-BTC-USDT", service.state().tradingState().instruments().get("JMH-BTC-USDT"),
                                    CoreOrderSide.SELL, CoreTriggerOrderType.STOP_LOSS,
                                    CoreTriggerCondition.LESS_OR_EQUAL, 90, 0, 0, 0, 0, 0,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, 90, 1, CoreMarginMode.CROSS,
                                    CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "",
                                    0, 0, 1, 1, 1);
                            wave[user] = command(CoreMessageType.PLACE_TRIGGER_ORDER, 1_000 + user,
                                    CoreTriggerOrderCodec.encodeState(trigger.view()));
                        } else if (phase == 3) {
                            wave[user] = command(CoreMessageType.CANCEL_TRIGGER_ORDER, 1_000 + user,
                                    CoreTriggerOrderCodec.encodeId(firstOrders[user]));
                        } else if (batchClosing && phase == 5) {
                            var orders = new ArrayList<PlaceOrderCommand>(batchSize);
                            for (int item = 0; item < batchSize; item++)
                                orders.add(order(orderId++, CoreOrderSide.SELL, 100));
                            wave[user] = command(CoreMessageType.PLACE_ORDER_BATCH, 1000 + user,
                                    TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders)));
                        } else {
                            long account = phase == 0 || phase == 5 || phase == 7 ? 1256 : 1000 + user;
                            CoreOrderSide side = phase == 0 || phase == 4 || phase == 6 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                            if (batchClosing && phase == 4) { account = 1256; side = CoreOrderSide.BUY; }
                            long quantity = batchClosing && (phase < 2 || phase == 4) ? batchSize
                                    : partialFirst && phase < 2 ? 2 : 1;
                            wave[user] = command(CoreMessageType.PLACE_ORDER, account,
                                    TradingCommandCodec.encodePlaceOrder(order(orderId++, side, 100, quantity)));
                        }
                    }
                    for (CoreMessage message : wave) send(message);
                    drain();
                    if (partialFirst && phase == 5) for (int user = 0; user < maxInFlight; user++) {
                        var trigger = service.state().runtimeState.triggerOrder(firstOrders[user]);
                        if (trigger == null || trigger.status() != CoreTriggerOrderStatus.PENDING)
                            throw new IllegalStateException("partial close canceled protective trigger");
                    }
                }
                if (terminal - before != (partialFirst ? 1792 : automaticClose ? 1280 : 1536))
                    throw new IllegalStateException("trigger terminal mismatch");
                if (automaticClose) for (int user = 0; user < maxInFlight; user++) {
                    long id = firstOrders[user];
                    var trigger = service.state().runtimeState.triggerOrder(id);
                    if (trigger != null && trigger.status() != CoreTriggerOrderStatus.CANCELED)
                        throw new IllegalStateException("closed position retains trigger " + id + ": " + trigger.status());
                    if (trigger == null && !service.state().terminalRetention.containsTrigger(
                            id, 1000 + user, "trigger-" + id))
                        throw new IllegalStateException("closed trigger lost terminal identity");
                }
            } finally {
                singleResponses = false;
                expectBatchTrades = previousBatchTrades;
                responseBatchSize = previousResponseSize;
            }
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
                drain();
                if (terminal - before != 1536 || batchTrades - fillsBefore != 512L * batchSize) {
                    throw new IllegalStateException("amend batch terminal/fill mismatch");
                }
            } finally {
                expectBatchTrades = singleResponses = false;
            }
        }

        public void runIndependentCommandWindows() {
            runIndependentCommandWindows(false);
        }

        public void runIndependentCommandWindows(boolean fullWindowBoundary) {
            long before = terminal;
            singleResponses = true;
            try {
                for (int user = 0; user < 256; user++) {
                    long id = orderId++;
                    firstOrders[user] = id;
                    String symbol = (user & 1) == 0 ? "JMH-PIPE-A-USDT" : pipelineSymbolB;
                    send(command(CoreMessageType.PLACE_ORDER, 1_000 + user,
                            TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(id, symbol,
                                    CoreOrderSide.BUY, 90, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                    CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "pipeline-" + id))));
                }
                if (fullWindowBoundary) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (service.pendingCommandCount() != 0) {
                        service.sessionOpened();
                        if (System.nanoTime() > deadline)
                            throw new IllegalStateException("full window waited for another timer");
                        Thread.onSpinWait();
                    }
                } else drain();
                for (int user = 0; user < 256; user++) send(command(CoreMessageType.CANCEL_ORDER, 1_000 + user,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(firstOrders[user]))));
                drain();
                if (terminal - before != 512 || service.commandWindowHighWaterMark() < 2)
                    throw new IllegalStateException("independent commands did not pipeline and complete");
            } finally { singleResponses = false; }
        }

        public void runPriceScopedBatchWindows() {
            long first = orderId++;
            long second = orderId++;
            String[] symbols = {"JMH-PIPE-A-USDT", pipelineSymbolB};
            singleResponses = true;
            try {
                for (int i = 0; i < 2; i++) send(command(CoreMessageType.PLACE_ORDER, 1256,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(i == 0 ? first : second,
                                symbols[i], CoreOrderSide.SELL, 120, 1, false, CoreMarginMode.CROSS,
                                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, ""))));
                drain();
                singleResponses = false;
                runIndependentBatchWindows();
                singleResponses = true;
                for (long id : new long[]{first, second}) send(command(CoreMessageType.CANCEL_ORDER, 1256,
                        TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(id))));
                drain();
            } finally { singleResponses = false; }
        }

        public void runIndependentBatchWindows() { runIndependentBatchWindows(false); }

        public void runIndependentBatchWindows(boolean sharedAccounts) {
            long before = terminal;
            if (realtime && snapshotRequests.isEmpty() && !service.state().realtimeSnapshotPending())
                snapshotRequests.offer(new RealtimeFrame(productLine, RealtimeFrame.Kind.SNAPSHOT_REQUEST,
                        1_000, 0, 0, 1_700_000_000_000L, sequence + 1, "", "", new byte[0]));
            for (int user = 0; user < 256; user++) {
                firstOrders[user] = orderId;
                String symbol = (sharedAccounts ? user < 128 : (user & 1) == 0) ? "JMH-PIPE-A-USDT" : pipelineSymbolB;
                var orders = new ArrayList<PlaceOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) {
                    long id = orderId++;
                    orders.add(new PlaceOrderCommand(id, symbol, CoreOrderSide.BUY, 90, 1,
                            false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC, false, "batch-window-" + id));
                }
                send(command(CoreMessageType.PLACE_ORDER_BATCH, 1_000 + (sharedAccounts ? user & 127 : user),
                        TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(orders))));
            }
            drain();
            for (int user = 0; user < 256; user++) {
                var orders = new ArrayList<CancelOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) orders.add(new CancelOrderCommand(firstOrders[user] + item));
                send(command(CoreMessageType.CANCEL_ORDER_BATCH, 1_000 + (sharedAccounts ? user & 127 : user),
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
                if (interleavedMetrics) metrics();
            }
            drain();
            for (int user = 0; user < 256; user++) {
                var orders = new ArrayList<CancelOrderCommand>(batchSize);
                for (int item = 0; item < batchSize; item++) orders.add(new CancelOrderCommand(firstOrders[user] + item));
                send(command(CoreMessageType.CANCEL_ORDER_BATCH, 1_000 + user,
                        TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(orders))));
                if (interleavedMetrics) metrics();
            }
            drain();
            if (terminal - terminalBefore != 512
                    || queryResults - queriesBefore != (interleavedMetrics ? 512 : 0)) {
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
            service.acceptCommittedCommand(session, com.surprising.aeron.service.orchestration.ingress.CoreMessageFlyweightDecoder.decode(
                            new UnsafeBuffer(bytes), 0, bytes.length),
                    1_700_000_000_000L, header.position());
            maxBacklog = Math.max(maxBacklog, service.state().pendingMatchingCount());
        }

        private void drain() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (service.pollCommands() != 0 || service.pendingCommandCount() != 0) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("service completion timeout");
                Thread.onSpinWait();
            }
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
            return new PlaceOrderCommand(id, "JMH-BTC-USDT", side, price, quantity, false,
                    CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC,
                    false, "cluster-batch-" + id);
        }

        private static boolean nonFlat(com.surprising.aeron.service.state.model.CorePositionState p) {
            return p.signedQuantitySteps()!=0 || p.positionMarginUnits()!=0 || p.entryPriceTicks()!=0
                    || p.entryValueTicks()!=0 || p.realizedPnlUnits()!=0;
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
                try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(productLine, snapshot)) {
                    if (restored.tradingState().businessStateHash() != state.businessStateHash()
                            || !restored.tradingState().clientOrderIndex().equals(state.clientOrderIndex())) {
                        throw new IllegalStateException("snapshot recovery mismatch");
                    }
                }
                log.info("clusterBatch acceptedCore={} terminalCore={} unfinished=0 endBacklog=0 maxBacklog={} queries={}", terminal, terminal, maxBacklog, queryResults);
            } finally {
                service.terminate();
                consuming=false;
                if(realtimeConsumer!=null){try{realtimeConsumer.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                if(realtimeOutbox!=null)log.info("{}", "realtimeDroppedBatches="+realtimeOutbox.droppedBatches());
                service = null;
                if (previousMatchingEngines == null) System.clearProperty("surprising.aeron.matching-engines");
                else System.setProperty("surprising.aeron.matching-engines", previousMatchingEngines);
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
