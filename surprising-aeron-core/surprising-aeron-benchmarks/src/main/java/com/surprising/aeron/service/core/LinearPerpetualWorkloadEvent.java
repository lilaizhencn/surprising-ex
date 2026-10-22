package com.surprising.aeron.service.core;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

public @Name("com.surprising.LinearPerpetualWorkload")
@Label("Linear perpetual mixed workload")
@Category({"Surprising", "Benchmark"})
final class LinearPerpetualWorkloadEvent extends Event {

    public @Label("Active users")
    int activeUsers;

    public @Label("Symbols")
    int symbols;

    public @Label("Listed symbols")
    int listedSymbols;

    public @Label("Maximum positions per user")
    int maxPositionsPerUser;

    public @Label("Maximum open orders per user")
    int maxOpenOrdersPerUser;

    public @Label("Traffic profile")
    String trafficProfile;

    public @Label("HFT rounds")
    int hftRounds;

    public @Label("HFT batch size")
    int hftBatchSize;

    public @Label("Lifecycle symbols per run")
    int lifecycleSymbolsPerRun;

    public @Label("Accepted business operations")
    long acceptedBusinessOperations;

    public @Label("Terminal business operations")
    long terminalBusinessOperations;

    public @Label("Accepted Core messages")
    long acceptedCoreMessages;

    public @Label("Terminal Core messages")
    long terminalCoreMessages;

    public @Label("Maximum matching backlog")
    long maxMatchingBacklog;
}
