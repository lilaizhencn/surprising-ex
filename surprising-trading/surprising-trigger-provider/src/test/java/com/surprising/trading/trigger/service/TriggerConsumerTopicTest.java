package com.surprising.trading.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.trigger.config.TriggerProperties;
import org.junit.jupiter.api.Test;

class TriggerConsumerTopicTest {

    @Test
    void consumersResolveTopicsAndGroupFromProductLine() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        TriggerOrderService triggerOrderService = mock(TriggerOrderService.class);

        MarkPriceTriggerConsumer markConsumer = new MarkPriceTriggerConsumer(
                mock(MarkPriceTriggerParser.class), triggerOrderService, properties);
        IndexPriceTriggerConsumer indexConsumer = new IndexPriceTriggerConsumer(
                mock(IndexPriceTriggerParser.class), triggerOrderService, properties);
        LastPriceTriggerConsumer lastConsumer = new LastPriceTriggerConsumer(
                mock(LastPriceTriggerParser.class), triggerOrderService, properties);

        assertThat(markConsumer.markPriceTopic()).isEqualTo("surprising.linear-delivery.mark.price.v1");
        assertThat(indexConsumer.indexPriceTopic()).isEqualTo("surprising.linear-delivery.index.price.v1");
        assertThat(lastConsumer.lastPriceTopic()).isEqualTo("surprising.linear-delivery.match.trades.v1");
        assertThat(markConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
        assertThat(indexConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
        assertThat(lastConsumer.groupId()).isEqualTo("surprising-linear-delivery-trigger-v1");
    }
}
