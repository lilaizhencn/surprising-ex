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
import java.util.Arrays;

final class LinearPerpetualSaturationWorkload {

    private static final long PRICE_TICKS = 100;

    private LinearPerpetualSaturationWorkload() {
    }

    interface SaturationScenario extends LinearPerpetualBenchmarkSupport.Scenario {
        int completedLatencySamples();

        long p50LatencyNanos();

        long p99LatencyNanos();

        long p999LatencyNanos();

        double averageMatchingBacklog();

        double fullWindowPercentage();
    }

    static SaturationScenario scenario(
            LinearPerpetualMixedWorkload.Template template,
            int maxInFlight,
            int operationsPerRun) {
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
        var harness = LinearPerpetualBenchmarkSupport.Harness.restore(template.snapshot());
        int openingActiveOrders = harness.state().tradingState().orders().size();
        return new SaturationScenario() {
            private final long[] completionLatencies = new long[operationsPerRun];
            private long runSequence;
            private int latencySamples;
            private long acceptedCoreMessages;
            private long terminalCoreMessages;
            private long backlogTotal;
            private long backlogSamples;
            private long fullWindowSamples;

            @Override
            public long run() {
                long acceptedCoreBefore = harness.acceptedCoreMessages();
                long terminalCoreBefore = harness.terminalCoreMessages();
                latencySamples = 0;
                backlogTotal = 0;
                backlogSamples = 0;
                fullWindowSamples = 0;
                int symbolCount = template.symbols().size();
                int directionCount = operationsPerRun / operationsPerDirection;
                for (int direction = 0; direction < directionCount; direction++) {
                    CoreOrderSide makerSide = ((runSequence + direction) & 1) == 0
                            ? CoreOrderSide.SELL : CoreOrderSide.BUY;
                    CoreOrderSide takerSide = makerSide == CoreOrderSide.SELL
                            ? CoreOrderSide.BUY : CoreOrderSide.SELL;
                    for (int symbolIndex = 0; symbolIndex < symbolCount; symbolIndex++) {
                        String symbol = template.symbols().get(symbolIndex);
                        submit(harness, maxInFlight, template.hftMakers().get(symbolIndex), symbol,
                                makerSide, CoreTimeInForce.GTC);
                        submit(harness, maxInFlight, template.hftTakers().get(symbolIndex), symbol,
                                takerSide, CoreTimeInForce.IOC);
                    }
                    drainFence(harness);
                }
                runSequence = Math.addExact(runSequence, directionCount);
                acceptedCoreMessages = Math.subtractExact(harness.acceptedCoreMessages(), acceptedCoreBefore);
                terminalCoreMessages = Math.subtractExact(harness.terminalCoreMessages(), terminalCoreBefore);
                if (latencySamples != operationsPerRun) {
                    throw new IllegalStateException("saturation workload lost completion latency samples");
                }
                return harness.state().tradingState().businessStateHash();
            }

            private void submit(LinearPerpetualBenchmarkSupport.Harness target, int window, long userId,
                                String symbol, CoreOrderSide side, CoreTimeInForce timeInForce) {
                long orderId = target.nextOrderId();
                var order = new PlaceOrderCommand(orderId, symbol, 1, side, PRICE_TICKS, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        timeInForce, false, "saturation-" + orderId);
                target.submitTimed(target.command(CoreMessageType.PLACE_ORDER, CommandSource.GATEWAY,
                        userId, TradingCommandCodec.encodePlaceOrder(order)));
                int backlog = target.pendingSubmissions();
                backlogTotal = Math.addExact(backlogTotal, backlog);
                backlogSamples = Math.incrementExact(backlogSamples);
                if (backlog >= window) {
                    fullWindowSamples = Math.incrementExact(fullWindowSamples);
                    int batchSize = window < 64 ? 1 : Math.min(64, Math.max(1, window / 4));
                    int completed = target.drainReadyMatching(batchSize, this::record);
                    if (completed == 0) throw new IllegalStateException("saturated matching batch made no progress");
                }
            }

            private void drainFence(LinearPerpetualBenchmarkSupport.Harness target) {
                while (target.pendingSubmissions() != 0) {
                    int completed = target.drainReadyMatching(64, this::record);
                    if (completed == 0) throw new IllegalStateException("matching fence batch made no progress");
                }
                target.drainSubmitted();
            }

            private void record(long latencyNanos) {
                if (latencyNanos <= 0 || latencySamples >= completionLatencies.length) {
                    throw new IllegalStateException("invalid saturation completion latency");
                }
                completionLatencies[latencySamples++] = latencyNanos;
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
            public int completedLatencySamples() {
                return latencySamples;
            }

            @Override
            public long p50LatencyNanos() {
                return percentile(0.50);
            }

            @Override
            public long p99LatencyNanos() {
                return percentile(0.99);
            }

            @Override
            public long p999LatencyNanos() {
                return percentile(0.999);
            }

            @Override
            public double averageMatchingBacklog() {
                return backlogSamples == 0 ? 0 : (double) backlogTotal / backlogSamples;
            }

            @Override
            public double fullWindowPercentage() {
                return backlogSamples == 0 ? 0 : 100.0 * fullWindowSamples / backlogSamples;
            }

            private long percentile(double fraction) {
                if (latencySamples == 0) return 0;
                long[] sorted = Arrays.copyOf(completionLatencies, latencySamples);
                Arrays.sort(sorted);
                int index = (int) Math.ceil(sorted.length * fraction) - 1;
                return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
            }

            @Override
            public void verify() {
                long closingFunds = LinearPerpetualMixedWorkload.totalFunds(harness.state().tradingState());
                int closingActiveOrders = harness.state().tradingState().orders().size();
                if (latencySamples != operationsPerRun
                        || acceptedCoreMessages != terminalCoreMessages
                        || backlogSamples != operationsPerRun
                        || harness.pendingSubmissions() != 0
                        || closingFunds != template.openingFunds()
                        || closingActiveOrders != openingActiveOrders) {
                    throw new IllegalStateException("saturation workload invariant failed: samples="
                            + latencySamples + '/' + operationsPerRun
                            + ", coreMessages=" + acceptedCoreMessages + '/' + terminalCoreMessages
                            + ", backlogSamples=" + backlogSamples + '/' + operationsPerRun
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
}
