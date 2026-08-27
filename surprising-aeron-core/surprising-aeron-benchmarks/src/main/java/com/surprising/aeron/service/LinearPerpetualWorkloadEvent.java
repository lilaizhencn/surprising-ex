package com.surprising.aeron.service;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.surprising.LinearPerpetualWorkload")
@Label("Linear perpetual mixed workload")
@Category({"Surprising", "Benchmark"})
final class LinearPerpetualWorkloadEvent extends Event {

    @Label("Active users")
    int activeUsers;

    @Label("Symbols")
    int symbols;

    @Label("HFT rounds")
    int hftRounds;

    @Label("HFT batch size")
    int hftBatchSize;
}
