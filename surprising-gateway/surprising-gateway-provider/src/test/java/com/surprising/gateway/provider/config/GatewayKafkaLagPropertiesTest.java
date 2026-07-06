package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class GatewayKafkaLagPropertiesTest {

    @Test
    void defaultsToLegacyConsumerGroupsUntilProductTopicsAreEnabled() {
        GatewayProperties.KafkaLag kafka = new GatewayProperties.KafkaLag();

        assertThat(kafka.getConsumerGroups())
                .extracting(GatewayProperties.KafkaConsumerGroup::getGroupId)
                .contains("surprising-matching-v1", "surprising-account-v1", "surprising-risk-v1");
        assertThat(kafka.getConsumerGroups().get(0).getTopics())
                .containsExactly("surprising.perp.order.commands.v1");
    }

    @Test
    void canResolveConsumerGroupsFromProductLine() {
        GatewayProperties.KafkaLag kafka = new GatewayProperties.KafkaLag();
        kafka.setProductLine(ProductLine.LINEAR_DELIVERY);
        kafka.setProductTopicsEnabled(true);

        assertThat(kafka.getConsumerGroups())
                .extracting(GatewayProperties.KafkaConsumerGroup::getGroupId)
                .containsExactly(
                        "surprising-linear-delivery-matching-v1",
                        "surprising-linear-delivery-account-v1",
                        "surprising-linear-delivery-risk-v1",
                        "surprising-linear-delivery-liquidation-v1",
                        "surprising-linear-delivery-trigger-v1",
                        "surprising-linear-delivery-mark-price-v1",
                        "surprising-linear-delivery-candlestick-v1");
        assertThat(kafka.getConsumerGroups().get(0).getTopics())
                .containsExactly("surprising.linear-delivery.order.commands.v1");
        assertThat(kafka.getConsumerGroups().get(3).getTopics())
                .containsExactly("surprising.linear-delivery.liquidation.candidates.v1",
                        "surprising.linear-delivery.match.results.v1");
        assertThat(kafka.getConsumerGroups().get(5).getTopics())
                .containsExactly("surprising.linear-delivery.index.price.v1",
                        "surprising.linear-delivery.book.ticker.v1",
                        "surprising.linear-delivery.trade.events.v1",
                        "surprising.linear-delivery.funding.rate.v1");
    }
}
