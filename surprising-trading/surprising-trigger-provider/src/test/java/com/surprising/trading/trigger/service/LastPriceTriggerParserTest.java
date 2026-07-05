package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.trading.trigger.model.LastPriceTrigger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LastPriceTriggerParserTest {

    @Test
    void parsesMatchTradeEnvelopeWithoutFullApiModel() {
        LastPriceTriggerParser parser = new LastPriceTriggerParser(new ObjectMapper());

        LastPriceTrigger trigger = parser.parse("""
                {"tradeId":91,"symbol":"btc-usdt","priceTicks":650001,"eventTime":"2026-07-01T00:00:00Z"}
                """);

        assertThat(trigger.symbol()).isEqualTo("BTC-USDT");
        assertThat(trigger.sequence()).isEqualTo(91L);
        assertThat(trigger.priceTicks()).isEqualTo(650_001L);
        assertThat(trigger.eventTime()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void parsesEpochSecondsEventTimeFromMatchTradeEvent() {
        LastPriceTriggerParser parser = new LastPriceTriggerParser(new ObjectMapper());

        LastPriceTrigger trigger = parser.parse("""
                {"tradeId":92,"symbol":"BTC-USDT","priceTicks":650002,"eventTime":1.782915952572367E9}
                """);

        assertThat(trigger.symbol()).isEqualTo("BTC-USDT");
        assertThat(trigger.sequence()).isEqualTo(92L);
        assertThat(trigger.priceTicks()).isEqualTo(650_002L);
        assertThat(trigger.eventTime()).isEqualTo(Instant.ofEpochSecond(1_782_915_952L, 572_367_000L));
    }

    @Test
    void rejectsMissingTradeIdOrPrice() {
        LastPriceTriggerParser parser = new LastPriceTriggerParser(new ObjectMapper());

        assertThatThrownBy(() -> parser.parse("""
                {"symbol":"BTC-USDT","priceTicks":650002,"eventTime":"2026-07-01T00:00:00Z"}
                """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"tradeId":92,"symbol":"BTC-USDT","eventTime":"2026-07-01T00:00:00Z"}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
