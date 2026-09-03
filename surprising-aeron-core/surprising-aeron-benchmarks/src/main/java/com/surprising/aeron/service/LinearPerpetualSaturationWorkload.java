package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.CoreOrderStatus;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

final class LinearPerpetualSaturationWorkload {

    // Keep saturation pairs on a level that the restored density fixture never uses. Otherwise an IOC can
    // consume a fixture order and leave its paired GTC behind, making the benchmark measure fixture cleanup.
    private static final long PRICE_TICKS = 1_000;
    private static final long PROGRESS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private LinearPerpetualSaturationWorkload() {
    }

    interface SaturationScenario extends LinearPerpetualBenchmarkSupport.Scenario {
        int completedLatencySamples();

        long p50LatencyNanos();

        long p99LatencyNanos();

        long p999LatencyNanos();

        LatencyReport latencyReport();

        double averageMatchingBacklog();

        double fullWindowPercentage();

        long refillOperations();

        long windowSamples();

        long fullWindowSamples();

        long producerStarvationSamples();

        double producerStarvationPercentage();

    }

    record StageLatency(int samples, long rangeLowestNanos, long rangeHighestNanos,
                        long timeoutNanos, String unit, boolean coordinatedOmissionCorrected,
                        String histogramCounts, long p50, long p90, long p95, long p99,
                        long p999, long max) {
    }

    record LatencyReport(String businessType, String loadModel, int targetOperationsPerSecond,
                         StageLatency entryAccepted, StageLatency acceptedTerminal,
                         StageLatency entryTerminal) {
    }

    static SaturationScenario scenario(
            LinearPerpetualMixedWorkload.Template template,
            int maxInFlight,
            int operationsPerRun) {
        return scenario(template, maxInFlight, operationsPerRun, 100_000);
    }

