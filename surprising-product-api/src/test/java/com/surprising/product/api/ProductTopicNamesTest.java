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
        assertThat(linear.triggerOrderEventsTopic())
                .isEqualTo("surprising.linear-perp.trigger-order.events.v1");
        assertThat(inverse.matchTradesTopic()).isEqualTo("surprising.inverse-perp.match.trades.v1");
        assertThat(linear.indexPriceTopic()).isEqualTo("surprising.linear-perp.index.price.v1");
        assertThat(linear.bookTickerTopic()).isEqualTo("surprising.linear-perp.book.ticker.v1");
        assertThat(linear.markPriceTopic()).isEqualTo("surprising.linear-perp.mark.price.v1");
        assertThat(linear.fundingRateTopic()).isEqualTo("surprising.linear-perp.funding.rate.v1");
        assertThat(linear.accountPositionEventsTopic())
                .isEqualTo("surprising.linear-perp.account.position.events.v1");
        assertThat(linear.accountPositionCacheEventsTopic())
                .isEqualTo("surprising.linear-perp.account.position-cache.events.v1");
        assertThat(linear.accountLiquidationFeeEventsTopic())
                .isEqualTo("surprising.linear-perp.account.liquidation-fee.events.v1");
        assertThat(linear.accountRiskEventsTopic()).isEqualTo("surprising.linear-perp.risk.account.events.v1");
        assertThat(linear.positionRiskEventsTopic()).isEqualTo("surprising.linear-perp.risk.position.events.v1");
        assertThat(linear.liquidationCandidatesTopic())
                .isEqualTo("surprising.linear-perp.liquidation.candidates.v1");
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
