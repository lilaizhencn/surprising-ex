package com.surprising.candlestick.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class CandlestickPropertiesTest {

    @Test
    void defaultsToLegacyPerpTopicsUntilProductTopicsAreEnabled() {
        CandlestickProperties properties = new CandlestickProperties();

        assertThat(properties.getKafka().getTradeTopic()).isEqualTo("surprising.perp.match.trades.v1");
        assertThat(properties.getKafka().getCandleTopic()).isEqualTo("surprising.perp.candle.events.v1");
        assertThat(properties.getKafka().getApplicationId()).isEqualTo("surprising-candlestick-v1");
    }

    @Test
    void canResolveCandlestickTopicsAndApplicationIdFromProductLine() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);

        assertThat(properties.getKafka().getTradeTopic())
                .isEqualTo("surprising.inverse-delivery.match.trades.v1");
        assertThat(properties.getKafka().getCandleTopic())
                .isEqualTo("surprising.inverse-delivery.candle.events.v1");
        assertThat(properties.getKafka().getApplicationId())
                .isEqualTo("surprising-inverse-delivery-candlestick-v1");
    }
}
