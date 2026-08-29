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

    @Label("Completion mailbox high-water mark")
    int completionMailboxHighWaterMark;

    @Label("Completion mailbox capacity")
    int completionMailboxCapacity;

    @Label("Completion p50")
    @Timespan(Timespan.NANOSECONDS)
    long p50LatencyNanos;

    @Label("Completion p99")
    @Timespan(Timespan.NANOSECONDS)
    long p99LatencyNanos;

    @Label("Completion p99.9")
    @Timespan(Timespan.NANOSECONDS)
    long p999LatencyNanos;
}
