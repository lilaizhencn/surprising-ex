package com.surprising.aeron.service.core;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

public @Name("com.surprising.LinearPerpetualSaturation")
@Label("Linear perpetual sustained saturation")
@Category({"Surprising", "Benchmark"})
final class LinearPerpetualSaturationEvent extends Event {

    public @Label("Active users")
    int activeUsers;

    public @Label("Active symbols")
    int activeSymbols;

    public @Label("Maximum in-flight commands")
    int maxInFlight;

    public @Label("Operations per invocation")
    int operationsPerInvocation;

    public @Label("Scheduled business operations") long scheduledBusinessOperations;

    public @Label("Business type") String businessType;
    public @Label("Load model") String loadModel;
    public @Label("Target operations per second") int targetOperationsPerSecond;
    public @Label("Coordinated omission corrected") boolean coordinatedOmissionCorrected;
    public @Label("Latency samples") int latencySamples;
    public @Label("Histogram lowest") @Timespan(Timespan.NANOSECONDS) long histogramLowestNanos;
    public @Label("Histogram highest") @Timespan(Timespan.NANOSECONDS) long histogramHighestNanos;
    public @Label("Timeout") @Timespan(Timespan.NANOSECONDS) long timeoutNanos;
    public @Label("Latency unit") String latencyUnit;
    public @Label("Classification source") String classificationSource;
    public @Label("Entry-accepted histogram counts") String entryAcceptedHistogramCounts;
    public @Label("Accepted-terminal histogram counts") String acceptedTerminalHistogramCounts;
    public @Label("Entry-terminal histogram counts") String entryTerminalHistogramCounts;

    public @Label("Terminal business operations")
    long terminalBusinessOperations;

    public @Label("Terminal Core messages")
    long terminalCoreMessages;

    public @Label("Maximum matching backlog")
    long maxMatchingBacklog;

    public @Label("Average matching backlog")
    double averageMatchingBacklog;

    public @Label("Full window percentage")
    double fullWindowPercentage;

    public @Label("Window samples")
    long windowSamples;

    public @Label("Full window samples")
    long fullWindowSamples;

    public @Label("Sliding refill operations")
    long refillOperations;

    public @Label("Producer starvation samples")
    long producerStarvationSamples;

    public @Label("Producer starvation percentage")
    double producerStarvationPercentage;

    public @Label("Completion p50")
    @Timespan(Timespan.NANOSECONDS)
    long p50LatencyNanos;

    public @Label("Completion p99")
    @Timespan(Timespan.NANOSECONDS)
    long p99LatencyNanos;

    public @Label("Completion p99.9")
    @Timespan(Timespan.NANOSECONDS)
    long p999LatencyNanos;

    public @Label("Entry to accepted p50") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP50Nanos;
    public @Label("Entry to accepted p90") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP90Nanos;
    public @Label("Entry to accepted p95") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP95Nanos;
    public @Label("Entry to accepted p99") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP99Nanos;
    public @Label("Entry to accepted p99.9") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP999Nanos;
    public @Label("Entry to accepted max") @Timespan(Timespan.NANOSECONDS) long entryAcceptedMaxNanos;
    public @Label("Accepted to terminal p50") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP50Nanos;
    public @Label("Accepted to terminal p90") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP90Nanos;
    public @Label("Accepted to terminal p95") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP95Nanos;
    public @Label("Accepted to terminal p99") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP99Nanos;
    public @Label("Accepted to terminal p99.9") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP999Nanos;
    public @Label("Accepted to terminal max") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalMaxNanos;
    public @Label("Entry to terminal p50") @Timespan(Timespan.NANOSECONDS) long entryTerminalP50Nanos;
    public @Label("Entry to terminal p90") @Timespan(Timespan.NANOSECONDS) long entryTerminalP90Nanos;
    public @Label("Entry to terminal p95") @Timespan(Timespan.NANOSECONDS) long entryTerminalP95Nanos;
    public @Label("Entry to terminal p99") @Timespan(Timespan.NANOSECONDS) long entryTerminalP99Nanos;
    public @Label("Entry to terminal p99.9") @Timespan(Timespan.NANOSECONDS) long entryTerminalP999Nanos;
    public @Label("Entry to terminal max") @Timespan(Timespan.NANOSECONDS) long entryTerminalMaxNanos;
}
