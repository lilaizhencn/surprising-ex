package com.surprising.aeron.service.orchestration;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

public @Name("com.surprising.LinearPerpetualBusinessLatency")
@Label("Linear perpetual business latency")
@Category({"Surprising", "Benchmark"})
final class LinearPerpetualBusinessLatencyEvent extends Event {
    public String businessType;
    public String loadModel;
    public boolean coordinatedOmissionCorrected;
    public int targetOperationsPerSecond;
    public int operationsPerInvocation;
    public long scheduledBusinessOperations;
    public long terminalBusinessOperations;
    public long terminalCoreMessages;
    public int latencySamples;
    public @Timespan(Timespan.NANOSECONDS) long histogramLowestNanos;
    public @Timespan(Timespan.NANOSECONDS) long histogramHighestNanos;
    public @Timespan(Timespan.NANOSECONDS) long timeoutNanos;
    public String latencyUnit;
    public String classificationSource;
    public String entryAcceptedHistogramCounts;
    public String acceptedTerminalHistogramCounts;
    public String entryTerminalHistogramCounts;
    public @Timespan(Timespan.NANOSECONDS) long entryAcceptedP50Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryAcceptedP90Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryAcceptedP95Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryAcceptedP99Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryAcceptedP999Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryAcceptedMaxNanos;
    public @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP50Nanos;
    public @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP90Nanos;
    public @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP95Nanos;
    public @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP99Nanos;
    public @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP999Nanos;
    public @Timespan(Timespan.NANOSECONDS) long acceptedTerminalMaxNanos;
    public @Timespan(Timespan.NANOSECONDS) long entryTerminalP50Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryTerminalP90Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryTerminalP95Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryTerminalP99Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryTerminalP999Nanos;
    public @Timespan(Timespan.NANOSECONDS) long entryTerminalMaxNanos;
}
