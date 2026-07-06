package com.surprising.product.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTopicNamesTest {

    @Test
    void buildsProductSpecificTopicNames() {
        ProductTopicNames spot = ProductTopicNames.of(ProductLine.SPOT);
        ProductTopicNames linear = ProductTopicNames.of(ProductLine.LINEAR_PERPETUAL);
        ProductTopicNames inverse = ProductTopicNames.of(ProductLine.INVERSE_PERPETUAL);

        assertThat(spot.orderCommandsTopic()).isEqualTo("surprising.spot.order.commands.v1");
        assertThat(linear.orderCommandsTopic()).isEqualTo("surprising.linear-perp.order.commands.v1");
        assertThat(inverse.matchTradesTopic()).isEqualTo("surprising.inverse-perp.match.trades.v1");
    }

    @Test
    void buildsDeliveryAndOptionSettlementTopics() {
        assertThat(ProductTopicNames.of(ProductLine.LINEAR_DELIVERY).deliverySettlementsTopic())
                .isEqualTo("surprising.linear-delivery.delivery.settlements.v1");
        assertThat(ProductTopicNames.of(ProductLine.OPTION).optionExercisesTopic())
                .isEqualTo("surprising.option.option.exercises.v1");
    }

    @Test
    void buildsProductSpecificConsumerGroups() {
        assertThat(ProductTopicNames.of(ProductLine.LINEAR_PERPETUAL).consumerGroup("matching"))
                .isEqualTo("surprising-linear-perp-matching-v1");
    }
}
