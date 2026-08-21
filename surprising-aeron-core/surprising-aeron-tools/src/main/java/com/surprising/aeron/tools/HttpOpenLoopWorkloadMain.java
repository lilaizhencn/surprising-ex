package com.surprising.aeron.tools;

import java.util.Properties;

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
        HttpOpenLoopWorkload.Summary summary = new HttpOpenLoopWorkload(HttpWorkloadConfig.from(properties)).run();
        System.out.printf("httpOpenLoop=PASS scheduled=%d completed=%d outstanding=%d deliberately_aborted=%d maxInFlight=%d%n",
                summary.scheduled(), summary.completed(), summary.outstanding(), summary.deliberatelyAborted(),
                summary.maxObservedInFlight());
    }
}
