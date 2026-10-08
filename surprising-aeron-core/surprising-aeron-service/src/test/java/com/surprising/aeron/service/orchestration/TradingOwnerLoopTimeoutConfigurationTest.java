package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TradingOwnerLoopTimeoutConfigurationTest {

    @Test
    void roleChangeBoundaryDefaultsToTenMinutes() {
        assertThat(TradingOwnerLoop.configuredRoleChangeDeadlineNanos(null))
                .isEqualTo(TimeUnit.MINUTES.toNanos(10));
    }

    @Test
    void roleChangeBoundaryAcceptsConfiguredTimeoutInSeconds() {
        assertThat(TradingOwnerLoop.configuredRoleChangeDeadlineNanos("900"))
                .isEqualTo(TimeUnit.MINUTES.toNanos(15));
    }

    @Test
    void roleChangeBoundaryRejectsTimeoutOutsideSafeBounds() {
        assertThatThrownBy(() -> TradingOwnerLoop.configuredRoleChangeDeadlineNanos("29"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradingOwnerLoop.configuredRoleChangeDeadlineNanos("1801"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TradingOwnerLoop.configuredRoleChangeDeadlineNanos("not-a-number"))
                .isInstanceOf(NumberFormatException.class);
    }
}
