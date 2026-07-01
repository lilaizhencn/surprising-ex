package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.trigger.model.MarkTrigger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarkPriceTriggerParserTest {

    @Test
    void parsesMarkPriceEnvelopeWithoutPriceApiModel() {
        MarkPriceTriggerParser parser = new MarkPriceTriggerParser(new ObjectMapper());

        MarkTrigger trigger = parser.parse("""
                {"symbol":"btc-usdt","sequence":42,"markPrice":"65000.1","eventTime":"2026-07-01T00:00:00Z"}
                """);

        assertThat(trigger.symbol()).isEqualTo("BTC-USDT");
        assertThat(trigger.sequence()).isEqualTo(42L);
        assertThat(trigger.eventTime()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void rejectsMissingSequence() {
        MarkPriceTriggerParser parser = new MarkPriceTriggerParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("""
                {"symbol":"BTC-USDT","eventTime":"2026-07-01T00:00:00Z"}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
