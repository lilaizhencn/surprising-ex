package com.surprising.aeron.benchmarks.workload;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

public @Name("com.surprising.HttpWorkloadMeasurement")
@Label("HTTP workload measured interval")
@Category({"Surprising", "Capacity"})
final class HttpWorkloadMeasurementEvent extends Event {

    public @Label("Run ID")
    String runId;

    public @Label("Offered operations per second")
    long offeredRatePerSecond;

    public @Label("Scheduled operations")
    long scheduled;

    public @Label("Completed operations")
    long completed;
}
