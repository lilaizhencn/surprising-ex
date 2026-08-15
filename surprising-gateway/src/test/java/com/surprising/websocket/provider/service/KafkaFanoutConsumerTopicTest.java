package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.surprising.product.api.ProductLine;
import com.surprising.websocket.provider.config.WebSocketProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KafkaFanoutConsumerTopicTest {

    @Test
    void exposesResolvedTopicsAndGroupFromProperties() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setGroupId("node-b");
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(new ObjectMapper(),
                mock(SubscriptionRegistry.class), mock(CandleUpdateCoalescer.class), properties);

        assertThat(consumer.groupId()).isEqualTo("surprising-inverse-delivery-websocket-v1-node-b");
        assertThat(consumer.candleTopic()).isEqualTo("surprising.inverse-delivery.candle.events.v1");
        assertThat(consumer.tradeTopic()).isEqualTo("surprising.inverse-delivery.trade.events.v1");
        assertThat(consumer.orderBookDepthTopic()).isEqualTo("surprising.inverse-delivery.orderbook.depth.v1");
        assertThat(consumer.indexPriceTopic()).isEqualTo("surprising.inverse-delivery.index.price.v1");
        assertThat(consumer.markPriceTopic()).isEqualTo("surprising.inverse-delivery.mark.price.v1");
        assertThat(consumer.fundingRateListenerEnabled()).isFalse();
        assertThat(consumer.fundingRateTopic()).isEqualTo("surprising.perp.funding.rate.v1");
        assertThat(consumer.orderEventsTopic()).isEqualTo("surprising.inverse-delivery.order.events.v1");
        assertThat(consumer.triggerOrderEventsTopic())
                .isEqualTo("surprising.inverse-delivery.trigger-order.events.v1");
        assertThat(consumer.matchResultsTopic()).isEqualTo("surprising.inverse-delivery.match.results.v1");
        assertThat(consumer.matchTradesTopic()).isEqualTo("surprising.inverse-delivery.match.trades.v1");
        assertThat(consumer.positionEventsTopic())
                .isEqualTo("surprising.inverse-delivery.account.position.events.v1");
        assertThat(consumer.accountRiskEventsTopic())
                .isEqualTo("surprising.inverse-delivery.risk.account.events.v1");
        assertThat(consumer.positionRiskEventsTopic())
                .isEqualTo("surprising.inverse-delivery.risk.position.events.v1");
    }

    @Test
    void enablesFundingRateFanoutForFundingProductLine() {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(new ObjectMapper(),
                mock(SubscriptionRegistry.class), mock(CandleUpdateCoalescer.class), properties);

        assertThat(consumer.fundingRateListenerEnabled()).isTrue();
        assertThat(consumer.fundingRateTopic()).isEqualTo("surprising.inverse-perp.funding.rate.v1");
    }
}
