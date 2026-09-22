package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TradingOrderPropertiesTest {

    @Test
    void eventPublishTimeoutMustBePositive() {
        TradingOrderProperties.EventPublish eventPublish = new TradingOrderProperties.EventPublish();

        assertThatThrownBy(() -> eventPublish.setSendTimeout(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sendTimeout");
        assertThatThrownBy(() -> eventPublish.setSendTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sendTimeout");
        assertThatThrownBy(() -> eventPublish.setSendTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sendTimeout");

        eventPublish.setSendTimeout(Duration.ofSeconds(1));
        assertThat(eventPublish.getSendTimeout()).isEqualTo(Duration.ofSeconds(1));
    }
}
