package com.surprising.websocket.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class WebSocketPropertiesTest {

    @Test
    void defaultsToLinearPerpetualTopics() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setGroupId("node-a");

        assertThat(properties.getKafka().getGroupId()).isEqualTo("surprising-linear-perp-websocket-v1-node-a");
        assertThat(properties.getKafka().getCandleTopic()).isEqualTo("surprising.linear-perp.candle.events.v1");
    }

    @Test
    void canResolveFanoutTopicsAndPerNodeGroupFromProductLine() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setGroupId("node-a");

        assertThat(properties.getKafka().getGroupId())
                .isEqualTo("surprising-linear-delivery-websocket-v1-node-a");
        assertThat(properties.getKafka().getCandleTopic())
                .isEqualTo("surprising.linear-delivery.candle.events.v1");
        assertThat(properties.getKafka().getPriceEventsTopic())
                .isEqualTo("surprising.linear-delivery.price.events.v1");
        assertThat(properties.getKafka().isFundingRateTopicEnabled()).isFalse();
        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.linear-delivery.funding.rate.v1");
    }

    @Test
    void canResolveFundingRateTopicForFundingProductLine() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);

        assertThat(properties.getKafka().isFundingRateTopicEnabled()).isTrue();
        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.inverse-perp.funding.rate.v1");
    }

}