    static SaturationScenario scenario(
            LinearPerpetualMixedWorkload.Template template,
            int maxInFlight,
            int operationsPerRun,
            int targetOperationsPerSecond) {
        if (template == null) throw new IllegalArgumentException("saturation template is required");
        if (maxInFlight < 4 || maxInFlight > 4_096) {
            throw new IllegalArgumentException("maxInFlight must be in [4,4096]");
        }
        int operationsPerDirection = Math.multiplyExact(template.symbols().size(), 2);
        int operationsPerRoundTrip = Math.multiplyExact(operationsPerDirection, 2);
        if (operationsPerRun < maxInFlight || operationsPerRun % operationsPerRoundTrip != 0) {
            throw new IllegalArgumentException(
                    "operationsPerRun must cover the window and contain complete direction round trips");
        }
        if (targetOperationsPerSecond <= 0) {
            throw new IllegalArgumentException("targetOperationsPerSecond must be positive");
        }
        var harness = LinearPerpetualBenchmarkSupport.Harness.restore(template.snapshot());
        int openingActiveOrders = activeOrderCount(harness.state());
        int symbolCount = template.symbols().size();
        int directionCount = operationsPerRun / operationsPerDirection;
        int completionBatchSize = maxInFlight < 64 ? 2 : Math.min(64, maxInFlight / 4);
        return new SaturationScenario() {
            private final long[] entryAcceptedLatencies = new long[operationsPerRun];
            private final long[] acceptedTerminalLatencies = new long[operationsPerRun];
            private final long[] entryTerminalLatencies = new long[operationsPerRun];
            private final LongHashSet usersInFlight = new LongHashSet();
            private final LongLongHashMap userSymbols = userSymbols();
            private final boolean[] pairInFlight = new boolean[symbolCount];
            private final boolean[] symbolQueued = new boolean[symbolCount];
            private final int[] readySymbols = new int[symbolCount];
            private long runSequence;
            private int latencySamples;
            private long acceptedCoreMessages;
            private long terminalCoreMessages;
            private long terminalTrades;
            private long laneOperations;
            private final long[] laneOperationsByType =
                    new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
            private long backlogTotal;
            private long backlogSamples;
            private long fullWindowSamples;
            private long producerStarvationSamples;
            private long refillOperations;
            private int readyHead;
            private int readyTail;
            private int readySize;
            private int scheduledOperations;
            private int currentDirection;
            private int completedPairsInDirection;
            private long firstScheduledEntryNanos;
            private int scheduledEntrySequence;

            @Override
            public long run() {
                long acceptedCoreBefore = harness.acceptedCoreMessages();
                long terminalCoreBefore = harness.terminalCoreMessages();
                long terminalTradesBefore = harness.terminalTradeCount();
                long[] laneOperationsBefore = completedLaneOperations(harness.state());
                latencySamples = 0;
                backlogTotal = 0;
                backlogSamples = 0;
                fullWindowSamples = 0;
                producerStarvationSamples = 0;
                refillOperations = 0;
                firstScheduledEntryNanos = System.nanoTime();
                scheduledEntrySequence = 0;
                prepareRun();
                long lastProgressNanos = System.nanoTime();
                harness.admissionBackpressureDrain(() -> completeReady(harness));
                try {
                    while (latencySamples < operationsPerRun) {
                        int filled = fillWindow(harness);
                        sampleWindow(harness);
                        int completed = completeReady(harness);
                        if (filled != 0 || completed != 0) {
                            lastProgressNanos = System.nanoTime();
                        } else {
                            if (harness.pendingSubmissions() == 0) {
                                throw new IllegalStateException(
                                        "continuous feeder has no runnable or in-flight work");
                            }
                            if (System.nanoTime() - lastProgressNanos >= PROGRESS_TIMEOUT_NANOS) {
                                throw new IllegalStateException(
                                        "continuous feeder made no progress within 30 seconds");
                            }
                            Thread.onSpinWait();
                        }
                    }
                    harness.drainSubmitted();
                } finally {
                    harness.admissionBackpressureDrain(null);
                }
                runSequence = Math.addExact(runSequence, directionCount);
                acceptedCoreMessages = Math.subtractExact(harness.acceptedCoreMessages(), acceptedCoreBefore);
                terminalCoreMessages = Math.subtractExact(harness.terminalCoreMessages(), terminalCoreBefore);
                terminalTrades = Math.subtractExact(harness.terminalTradeCount(), terminalTradesBefore);
                long[] laneOperationsAfter = completedLaneOperations(harness.state());
                laneOperations = 0;
                for (int type = 0; type < laneOperationsByType.length; type++) {
                    laneOperationsByType[type] = Math.subtractExact(
                            laneOperationsAfter[type], laneOperationsBefore[type]);
                    laneOperations = Math.addExact(laneOperations, laneOperationsByType[type]);
                }
                if (latencySamples != operationsPerRun) {
                    throw new IllegalStateException("saturation workload lost completion latency samples");
                }
                return harness.state().snapshotBusinessStateHash();
            }

            private int completeReady(LinearPerpetualBenchmarkSupport.Harness target) {
                return target.awaitReadyMatching(completionBatchSize, this::record);
            }

            private void prepareRun() {
                usersInFlight.clear();
                Arrays.fill(pairInFlight, false);
                Arrays.fill(symbolQueued, false);
                readyHead = 0;
                readyTail = 0;
                readySize = 0;
                scheduledOperations = 0;
                currentDirection = 0;
                completedPairsInDirection = 0;
                for (int symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) enqueue(symbolIndex);
            }

            private int fillWindow(LinearPerpetualBenchmarkSupport.Harness target) {
                int filled = 0;
                while (readySize != 0 && scheduledOperations < operationsPerRun
                        && target.pendingSubmissions() <= maxInFlight - 2) {
                    int symbolIndex = dequeue();
                    long makerId = template.hftMakers().get(symbolIndex);
                    long takerId = template.hftTakers().get(symbolIndex);
                    if (pairInFlight[symbolIndex] || usersInFlight.contains(makerId)
                            || usersInFlight.contains(takerId)) {
                        throw new IllegalStateException("invalid continuous feeder dependency state");
                    }
                    int pendingBefore = target.pendingSubmissions();
                    boolean makerSells = ((runSequence + currentDirection) & 1) == 0;
                    CoreOrderSide makerSide = makerSells ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                    CoreOrderSide takerSide = makerSells ? CoreOrderSide.BUY : CoreOrderSide.SELL;
                    usersInFlight.add(makerId);
                    usersInFlight.add(takerId);
                    pairInFlight[symbolIndex] = true;
                    String symbol = template.symbols().get(symbolIndex);
                    submit(target, makerId, symbol, makerSide, CoreTimeInForce.GTC);
                    submit(target, takerId, symbol, takerSide, CoreTimeInForce.IOC);
                    scheduledOperations += 2;
                    filled += 2;
                    if (pendingBefore != 0) refillOperations += 2;
                }
                return filled;
            }

            private void submit(LinearPerpetualBenchmarkSupport.Harness target, long userId,
                                String symbol, CoreOrderSide side, CoreTimeInForce timeInForce) {
                long orderId = target.nextOrderId();
                var order = new PlaceOrderCommand(orderId, symbol, 1, side, PRICE_TICKS, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        timeInForce, false, "saturation-" + orderId);
                long scheduledEntryNanos = Math.addExact(firstScheduledEntryNanos,
                        Math.multiplyExact(scheduledEntrySequence++,
                                TimeUnit.SECONDS.toNanos(1) / targetOperationsPerSecond));
                target.submitScheduled(target.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                        userId, TradingCommandCodec.encodePlaceOrder(order)), scheduledEntryNanos);
            }

            private void sampleWindow(LinearPerpetualBenchmarkSupport.Harness target) {
                int backlog = target.pendingSubmissions();
                backlogTotal = Math.addExact(backlogTotal, backlog);
                backlogSamples = Math.incrementExact(backlogSamples);
                if (backlog >= maxInFlight - 1) {
                    fullWindowSamples = Math.incrementExact(fullWindowSamples);
                }
                if (scheduledOperations < operationsPerRun && backlog == 0) {
                    producerStarvationSamples = Math.incrementExact(producerStarvationSamples);
                }
            }

            private void record(long userId, long entryNanos, long acceptedNanos, long terminalNanos) {
                if (!usersInFlight.remove(userId)) {
                    throw new IllegalStateException("matching completion user is not in flight");
                }
                if (entryNanos <= 0 || acceptedNanos < entryNanos || terminalNanos < acceptedNanos
                        || latencySamples >= entryTerminalLatencies.length) {
                    throw new IllegalStateException("invalid saturation completion latency");
                }
                entryAcceptedLatencies[latencySamples] = acceptedNanos - entryNanos;
                acceptedTerminalLatencies[latencySamples] = terminalNanos - acceptedNanos;
                entryTerminalLatencies[latencySamples] = terminalNanos - entryNanos;
                latencySamples++;
                long mapped = userSymbols.get(userId);
                if (mapped == 0) throw new IllegalStateException("matching completion user is unknown");
                int symbolIndex = Math.toIntExact(mapped - 1);
                long makerId = template.hftMakers().get(symbolIndex);
                long takerId = template.hftTakers().get(symbolIndex);
                if (pairInFlight[symbolIndex] && !usersInFlight.contains(makerId)
                        && !usersInFlight.contains(takerId)) {
                    pairInFlight[symbolIndex] = false;
                    completedPairsInDirection++;
                    if (completedPairsInDirection == symbolCount
                            && scheduledOperations < operationsPerRun) {
                        currentDirection++;
                        completedPairsInDirection = 0;
                        for (int index = 0; index < symbolCount; index++) enqueue(index);
                    }
                }
            }

            private LongLongHashMap userSymbols() {
                LongLongHashMap values = new LongLongHashMap(Math.multiplyExact(symbolCount, 2));
                for (int symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) {
                    putUserSymbol(values, template.hftMakers().get(symbolIndex), symbolIndex);
                    putUserSymbol(values, template.hftTakers().get(symbolIndex), symbolIndex);
                }
                return values;
            }

            private void putUserSymbol(LongLongHashMap values, long userId, int symbolIndex) {
                if (values.containsKey(userId)) {
                    throw new IllegalArgumentException("saturation users must be unique per symbol");
                }
                values.put(userId, symbolIndex + 1L);
            }

            private void enqueue(int symbolIndex) {
                if (symbolQueued[symbolIndex] || readySize == readySymbols.length) {
                    throw new IllegalStateException("continuous feeder ready queue is invalid");
                }
                readySymbols[readyTail] = symbolIndex;
                readyTail = (readyTail + 1) % readySymbols.length;
                readySize++;
                symbolQueued[symbolIndex] = true;
            }

            private int dequeue() {
                if (readySize == 0) throw new IllegalStateException("continuous feeder ready queue is empty");
                int symbolIndex = readySymbols[readyHead];
                readyHead = (readyHead + 1) % readySymbols.length;
                readySize--;
                symbolQueued[symbolIndex] = false;
                return symbolIndex;
            }

            @Override
            public long operations() {
                return operationsPerRun;
            }

            @Override
            public long acceptedOperations() {
                return operationsPerRun;
            }

            @Override
            public long terminalOperations() {
                return latencySamples;
            }

            @Override
            public long acceptedCoreMessages() {
                return acceptedCoreMessages;
            }

            @Override
            public long terminalCoreMessages() {
                return terminalCoreMessages;
            }

            @Override
            public long maxBacklog() {
                return harness.maxMatchingBacklog();
            }

            @Override
            public long terminalTrades() {
                return terminalTrades;
            }

            @Override
            public long laneOperations() {
                return laneOperations;
            }

            @Override
            public long laneOperations(int operationType) {
                return laneOperationsByType[operationType];
            }

            @Override
            public int completedLatencySamples() {
                return latencySamples;
            }

            @Override
            public long p50LatencyNanos() {
                return percentile(entryTerminalLatencies, 0.50);
            }

            @Override
            public long p99LatencyNanos() {
                return percentile(entryTerminalLatencies, 0.99);
            }

            @Override
            public long p999LatencyNanos() {
                return percentile(entryTerminalLatencies, 0.999);
            }

            @Override
            public LatencyReport latencyReport() {
                return new LatencyReport("PLACE_ORDER", "OPEN_LOOP_CONSTANT_ARRIVAL",
                        targetOperationsPerSecond, stage(entryAcceptedLatencies),
                        stage(acceptedTerminalLatencies), stage(entryTerminalLatencies));
            }

            @Override
            public double averageMatchingBacklog() {
                return backlogSamples == 0 ? 0 : (double) backlogTotal / backlogSamples;
            }

            @Override
            public double fullWindowPercentage() {
                return backlogSamples == 0 ? 0 : 100.0 * fullWindowSamples / backlogSamples;
            }

            @Override
            public long refillOperations() {
                return refillOperations;
            }

            @Override
            public long windowSamples() {
                return backlogSamples;
            }

            @Override
            public long fullWindowSamples() {
                return fullWindowSamples;
            }

            @Override
            public long producerStarvationSamples() {
                return producerStarvationSamples;
            }

            @Override
            public double producerStarvationPercentage() {
                return backlogSamples == 0 ? 0 : 100.0 * producerStarvationSamples / backlogSamples;
            }

            private StageLatency stage(long[] values) {
                return new StageLatency(latencySamples, 1, PROGRESS_TIMEOUT_NANOS,
                        PROGRESS_TIMEOUT_NANOS, "NANOSECONDS", true, histogram(values),
                        percentile(values, 0.50), percentile(values, 0.90),
                        percentile(values, 0.95), percentile(values, 0.99),
                        percentile(values, 0.999), percentile(values, 1.0));
            }

            private String histogram(long[] values) {
                long[] buckets = new long[64];
                for (int index = 0; index < latencySamples; index++) {
                    long value = Math.max(1, values[index]);
                    int bucket = 64 - Long.numberOfLeadingZeros(value - 1);
                    buckets[Math.min(bucket, buckets.length - 1)]++;
                }
                StringBuilder encoded = new StringBuilder(128);
                for (int index = 0; index < buckets.length; index++) {
                    if (index != 0) encoded.append(',');
                    encoded.append(buckets[index]);
                }
                return encoded.toString();
            }

            private long percentile(long[] values, double fraction) {
                if (latencySamples == 0) return 0;
                long[] sorted = Arrays.copyOf(values, latencySamples);
                Arrays.sort(sorted);
                int index = (int) Math.ceil(sorted.length * fraction) - 1;
                return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
            }

            @Override
            public void verify() {
                long closingFunds = LinearPerpetualMixedWorkload.totalFunds(harness.state().tradingState());
                int closingActiveOrders = activeOrderCount(harness.state());
                int parallelSettlementLanes = 0;
                CoreLaneMetrics laneMetrics = harness.state().laneMetrics();
                for (int highWaterMark : laneMetrics.accountLaneQueueHighWaterMarks()) {
                    if (highWaterMark > 0) parallelSettlementLanes++;
                }
                long rejectedLaneSubmissions = 0;
                for (long rejected : laneMetrics.accountLaneRejectedSubmissions()) {
                    rejectedLaneSubmissions = Math.addExact(rejectedLaneSubmissions, rejected);
                }
                int queuedLaneOperations = 0;
                for (int depth : laneMetrics.accountLaneQueueDepths()) {
                    queuedLaneOperations = Math.addExact(queuedLaneOperations, depth);
                }
                boolean incompletePair = false;
                for (boolean inFlight : pairInFlight) incompletePair |= inFlight;
                int settlementInFlightHighWaterMark = harness.dispatchedSettlementHighWaterMark();
                if (latencySamples != operationsPerRun
                        || scheduledOperations != operationsPerRun
                        || scheduledEntrySequence != operationsPerRun
                        || acceptedCoreMessages != terminalCoreMessages
                        || terminalTrades != operationsPerRun / 2L
                        || laneOperationsByType[0] != operationsPerRun
                        || laneOperationsByType[1] <= operationsPerRun
                        || laneOperationsByType[1] > Math.multiplyExact(operationsPerRun, 2L)
                        || laneOperations != Math.addExact(laneOperationsByType[0], laneOperationsByType[1])
                        || laneOperations <= Math.multiplyExact(operationsPerRun, 2L)
                        || laneOperations > Math.multiplyExact(operationsPerRun, 3L)
                        || parallelSettlementLanes < 2
                        || maxInFlight > 1 && settlementInFlightHighWaterMark < 2
                        || rejectedLaneSubmissions != 0
                        || queuedLaneOperations != 0
                        || backlogSamples == 0
                        || !usersInFlight.isEmpty()
                        || incompletePair
                        || readySize != 0
                        || currentDirection != directionCount - 1
                        || completedPairsInDirection != symbolCount
                        || harness.pendingSubmissions() != 0
                        || closingFunds != template.openingFunds()
                        || closingActiveOrders != openingActiveOrders) {
                    throw new IllegalStateException("saturation workload invariant failed: samples="
                            + latencySamples + '/' + operationsPerRun
                            + ", scheduled=" + scheduledOperations + '/' + operationsPerRun
                            + ", scheduledEntries=" + scheduledEntrySequence + '/' + operationsPerRun
                            + ", coreMessages=" + acceptedCoreMessages + '/' + terminalCoreMessages
                            + ", terminalTrades=" + terminalTrades + '/' + (operationsPerRun / 2L)
                            + ", laneOperations=" + laneOperations
                            + ", laneAdmissions=" + laneOperationsByType[0]
                            + ", laneSettlements=" + laneOperationsByType[1]
                            + ", rejectedLaneSubmissions=" + rejectedLaneSubmissions
                            + ", queuedLaneOperations=" + queuedLaneOperations
                            + ", parallelSettlementLanes=" + parallelSettlementLanes
                            + ", settlementInFlightHighWaterMark=" + settlementInFlightHighWaterMark
                            + ", backlogSamples=" + backlogSamples
                            + ", usersInFlight=" + usersInFlight.size()
                            + ", incompletePair=" + incompletePair
                            + ", ready=" + readySize
                            + ", direction=" + currentDirection + '/' + (directionCount - 1)
                            + ", completedPairs=" + completedPairsInDirection + '/' + symbolCount
                            + ", pending=" + harness.pendingSubmissions()
                            + ", funds=" + template.openingFunds() + '/' + closingFunds
                            + ", activeOrders=" + openingActiveOrders + '/' + closingActiveOrders);
                }
            }

            @Override
            public void close() {
                harness.close();
            }
        };
    }

    private static long[] completedLaneOperations(CoreProbeState state) {
        long[] total = new long[CoreLaneMetrics.OPERATION_TYPE_COUNT];
        long[] completed = state.laneMetrics().accountLaneCompletedOperations();
        for (int index = 0; index < completed.length; index++) {
            int type = index % CoreLaneMetrics.OPERATION_TYPE_COUNT;
            total[type] = Math.addExact(total[type], completed[index]);
        }
        return total;
    }

    private static int activeOrderCount(CoreProbeState state) {
        return (int) state.tradingState().orders().values().stream()
                .filter(order -> order.status() == CoreOrderStatus.OPEN)
                .count();
    }
}
