package com.surprising.realtime.provider.option.consumer;

import com.surprising.price.api.model.PriceEventType;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.utils.InstrumentIds;
import com.surprising.product.api.utils.ProductTopicNames;
import com.surprising.realtime.provider.option.service.OptionQuoteService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Kafka协议入口；校验Topic/key和原生ID后把完整源事件交给期权视图所有者。 */
@Component
@ConditionalOnProperty(name = "surprising.candlestick.kafka.product-line", havingValue = "OPTION")
public final class OptionQuotePriceConsumer {
    private final OptionQuoteService quotes;
    private final ObjectMapper mapper;
    private final String group = "surprising-option-reference-" + UUID.randomUUID();

    public OptionQuotePriceConsumer(OptionQuoteService quotes, ObjectMapper mapper) {
        this.quotes = quotes;
        this.mapper = mapper;
    }
    public String topic() { return ProductTopicNames.of(ProductLine.OPTION).priceEventsTopic(); }
    public String groupId() { return group; }

    @KafkaListener(topics = "#{__listener.topic()}", groupId = "#{__listener.groupId()}",
            properties = "auto.offset.reset=latest",
            containerFactory = "candlestickInstrumentSnapshotKafkaListenerContainerFactory")
    public void onPrice(ConsumerRecord<String, String> record) throws Exception {
        if (!topic().equals(record.topic())) throw new IllegalArgumentException("option price topic mismatch");
        var publication = mapper.readValue(record.value(), PricePublishedEvent.class);
        if (!publication.instrumentId().equals(record.key())) throw new IllegalArgumentException("option price key mismatch");
        if (publication.eventType() != PriceEventType.MARK_PRICE) return;
        var mark = publication.markPrice();
        quotes.accept(InstrumentIds.parse(mark.result().instrumentId()), mark);
    }
}
