package com.surprising.aeron.benchmarks.workload;

import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@Slf4j
public final class HttpOpenLoopWorkloadMain {

    private HttpOpenLoopWorkloadMain() {
    }

    public static void main(String[] arguments) {
        Properties properties = new Properties();
        properties.putAll(System.getProperties());
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (separator <= 0 || separator == argument.length() - 1) {
                throw new IllegalArgumentException("arguments must use name=value: " + argument);
            }
            properties.setProperty(argument.substring(0, separator), argument.substring(separator + 1));
        }
        HttpWorkloadConfig config = HttpWorkloadConfig.from(properties);
        boolean p10 = "P10".equalsIgnoreCase(properties.getProperty("qualification", ""));
        if (p10) P10CapacityGate.requireConfiguration(config,
                Boolean.parseBoolean(properties.getProperty("requireJfr", "true")));
        HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(config).run();
        if (p10) P10CapacityGate.requireResult(summary);
        log.info("httpOpenLoop=PASS qualification={} scheduled={} completed={} outstanding={} deliberately_aborted={} maxInFlight={} terminalWithinMeasurement={} terminalRatePerSecond={}", p10 ? "P10" : "NONE",
                summary.scheduled(), summary.completed(), summary.outstanding(), summary.deliberatelyAborted(),
                summary.maxObservedInFlight(), summary.terminalWithinMeasurement(),
                summary.terminalRatePerSecond());
    }
}
