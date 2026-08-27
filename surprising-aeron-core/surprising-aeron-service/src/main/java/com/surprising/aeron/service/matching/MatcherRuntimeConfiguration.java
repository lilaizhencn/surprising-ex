package com.surprising.aeron.service.matching;

import exchange.core2.core.common.CoreWaitStrategy;
import java.util.Locale;

final class MatcherRuntimeConfiguration {

    static final String WAIT_STRATEGY_PROPERTY = "surprising.aeron.matcher-wait-strategy";

    private MatcherRuntimeConfiguration() {
    }

    static CoreWaitStrategy waitStrategy() {
        return waitStrategy(System.getProperty(WAIT_STRATEGY_PROPERTY, CoreWaitStrategy.BUSY_SPIN.name()));
    }

    static CoreWaitStrategy waitStrategy(String value) {
        CoreWaitStrategy strategy;
        try {
            strategy = CoreWaitStrategy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "matcher wait strategy must be BUSY_SPIN, YIELDING or BLOCKING", exception);
        }
        if (strategy == CoreWaitStrategy.SECOND_STEP_NO_WAIT) {
            throw new IllegalArgumentException(
                    "matcher wait strategy must be BUSY_SPIN, YIELDING or BLOCKING");
        }
        return strategy;
    }
}
