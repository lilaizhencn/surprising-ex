package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 检查角色切换动作的 JVM 等待配置：默认十分钟、按秒换算，以及上下界和数字格式错误。
 */
class TradingOwnerLoopTimeoutConfigurationTest {

    /**
     * 未配置时要求返回十分钟的纳秒预算。
     */
    @Test
    void roleChangeBoundaryDefaultsToTenMinutes() {
        assertThat(TradingOwnerLoop.configuredRoleChangeDeadlineNanos(null))
                .isEqualTo(TimeUnit.MINUTES.toNanos(10));
    }

    /**
     * 配置 900 秒时要求换算为十五分钟。
     */
    @Test
    void roleChangeBoundaryAcceptsConfiguredTimeoutInSeconds() {
        assertThat(TradingOwnerLoop.configuredRoleChangeDeadlineNanos("900"))
                .isEqualTo(TimeUnit.MINUTES.toNanos(15));
    }

    /**
     * 拒绝小于三十秒、大于三十分钟及非数字配置，保留对应异常类型。
     */
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
