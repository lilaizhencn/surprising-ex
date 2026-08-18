package com.surprising.candlestick.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class CandlestickPropertiesTest {

    @Test
    void defaultsToLinearPerpetualTopics() {
        CandlestickProperties properties = new CandlestickProperties();

        assertThat(properties.getKafka().getTradeTopic()).isEqualTo("surprising.linear-perp.match.trades.v1");
        assertThat(properties.getKafka().getCandleTopic()).isEqualTo("surprising.linear-perp.candle.events.v1");
        assertThat(properties.getKafka().getApplicationId()).isEqualTo("surprising-linear-perp-candlestick-v1");
    }

    @Test
    void canResolveCandlestickTopicsAndApplicationIdFromProductLine() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);

        assertThat(properties.getKafka().getTradeTopic())
                .isEqualTo("surprising.inverse-delivery.match.trades.v1");
        assertThat(properties.getKafka().getCandleTopic())
                .isEqualTo("surprising.inverse-delivery.candle.events.v1");
        assertThat(properties.getKafka().getApplicationId())
                .isEqualTo("surprising-inverse-delivery-candlestick-v1");
    }

    @Test
    void explicitApplicationIdOverrideAllowsPartitionedTopologyMigration() {
        CandlestickProperties properties = new CandlestickProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setApplicationIdOverride("surprising-linear-perp-candlestick-v2");

        assertThat(properties.getKafka().getApplicationId())
                .isEqualTo("surprising-linear-perp-candlestick-v2");
    }
}
