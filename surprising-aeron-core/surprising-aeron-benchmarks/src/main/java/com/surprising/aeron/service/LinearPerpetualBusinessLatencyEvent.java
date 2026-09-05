package com.surprising.aeron.service;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

@Name("com.surprising.LinearPerpetualBusinessLatency")
@Label("Linear perpetual business latency")
@Category({"Surprising", "Benchmark"})
final class LinearPerpetualBusinessLatencyEvent extends Event {
    String businessType;
    String loadModel;
    boolean coordinatedOmissionCorrected;
    int targetOperationsPerSecond;
    int operationsPerInvocation;
    long scheduledBusinessOperations;
    long terminalBusinessOperations;
    long terminalCoreMessages;
    int latencySamples;
    @Timespan(Timespan.NANOSECONDS) long histogramLowestNanos;
    @Timespan(Timespan.NANOSECONDS) long histogramHighestNanos;
    @Timespan(Timespan.NANOSECONDS) long timeoutNanos;
    String latencyUnit;
    String classificationSource;
    String entryAcceptedHistogramCounts;
    String acceptedTerminalHistogramCounts;
    String entryTerminalHistogramCounts;
    @Timespan(Timespan.NANOSECONDS) long entryAcceptedP50Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryAcceptedP90Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryAcceptedP95Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryAcceptedP99Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryAcceptedP999Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryAcceptedMaxNanos;
    @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP50Nanos;
    @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP90Nanos;
    @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP95Nanos;
    @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP99Nanos;
    @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP999Nanos;
    @Timespan(Timespan.NANOSECONDS) long acceptedTerminalMaxNanos;
    @Timespan(Timespan.NANOSECONDS) long entryTerminalP50Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryTerminalP90Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryTerminalP95Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryTerminalP99Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryTerminalP999Nanos;
    @Timespan(Timespan.NANOSECONDS) long entryTerminalMaxNanos;
}
