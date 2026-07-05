package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.trigger.model.MarkTrigger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IndexPriceTriggerParserTest {

    @Test
    void parsesIndexPriceEnvelopeWithoutPriceApiModel() {
        IndexPriceTriggerParser parser = new IndexPriceTriggerParser(new ObjectMapper());

        MarkTrigger trigger = parser.parse("""
                {"symbol":"btc-usdt","sequence":77,"indexPrice":"65000.1","eventTime":"2026-07-01T00:00:00Z"}
                """);

        assertThat(trigger.symbol()).isEqualTo("BTC-USDT");
        assertThat(trigger.sequence()).isEqualTo(77L);
        assertThat(trigger.eventTime()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void parsesEpochSecondsEventTimeFromIndexEvent() {
        IndexPriceTriggerParser parser = new IndexPriceTriggerParser(new ObjectMapper());

        MarkTrigger trigger = parser.parse("""
                {"symbol":"BTC-USDT","sequence":78,"eventTime":1.782915952572367E9}
                """);

        assertThat(trigger.symbol()).isEqualTo("BTC-USDT");
        assertThat(trigger.sequence()).isEqualTo(78L);
        assertThat(trigger.eventTime()).isEqualTo(Instant.ofEpochSecond(1_782_915_952L, 572_367_000L));
    }

    @Test
    void rejectsMissingSequence() {
        IndexPriceTriggerParser parser = new IndexPriceTriggerParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("""
                {"symbol":"BTC-USDT","eventTime":"2026-07-01T00:00:00Z"}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
