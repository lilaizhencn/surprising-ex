package com.surprising.aeron.service;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

@Name("com.surprising.LinearPerpetualSaturation")
@Label("Linear perpetual sustained saturation")
@Category({"Surprising", "Benchmark"})
final class LinearPerpetualSaturationEvent extends Event {

    @Label("Active users")
    int activeUsers;

    @Label("Active symbols")
    int activeSymbols;

    @Label("Maximum in-flight commands")
    int maxInFlight;

    @Label("Operations per invocation")
    int operationsPerInvocation;

    @Label("Matcher wait strategy")
    String matcherWaitStrategy;

    @Label("Scheduled business operations") long scheduledBusinessOperations;

    @Label("Business type") String businessType;
    @Label("Load model") String loadModel;
    @Label("Target operations per second") int targetOperationsPerSecond;
    @Label("Coordinated omission corrected") boolean coordinatedOmissionCorrected;
    @Label("Latency samples") int latencySamples;
    @Label("Histogram lowest") @Timespan(Timespan.NANOSECONDS) long histogramLowestNanos;
    @Label("Histogram highest") @Timespan(Timespan.NANOSECONDS) long histogramHighestNanos;
    @Label("Timeout") @Timespan(Timespan.NANOSECONDS) long timeoutNanos;
    @Label("Latency unit") String latencyUnit;
    @Label("Classification source") String classificationSource;
    @Label("Entry-accepted histogram counts") String entryAcceptedHistogramCounts;
    @Label("Accepted-terminal histogram counts") String acceptedTerminalHistogramCounts;
    @Label("Entry-terminal histogram counts") String entryTerminalHistogramCounts;

    @Label("Terminal business operations")
    long terminalBusinessOperations;

    @Label("Terminal Core messages")
    long terminalCoreMessages;

    @Label("Maximum matching backlog")
    long maxMatchingBacklog;

    @Label("Average matching backlog")
    double averageMatchingBacklog;

    @Label("Full window percentage")
    double fullWindowPercentage;

    @Label("Window samples")
    long windowSamples;

    @Label("Full window samples")
    long fullWindowSamples;

    @Label("Sliding refill operations")
    long refillOperations;

    @Label("Producer starvation samples")
    long producerStarvationSamples;

    @Label("Producer starvation percentage")
    double producerStarvationPercentage;

    @Label("Completion mailbox high-water mark")
    int completionMailboxHighWaterMark;

    @Label("Completion mailbox capacity")
    int completionMailboxCapacity;

    @Label("Completion batch count") long completionBatchCount;
    @Label("Completion batch items") long completionBatchItems;
    @Label("Maximum completion batch size") int maximumCompletionBatchSize;
    @Label("Average completion batch size") double averageCompletionBatchSize;

    @Label("Completion p50")
    @Timespan(Timespan.NANOSECONDS)
    long p50LatencyNanos;

    @Label("Completion p99")
    @Timespan(Timespan.NANOSECONDS)
    long p99LatencyNanos;

    @Label("Completion p99.9")
    @Timespan(Timespan.NANOSECONDS)
    long p999LatencyNanos;

    @Label("Entry to accepted p50") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP50Nanos;
    @Label("Entry to accepted p90") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP90Nanos;
    @Label("Entry to accepted p95") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP95Nanos;
    @Label("Entry to accepted p99") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP99Nanos;
    @Label("Entry to accepted p99.9") @Timespan(Timespan.NANOSECONDS) long entryAcceptedP999Nanos;
    @Label("Entry to accepted max") @Timespan(Timespan.NANOSECONDS) long entryAcceptedMaxNanos;
    @Label("Accepted to terminal p50") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP50Nanos;
    @Label("Accepted to terminal p90") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP90Nanos;
    @Label("Accepted to terminal p95") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP95Nanos;
    @Label("Accepted to terminal p99") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP99Nanos;
    @Label("Accepted to terminal p99.9") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalP999Nanos;
    @Label("Accepted to terminal max") @Timespan(Timespan.NANOSECONDS) long acceptedTerminalMaxNanos;
    @Label("Entry to terminal p50") @Timespan(Timespan.NANOSECONDS) long entryTerminalP50Nanos;
    @Label("Entry to terminal p90") @Timespan(Timespan.NANOSECONDS) long entryTerminalP90Nanos;
    @Label("Entry to terminal p95") @Timespan(Timespan.NANOSECONDS) long entryTerminalP95Nanos;
    @Label("Entry to terminal p99") @Timespan(Timespan.NANOSECONDS) long entryTerminalP99Nanos;
    @Label("Entry to terminal p99.9") @Timespan(Timespan.NANOSECONDS) long entryTerminalP999Nanos;
    @Label("Entry to terminal max") @Timespan(Timespan.NANOSECONDS) long entryTerminalMaxNanos;
}
