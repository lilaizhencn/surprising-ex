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
        assertThat(properties.getKafka().getMatchTradesTopic()).isEqualTo("surprising.linear-perp.match.trades.v1");
        assertThat(properties.getKafka().getTriggerOrderEventsTopic())
                .isEqualTo("surprising.linear-perp.trigger-order.events.v1");
        assertThat(properties.getKafka().getAccountRiskEventsTopic())
                .isEqualTo("surprising.linear-perp.risk.account.events.v1");
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
        assertThat(properties.getKafka().getOrderBookDepthTopic())
                .isEqualTo("surprising.linear-delivery.orderbook.depth.v1");
        assertThat(properties.getKafka().getPriceEventsTopic())
                .isEqualTo("surprising.linear-delivery.price.events.v1");
        assertThat(properties.getKafka().isFundingRateTopicEnabled()).isFalse();
        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.linear-delivery.funding.rate.v1");
        assertThat(properties.getKafka().getOrderEventsTopic())
                .isEqualTo("surprising.linear-delivery.order.events.v1");
        assertThat(properties.getKafka().getTriggerOrderEventsTopic())
                .isEqualTo("surprising.linear-delivery.trigger-order.events.v1");
        assertThat(properties.getKafka().getMatchResultsTopic())
                .isEqualTo("surprising.linear-delivery.match.results.v1");
        assertThat(properties.getKafka().getMatchTradesTopic())
                .isEqualTo("surprising.linear-delivery.match.trades.v1");
        assertThat(properties.getKafka().getPositionEventsTopic())
                .isEqualTo("surprising.linear-delivery.account.position.events.v1");
        assertThat(properties.getKafka().getAccountRiskEventsTopic())
                .isEqualTo("surprising.linear-delivery.risk.account.events.v1");
        assertThat(properties.getKafka().getPositionRiskEventsTopic())
                .isEqualTo("surprising.linear-delivery.risk.position.events.v1");
    }

    @Test
    void canResolveFundingRateTopicForFundingProductLine() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);

        assertThat(properties.getKafka().isFundingRateTopicEnabled()).isTrue();
        assertThat(properties.getKafka().getFundingRateTopic())
                .isEqualTo("surprising.inverse-perp.funding.rate.v1");
    }

    @Test
    void resolvesSixAuthoritativeCoreEventTopics() {
        WebSocketProperties properties = new WebSocketProperties();

        for (ProductLine productLine : ProductLine.values()) {
            properties.getKafka().setProductLine(productLine);
            assertThat(properties.getKafka().getCoreEventsTopic())
                    .isEqualTo("surprising." + productLine.topicSegment() + ".core.events.v1");
        }
    }
}
