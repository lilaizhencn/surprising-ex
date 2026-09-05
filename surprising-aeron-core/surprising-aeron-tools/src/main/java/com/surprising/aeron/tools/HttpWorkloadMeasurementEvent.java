package com.surprising.aeron.tools;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name("com.surprising.HttpWorkloadMeasurement")
@Label("HTTP workload measured interval")
@Category({"Surprising", "Capacity"})
final class HttpWorkloadMeasurementEvent extends Event {

    @Label("Run ID")
    String runId;

    @Label("Offered operations per second")
    long offeredRatePerSecond;

    @Label("Scheduled operations")
    long scheduled;

    @Label("Completed operations")
    long completed;
}
